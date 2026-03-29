import java.util.HashMap;
import java.util.Map;

public class RequestHistoryManager {
    private final Map<String, byte[]> history = new HashMap<>();
    
    // Public API
    public byte[] getCachedReply(int clientId, int requestId) {
        String key = buildKey(clientId, requestId);

        if (history.containsKey(key)) {
            System.out.println("[RequestHistoryManager] Duplicate request detected: " + key);
            System.out.println("[RequestHistoryManager] Re-sending stored reply for: " + key);
            return history.get(key);
        }

        System.out.println("[RequestHistoryManager] Processing NEW request: " + key);
        return null;
    }

    /**
     * Stores the reply for a freshly processed request.
     * Must be called AFTER executing the operation and BEFORE sending the reply,
     * so that any concurrent duplicate arriving during processing is handled.
     */
    public void storeReply(int clientId, int requestId, byte[] reply) {
        String key = buildKey(clientId, requestId);
        history.put(key, reply.clone());   // defensive copy
        System.out.println("[RequestHistoryManager] Stored reply for: " + key
                + "  (total cached=" + history.size() + ")");
    }

    /**
     * Convenience method — identical to getCachedReply but named to match
     * the spec's DuplicateDetector API for drop-in compatibility.
     */
    public boolean isProcessed(int clientId, int requestId) {
        return history.containsKey(buildKey(clientId, requestId));
    }

    /**
     * Returns the number of unique requests currently held in history.
     * Useful for debugging and experiment logs.
     */
    public int size() {
        return history.size();
    }

    /**
     * Clears the entire history (e.g. on server restart between experiments).
     */
    public void clear() {
        history.clear();
        System.out.println("[RequestHistoryManager] History cleared.");
    }

    /**
     * Prints a human-readable summary of all history entries.
     * Useful as a screenshot for the experiment report.
     */
    public void printHistory() {
        System.out.println("=== RequestHistoryManager — History Dump ===");
        if (history.isEmpty()) {
            System.out.println("  (empty)");
        } else {
            for (String key : history.keySet()) {
                byte[] reply = history.get(key);
                System.out.println("  key=" + key + "  replyBytes=" + reply.length);
            }
        }
        System.out.println("  Total entries: " + history.size());
        System.out.println("============================================");
    }

    // Internal helpers
    /**
     * Canonical key string: "clientId-requestId", e.g. "47832-3".
     * Must match RetryClientLogic.buildKey() on the client side.
     */
    public static String buildKey(int clientId, int requestId) {
        return clientId + "-" + requestId;
    }

    // Quick self-test
    public static void main(String[] args) {
        RequestHistoryManager mgr = new RequestHistoryManager();

        System.out.println("=== Test 1: New request ===");
        byte[] cached = mgr.getCachedReply(100, 1);
        System.out.println("getCachedReply returned null (new): " + (cached == null));

        // Simulate server executing the operation and storing reply
        byte[] reply = "TRANSFER:OK:balance=800.00".getBytes();
        mgr.storeReply(100, 1, reply);

        System.out.println("\n=== Test 2: Duplicate request (same clientId+requestId) ===");
        byte[] dup = mgr.getCachedReply(100, 1);
        System.out.println("getCachedReply returned cached reply: " + new String(dup));

        System.out.println("\n=== Test 3: Different request (new requestId) ===");
        byte[] fresh = mgr.getCachedReply(100, 2);
        System.out.println("getCachedReply returned null (new): " + (fresh == null));
        mgr.storeReply(100, 2, "DEPOSIT:OK:balance=1000.00".getBytes());

        System.out.println();
        mgr.printHistory();
    }
}
