import java.util.Random;

/**
 Purpose of RequestTracker:
 * Manages the (clientId, requestId) pair used to uniquely identify every
 * request this client sends.  The server uses the same key to implement
 * duplicate detection under at-most-once semantics.
 *
 * clientId   – a random integer generated once when the client starts.
 * requestId  – a counter that increments by 1 for every new request.
 *
 * Unique request key = clientId + "-" + requestId
 * e.g.  "47832-1"
 */
public class RequestTracker {

    /** Randomly assigned when the client starts.  Never changes. */
    private final int clientId;

    /** Increments with every new request sent by this client. */
    private int requestCounter;

    public RequestTracker() {
        this.clientId       = new Random().nextInt(100_000);   // 0 – 99999
        this.requestCounter = 0;
        System.out.println("[RequestTracker] Client started with clientId = " + clientId);
    }

    /**
     * Call this once before sending each new request.
     * Increments the internal counter and returns the next requestId.
     *
     * @return the requestId to embed in the outgoing request message.
     */
    public int nextRequestId() {
        requestCounter++;
        System.out.println("[RequestTracker] New requestId = " + requestCounter
                + "  (clientId = " + clientId + ")");
        return requestCounter;
    }

    /**
     * Returns the clientId assigned at startup.
     */
    public int getClientId() {
        return clientId;
    }

    /**
     * Returns the last requestId that was issued (without incrementing).
     * Useful when retrying the same request after a timeout.
     */
    public int getCurrentRequestId() {
        return requestCounter;
    }

    /**
     * Builds the canonical string key used by the server's history map.
     * @return     key string, e.g. "47832-3"
     */
    public static String buildKey(int cId, int rId) {
        return cId + "-" + rId;
    }

    /**
     * Convenience method: builds the key for the *current* (last-issued) request.
     */
    public String currentKey() {
        return buildKey(clientId, requestCounter);
    }

    // -----------------------------------------------------------------------
    // Quick self-test (run: javac RequestTracker.java && java RequestTracker)
    // -----------------------------------------------------------------------
    public static void main(String[] args) {
        RequestTracker tracker = new RequestTracker();

        int r1 = tracker.nextRequestId();
        System.out.println("Key for request 1: " + RequestTracker.buildKey(tracker.getClientId(), r1));

        int r2 = tracker.nextRequestId();
        System.out.println("Key for request 2: " + RequestTracker.buildKey(tracker.getClientId(), r2));

        System.out.println("Current key (handy helper): " + tracker.currentKey());
    }
}
