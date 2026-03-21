import java.util.HashMap;
import java.util.Map;

/**
 * editted RequestHistoryManager 
 *
 * Server-side component that implements AT-MOST-ONCE invocation semantics.
 *
 * Every successfully processed request is stored in a history map keyed by
 * (clientId, requestId).  On each incoming packet the server should:
 *
 *   1. Extract clientId  from bytes [0-3] of the packet (via RetryClientLogic.readInt).
 *   2. Extract requestId from bytes [4-7] of the packet.
 *   3. Call handle() — this either:
 *        a. Returns the cached reply for a duplicate (no re-execution), or
 *        b. Returns null for a new request (caller must execute, then call storeReply).
 *
 * ─── Integration pattern inside the server receive loop ───────────────────
 *
 *   RequestHistoryManager history = new RequestHistoryManager();
 *
 *   while (true) {
 *       socket.receive(requestPacket);
 *       byte[] data = requestPacket.getData();
 *       int clientId  = RetryClientLogic.readInt(data, 0);
 *       int requestId = RetryClientLogic.readInt(data, 4);
 *
 *       byte[] cached = history.getCachedReply(clientId, requestId);
 *       if (cached != null) {
 *           // Duplicate — resend old reply, do NOT re-execute
 *           sendWithPossibleLoss(cached, clientAddress, clientPort);
 *       } else {
 *           // New request — execute and store
 *           byte[] reply = processRequest(data);
 *           history.storeReply(clientId, requestId, reply);
 *           sendWithPossibleLoss(reply, clientAddress, clientPort);
 *       }
 *   }
 *
 * ─── Required log messages (for screenshots / report) ─────────────────────
 *   "Processing NEW request: <key>"
 *   "Duplicate request detected: <key>"
 *   "Re-sending stored reply for: <key>"
 *
 * Fully self-contained. Use RetryClientLogic.readInt() for header parsing on the server.
 */
public class RequestHistoryManager {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /**
     * History map: "clientId-requestId" → cached reply bytes.
     * e.g.  "47832-3" → byte[] { ... }
     */
    private final Map<String, byte[]> history = new HashMap<>();

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Checks whether a cached reply exists for the given (clientId, requestId).
     *
     * If a cached reply exists (duplicate), logs "Duplicate request detected"
     * and "Re-sending stored reply for" and returns the bytes.
     *
     * If no cached reply (new request), logs "Processing NEW request" and
     * returns null — the caller is responsible for executing the operation
     * and calling storeReply() afterward.
     *
     * @param clientId   from first 4 bytes of the incoming request header
     * @param requestId  from bytes 4-7 of the incoming request header
     * @return           cached reply bytes if duplicate; null if new request
     */
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
     *
     * @param clientId   client identifier
     * @param requestId  request counter from this client
     * @param reply      the marshalled reply bytes to cache
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
     *
     * @return true  if this is a duplicate (cached reply exists)
     *         false if this is a new request
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

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Canonical key string: "clientId-requestId", e.g. "47832-3".
     * Must match RetryClientLogic.buildKey() on the client side.
     */
    public static String buildKey(int clientId, int requestId) {
        return clientId + "-" + requestId;
    }

    // -----------------------------------------------------------------------
    // Quick self-test
    // -----------------------------------------------------------------------
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
