import java.net.*;

/**
 * ExperimentRunner — Week 3
 *
 * Orchestrates the three required experiments described in the lab spec.
 * Run this class to produce the console logs needed as screenshots for the report.
 *
 * ─── Experiments ──────────────────────────────────────────────────────────
 *
 *  Experiment 1 — At-least-once + non-idempotent + reply loss
 *    Setup:  semantics = at-least-once, replyLossProb = 0.3, op = transferMoney
 *    Result: Server may process the transfer multiple times → wrong balance.
 *
 *  Experiment 2 — At-most-once + non-idempotent + reply loss
 *    Setup:  semantics = at-most-once, replyLossProb = 0.3, op = transferMoney
 *    Result: Server executes once, returns cached reply on retries → correct balance.
 *
 *  Experiment 3 — Idempotent operation (checkBalance) under both semantics
 *    Setup:  op = checkBalance, both semantics
 *    Result: Repeated execution is safe either way → state unchanged.
 *
 * ─── Usage ────────────────────────────────────────────────────────────────
 *
 *   Compile all files, then run:
 *     java ExperimentRunner
 *
 *   This class simulates the scenario LOCALLY (no real network needed)
 *   using a loopback server stub.  For the full demo with two machines,
 *   use RetryClientLogic + RequestHistoryManager + MonitorManager directly.
 *
 * Depends on: RequestHistoryManager, LossSimulator, RetryClientLogic.
 */
public class ExperimentRunner {

    // ─── Shared configuration ────────────────────────────────────────────────

    private static final int    SERVER_PORT      = 2222;
    private static final String SERVER_HOST      = "127.0.0.1";
    private static final double REPLY_LOSS_PROB  = 0.5;   // 50% — high enough to reliably trigger retries in demos
    private static final double NO_LOSS          = 0.0;

    // Simulated account state (shared mutable — intentionally not thread-safe to show the problem)
    private static double accountA = 1000.00;
    private static double accountB = 500.00;
    private static final double TRANSFER_AMOUNT = 200.00;

    // ─── Entry point ─────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        printBanner("WEEK 3 EXPERIMENT RUNNER");

        runExperiment1();
        resetAccounts();

        runExperiment2();
        resetAccounts();

        runExperiment3();
    }

    // ─── Experiment 1 ────────────────────────────────────────────────────────

    /**
     * AT-LEAST-ONCE + non-idempotent (transferMoney) + reply loss.
     *
     * Expected outcome: transfer may be applied more than once → wrong balance.
     */
    private static void runExperiment1() {
        printBanner("EXPERIMENT 1: At-Least-Once + Non-Idempotent + Reply Loss");

        System.out.println("[Setup] semantics        = AT-LEAST-ONCE");
        System.out.println("[Setup] reply loss prob  = " + REPLY_LOSS_PROB);
        System.out.println("[Setup] operation        = transferMoney");
        System.out.printf ("[Setup] Initial balances: A=%.2f  B=%.2f%n", accountA, accountB);
        System.out.println("[Setup] Transfer amount  = " + TRANSFER_AMOUNT);
        System.out.println();

        RequestHistoryManager history = new RequestHistoryManager();  // NOT used in at-least-once
        // clientId and requestId managed inline (RequestTracker inlined)
        int clientId  = new java.util.Random().nextInt(100_000);
        int requestId = 1;
        String key    = RetryClientLogic.buildKey(clientId, requestId);

        System.out.println("[Exp1] Sending transfer request key=" + key);

        for (int attempt = 1; attempt <= RetryClientLogic.MAX_RETRIES; attempt++) {
            System.out.println("[Exp1] Attempt " + attempt + "/" + RetryClientLogic.MAX_RETRIES);

            // Server receives and processes (no duplicate check under at-least-once)
            System.out.println("[Server] Processing NEW request: " + key);
            applyTransfer();   // ← executed every attempt that reaches the server

            // Simulate reply loss (server sends reply but it gets dropped)
            boolean dropped = simulateReplyLoss();
            if (dropped && attempt < RetryClientLogic.MAX_RETRIES) {
                System.out.println("[Client] Timeout. Retrying request " + key);
                continue;
            }

            if (!dropped) {
                System.out.println("[Client] Reply received on attempt " + attempt);
                break;
            }
        }

        System.out.println();
        System.out.printf("[Exp1] FINAL balances: A=%.2f  B=%.2f%n", accountA, accountB);
        System.out.printf("[Exp1] Expected if applied once: A=%.2f  B=%.2f%n",
                1000.00 - TRANSFER_AMOUNT, 500.00 + TRANSFER_AMOUNT);
        System.out.println("[Exp1] RESULT: " + (accountA < 1000.00 - TRANSFER_AMOUNT
                ? "WRONG — transfer applied multiple times (demonstrates the problem)"
                : "Correct by luck (retry not triggered this run)"));
        printSeparator();
    }

    // ─── Experiment 2 ────────────────────────────────────────────────────────

    /**
     * AT-MOST-ONCE + non-idempotent (transferMoney) + reply loss.
     *
     * Expected outcome: transfer applied exactly once → correct balance.
     */
    private static void runExperiment2() {
        printBanner("EXPERIMENT 2: At-Most-Once + Non-Idempotent + Reply Loss");

        System.out.println("[Setup] semantics        = AT-MOST-ONCE");
        System.out.println("[Setup] reply loss prob  = " + REPLY_LOSS_PROB);
        System.out.println("[Setup] operation        = transferMoney");
        System.out.printf ("[Setup] Initial balances: A=%.2f  B=%.2f%n", accountA, accountB);
        System.out.println("[Setup] Transfer amount  = " + TRANSFER_AMOUNT);
        System.out.println();

        RequestHistoryManager history = new RequestHistoryManager();  // at-most-once: history IS used
        // clientId and requestId managed inline (RequestTracker inlined)
        int clientId  = new java.util.Random().nextInt(100_000);
        int requestId = 1;
        String key    = RetryClientLogic.buildKey(clientId, requestId);

        // Simulate the reply bytes the server would send
        byte[] cachedReply = null;

        System.out.println("[Exp2] Sending transfer request key=" + key);

        for (int attempt = 1; attempt <= RetryClientLogic.MAX_RETRIES; attempt++) {
            System.out.println("[Exp2] Attempt " + attempt + "/" + RetryClientLogic.MAX_RETRIES);

            // Server checks history first
            byte[] existing = history.getCachedReply(clientId, requestId);

            if (existing != null) {
                // Duplicate — resend cached reply, do NOT re-execute
                System.out.println("[Server] Re-sending stored reply for: " + key);
                cachedReply = existing;
            } else {
                // New request — execute and store
                System.out.println("[Server] Processing NEW request: " + key);
                applyTransfer();   // ← executed ONCE only
                cachedReply = ("TRANSFER:OK:A=" + accountA + ":B=" + accountB).getBytes();
                history.storeReply(clientId, requestId, cachedReply);
            }

            // Simulate reply loss
            boolean dropped = simulateReplyLoss();
            if (dropped && attempt < RetryClientLogic.MAX_RETRIES) {
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
        System.out.printf("[Exp2] FINAL balances: A=%.2f  B=%.2f%n", accountA, accountB);
        System.out.printf("[Exp2] Expected:       A=%.2f  B=%.2f%n",
                1000.00 - TRANSFER_AMOUNT, 500.00 + TRANSFER_AMOUNT);
        boolean correct = Math.abs(accountA - (1000.00 - TRANSFER_AMOUNT)) < 0.001;
        System.out.println("[Exp2] RESULT: " + (correct
                ? "CORRECT — transfer applied exactly once"
                : "Unexpected — check loss simulation"));
        printSeparator();
    }

    // ─── Experiment 3 ────────────────────────────────────────────────────────

    /**
     * Idempotent operation (checkBalance) under both semantics.
     *
     * Expected outcome: repeated execution is harmless either way.
     */
    private static void runExperiment3() {
        printBanner("EXPERIMENT 3: Idempotent Operation (checkBalance) — Both Semantics");

        System.out.println("[Setup] operation       = checkBalance");
        System.out.println("[Setup] reply loss prob = " + REPLY_LOSS_PROB);
        System.out.printf ("[Setup] Initial balance: A=%.2f%n", accountA);
        System.out.println();

        // clientId and requestId managed inline (RequestTracker inlined)
        int clientId  = new java.util.Random().nextInt(100_000);
        int requestId = 1;
        String key    = RetryClientLogic.buildKey(clientId, requestId);

        System.out.println("--- Under AT-LEAST-ONCE ---");
        System.out.println("[Exp3] Sending checkBalance request key=" + key);
        for (int attempt = 1; attempt <= RetryClientLogic.MAX_RETRIES; attempt++) {
            System.out.println("[Exp3] Attempt " + attempt);
            System.out.println("[Server] Processing NEW request: " + key
                    + "  → balance=" + accountA + "  (state UNCHANGED)");
            boolean dropped = simulateReplyLoss();
            if (!dropped) {
                System.out.println("[Client] Reply received on attempt " + attempt);
                break;
            }
            if (attempt < RetryClientLogic.MAX_RETRIES)
                System.out.println("[Client] Timeout. Retrying request " + key);
        }
        System.out.printf("[Exp3] Balance after repeated reads: A=%.2f  (unchanged — idempotent)%n%n", accountA);

        System.out.println("--- Under AT-MOST-ONCE ---");
        RequestHistoryManager history = new RequestHistoryManager();
        int requestId2 = 2;
        String key2    = RetryClientLogic.buildKey(clientId, requestId2);
        System.out.println("[Exp3] Sending checkBalance request key=" + key2);
        for (int attempt = 1; attempt <= RetryClientLogic.MAX_RETRIES; attempt++) {
            System.out.println("[Exp3] Attempt " + attempt);
            byte[] cached = history.getCachedReply(clientId, requestId2);
            if (cached != null) {
                System.out.println("[Server] Re-sending stored reply for: " + key2);
            } else {
                System.out.println("[Server] Processing NEW request: " + key2
                        + "  → balance=" + accountA + "  (state UNCHANGED)");
                history.storeReply(clientId, requestId2, ("BALANCE:" + accountA).getBytes());
            }
            boolean dropped = simulateReplyLoss();
            if (!dropped) {
                System.out.println("[Client] Reply received on attempt " + attempt);
                break;
            }
            if (attempt < RetryClientLogic.MAX_RETRIES)
                System.out.println("[Client] Timeout. Retrying request " + key2);
        }
        System.out.printf("[Exp3] Balance after at-most-once reads: A=%.2f  (unchanged — idempotent)%n", accountA);
        System.out.println("[Exp3] RESULT: checkBalance is safe under both semantics.");
        System.out.println("[Exp3] PURPOSE: Non-idempotent ops (transfer) are the real problem.");
        printSeparator();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Applies the transfer from A to B once. */
    private static void applyTransfer() {
        accountA -= TRANSFER_AMOUNT;
        accountB += TRANSFER_AMOUNT;
        System.out.printf("[Server] Transfer applied: A=%.2f → A=%.2f   B=%.2f → B=%.2f%n",
                accountA + TRANSFER_AMOUNT, accountA, accountB - TRANSFER_AMOUNT, accountB);
    }

    /** Resets account balances to initial values between experiments. */
    private static void resetAccounts() {
        accountA = 1000.00;
        accountB = 500.00;
        System.out.println("[Reset] Accounts restored: A=" + accountA + "  B=" + accountB);
        System.out.println();
    }

    /**
     * Simulates a reply packet drop with REPLY_LOSS_PROB probability.
     * @return true if dropped, false if delivered.
     */
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
