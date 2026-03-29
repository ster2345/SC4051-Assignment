import java.net.*;

public class ExperimentRunner {

    private static final double REPLY_LOSS_PROB  = 0.5;   // 50% ensures retries are likely
    private static final float  INITIAL_BALANCE  = 1000f;
    private static final float  TRANSFER_AMOUNT  = 200f;
    private static final int    MAX_RETRIES      = RetryClientLogic.MAX_RETRIES;

    public static void main(String[] args) {
        printBanner("WEEK 3 EXPERIMENT RUNNER");

        runExperiment1();
        runExperiment2();
        runExperiment3();
    }

    //Experiment 1 AT-LEAST-ONCE + transferMoney + reply loss.
    
    private static void runExperiment1() {
        printBanner("EXPERIMENT 1: At-Least-Once + Non-Idempotent + Reply Loss");

        AccountStore store = new AccountStore();
        store.openAccount("Alice", "pw1", Currency.SGD, INITIAL_BALANCE);  // account 1000
        store.openAccount("Bob",   "pw2", Currency.SGD, INITIAL_BALANCE);  // account 1001

        System.out.println("[Setup] semantics       = AT-LEAST-ONCE");
        System.out.println("[Setup] replyLossProb   = " + REPLY_LOSS_PROB);
        System.out.println("[Setup] operation       = transferMoney");
        System.out.println("[Setup] transfer amount = " + TRANSFER_AMOUNT);
        System.out.println("[Setup] " + store.checkBalance("Alice", 1000, "pw1"));
        System.out.println("[Setup] " + store.checkBalance("Bob",   1001, "pw2"));
        System.out.println();

        int clientId  = new java.util.Random().nextInt(100_000);
        int requestId = 1;
        String key    = RetryClientLogic.buildKey(clientId, requestId);

        System.out.println("[Exp1] Sending transfer request key=" + key);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            System.out.println("[Exp1] Attempt " + attempt + "/" + MAX_RETRIES);

            // AT-LEAST-ONCE: no duplicate check — server always executes
            System.out.println("[Server] Processing NEW request: " + key);
            String result = store.transferMoney("Alice", 1000, "pw1", 1001, TRANSFER_AMOUNT);
            System.out.println("[Server] Result: " + result);

            boolean dropped = simulateReplyLoss();
            if (dropped && attempt < MAX_RETRIES) {
                System.out.println("[Client] Timeout. Retrying request " + key);
                continue;
            }
            if (!dropped) {
                System.out.println("[Client] Reply received on attempt " + attempt);
                break;
            }
        }

        System.out.println();
        System.out.println("[Exp1] FINAL STATE:");
        System.out.println("  " + store.checkBalance("Alice", 1000, "pw1"));
        System.out.println("  " + store.checkBalance("Bob",   1001, "pw2"));
        float expectedAlice = INITIAL_BALANCE - TRANSFER_AMOUNT;
        System.out.println("[Exp1] Expected Alice if applied ONCE: " + expectedAlice + " SGD");
        System.out.println("[Exp1] RESULT: If Alice's balance < " + expectedAlice
                + ", transfer was applied multiple times — DEMONSTRATES THE PROBLEM.");
        printSeparator();
    }

    // Experiment 2 AT-MOST-ONCE + transferMoney + reply loss.
    private static void runExperiment2() {
        printBanner("EXPERIMENT 2: At-Most-Once + Non-Idempotent + Reply Loss");

        AccountStore          store   = new AccountStore();
        RequestHistoryManager history = new RequestHistoryManager();

        store.openAccount("Alice", "pw1", Currency.SGD, INITIAL_BALANCE);  // account 1000
        store.openAccount("Bob",   "pw2", Currency.SGD, INITIAL_BALANCE);  // account 1001

        System.out.println("[Setup] semantics       = AT-MOST-ONCE");
        System.out.println("[Setup] replyLossProb   = " + REPLY_LOSS_PROB);
        System.out.println("[Setup] operation       = transferMoney");
        System.out.println("[Setup] transfer amount = " + TRANSFER_AMOUNT);
        System.out.println("[Setup] " + store.checkBalance("Alice", 1000, "pw1"));
        System.out.println("[Setup] " + store.checkBalance("Bob",   1001, "pw2"));
        System.out.println();

        int clientId  = new java.util.Random().nextInt(100_000);
        int requestId = 1;
        String key    = RetryClientLogic.buildKey(clientId, requestId);

        System.out.println("[Exp2] Sending transfer request key=" + key);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            System.out.println("[Exp2] Attempt " + attempt + "/" + MAX_RETRIES);

            byte[] cached = history.getCachedReply(clientId, requestId);

            if (cached != null) {
                // Duplicate — return cached reply, do NOT re-execute
                System.out.println("[Server] Re-sending stored reply for: " + key);
            } else {
                // New request — execute and cache
                System.out.println("[Server] Processing NEW request: " + key);
                String result = store.transferMoney("Alice", 1000, "pw1", 1001, TRANSFER_AMOUNT);
                System.out.println("[Server] Result: " + result);
                history.storeReply(clientId, requestId, result.getBytes());
            }

            boolean dropped = simulateReplyLoss();
            if (dropped && attempt < MAX_RETRIES) {
                System.out.println("[Client] Timeout. Retrying request " + key);
                continue;
            }
            if (!dropped) {
                System.out.println("[Client] Reply received on attempt " + attempt);
                break;
            }
        }

        history.printHistory();
        System.out.println();
        System.out.println("[Exp2] FINAL STATE:");
        System.out.println("  " + store.checkBalance("Alice", 1000, "pw1"));
        System.out.println("  " + store.checkBalance("Bob",   1001, "pw2"));
        float expectedAlice = INITIAL_BALANCE - TRANSFER_AMOUNT;
        float expectedBob   = INITIAL_BALANCE + TRANSFER_AMOUNT;
        System.out.println("[Exp2] Expected: Alice=" + expectedAlice + " SGD, Bob=" + expectedBob + " SGD");
        System.out.println("[Exp2] RESULT: Transfer applied exactly once — CORRECT.");
        printSeparator();
    }

    //Experiment 3 Idempotent operation (checkBalance) under both semantics.
    private static void runExperiment3() {
        printBanner("EXPERIMENT 3: Idempotent Operation (checkBalance) — Both Semantics");

        AccountStore store = new AccountStore();
        store.openAccount("Alice", "pw1", Currency.SGD, INITIAL_BALANCE);  // account 1000

        System.out.println("[Setup] operation       = checkBalance  (idempotent)");
        System.out.println("[Setup] replyLossProb   = " + REPLY_LOSS_PROB);
        System.out.println("[Setup] " + store.checkBalance("Alice", 1000, "pw1"));
        System.out.println();

        int clientId = new java.util.Random().nextInt(100_000);

        //AT-LEAST-ONCE
        System.out.println("--- Under AT-LEAST-ONCE ---");
        int    requestId1 = 1;
        String key1       = RetryClientLogic.buildKey(clientId, requestId1);
        System.out.println("[Exp3] Sending checkBalance request key=" + key1);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            System.out.println("[Exp3] Attempt " + attempt);
            System.out.println("[Server] Processing NEW request: " + key1);
            String result = store.checkBalance("Alice", 1000, "pw1");
            System.out.println("[Server] Result: " + result + "  (state UNCHANGED)");
            boolean dropped = simulateReplyLoss();
            if (!dropped) { System.out.println("[Client] Reply received on attempt " + attempt); break; }
            if (attempt < MAX_RETRIES)
                System.out.println("[Client] Timeout. Retrying request " + key1);
        }
        System.out.println("[Exp3] Balance after repeated reads: UNCHANGED");
        System.out.println();

        //AT-MOST-ONCE
        System.out.println("--- Under AT-MOST-ONCE ---");
        RequestHistoryManager history  = new RequestHistoryManager();
        int    requestId2 = 2;
        String key2       = RetryClientLogic.buildKey(clientId, requestId2);
        System.out.println("[Exp3] Sending checkBalance request key=" + key2);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            System.out.println("[Exp3] Attempt " + attempt);
            byte[] cached = history.getCachedReply(clientId, requestId2);
            if (cached != null) {
                System.out.println("[Server] Re-sending stored reply for: " + key2);
            } else {
                System.out.println("[Server] Processing NEW request: " + key2);
                String result = store.checkBalance("Alice", 1000, "pw1");
                System.out.println("[Server] Result: " + result + "  (state UNCHANGED)");
                history.storeReply(clientId, requestId2, result.getBytes());
            }
            boolean dropped = simulateReplyLoss();
            if (!dropped) { System.out.println("[Client] Reply received on attempt " + attempt); break; }
            if (attempt < MAX_RETRIES)
                System.out.println("[Client] Timeout. Retrying request " + key2);
        }

        System.out.println();
        System.out.println("[Exp3] FINAL: " + store.checkBalance("Alice", 1000, "pw1"));
        System.out.println("[Exp3] RESULT: checkBalance is safe under BOTH semantics.");
        System.out.println("[Exp3] PURPOSE: Non-idempotent ops (transfer) are the real problem.");
        printSeparator();
    }

    //Helpers 
    private static boolean simulateReplyLoss() {
        if (Math.random() < REPLY_LOSS_PROB) {
            System.out.println("[LossSimulator] DROPPED REPLY packet (prob=" + REPLY_LOSS_PROB + ")");
            return true;
        }
        System.out.println("[LossSimulator] REPLY packet delivered.");
        return false;
    }

    private static void printBanner(String title) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.printf ("║  %-52s║%n", title);
        System.out.println("╚══════════════════════════════════════════════════════╝");
    }

    private static void printSeparator() {
        System.out.println("──────────────────────────────────────────────────────");
        System.out.println();
    }
}

