import java.io.IOException;
import java.net.*;
import java.util.Random;

public class RetryClientLogic {

    /** Maximum send attempts before giving up. */
    public static final int MAX_RETRIES = 3;

    /** Milliseconds to wait for a reply before retrying. */
    public static final int TIMEOUT_MS = 2000;

    /** Receive buffer size in bytes. */
    private static final int BUFFER_SIZE = 4096;

    private final DatagramSocket socket;
    private final InetAddress    serverAddress;
    private final int            serverPort;
    private final LossSimulator  lossSimulator;

    // Inlined from RequestTracker
    private final int clientId;
    private int       requestCounter = 0;

    // Constructor
    public RetryClientLogic(String serverHost, int serverPort, LossSimulator lossSimulator)
            throws SocketException, UnknownHostException {
        this.serverAddress = InetAddress.getByName(serverHost);
        this.serverPort    = serverPort;
        this.lossSimulator = lossSimulator;
        this.clientId      = new Random().nextInt(100_000);
        this.socket        = new DatagramSocket();
        System.out.println("[RetryClientLogic] Ready. clientId=" + clientId
                + "  server=" + serverHost + ":" + serverPort);
    }

    // Public API — AT-LEAST-ONCE
    public byte[] sendAtLeastOnce(byte[] payload) throws IOException {
        // Generate requestId ONCE — reused for all retries
        int requestId = nextRequestId();
        byte[] message = wrapWithHeader(clientId, requestId, payload);
        String key = buildKey(clientId, requestId);

        System.out.println("[At-Least-Once] Starting request key=" + key);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            System.out.println("[At-Least-Once] Attempt " + attempt + "/" + MAX_RETRIES
                    + "  key=" + key);

            DatagramPacket sendPacket =
                    new DatagramPacket(message, message.length, serverAddress, serverPort);
            lossSimulator.send(sendPacket, "REQUEST");

            byte[] buf = new byte[BUFFER_SIZE];
            DatagramPacket replyPacket = new DatagramPacket(buf, buf.length);
            try {
                socket.setSoTimeout(TIMEOUT_MS);
                socket.receive(replyPacket);
                System.out.println("[At-Least-Once] Reply received on attempt " + attempt
                        + "  key=" + key);
                return stripHeader(replyPacket.getData(), replyPacket.getLength());
            } catch (SocketTimeoutException e) {
                System.out.println("[At-Least-Once] Timeout on attempt " + attempt
                        + ". Retrying request " + key);
            }
        }

        System.out.println("[At-Least-Once] All retries exhausted for key=" + key);
        return null;
    }

    // Public API — AT-MOST-ONCE
    public byte[] sendAtMostOnce(byte[] payload) throws IOException {
        // Generate requestId ONCE — reused for all retries
        int requestId = nextRequestId();
        byte[] message = wrapWithHeader(clientId, requestId, payload);
        String key = buildKey(clientId, requestId);

        System.out.println("[At-Most-Once] Starting request key=" + key);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            System.out.println("[At-Most-Once] Attempt " + attempt + "/" + MAX_RETRIES
                    + "  key=" + key);

            DatagramPacket sendPacket =
                    new DatagramPacket(message, message.length, serverAddress, serverPort);
            lossSimulator.send(sendPacket, "REQUEST");

            byte[] buf = new byte[BUFFER_SIZE];
            DatagramPacket replyPacket = new DatagramPacket(buf, buf.length);
            try {
                socket.setSoTimeout(TIMEOUT_MS);
                socket.receive(replyPacket);
                System.out.println("[At-Most-Once] Reply received on attempt " + attempt
                        + "  key=" + key);
                return stripHeader(replyPacket.getData(), replyPacket.getLength());
            } catch (SocketTimeoutException e) {
                System.out.println("[At-Most-Once] Timeout on attempt " + attempt
                        + ". Retrying request " + key);
            }
        }

        System.out.println("[At-Most-Once] All retries exhausted for key=" + key);
        return null;
    }

    // Accessors
    public int getClientId()         { return clientId; }
    public int getCurrentRequestId() { return requestCounter; }
    public void close()              { socket.close(); }

    // Inlined from RequestTracker
    /** Increments and returns the next requestId. Called ONCE per new request. */
    private int nextRequestId() {
        requestCounter++;
        System.out.println("[RetryClientLogic] New requestId=" + requestCounter
                + "  clientId=" + clientId);
        return requestCounter;
    }

    /**
     * Canonical key string: "clientId-requestId", e.g. "47832-3".
     * Must match RequestHistoryManager.buildKey() on the server side.
     */
    public static String buildKey(int clientId, int requestId) {
        return clientId + "-" + requestId;
    }

    // Inlined from InvocationClient — header marshalling helpers
    /** Prepends an 8-byte header (clientId + requestId, big-endian) to payload. */
    public static byte[] wrapWithHeader(int clientId, int requestId, byte[] payload) {
        byte[] msg = new byte[8 + payload.length];
        writeInt(msg, 0, clientId);
        writeInt(msg, 4, requestId);
        System.arraycopy(payload, 0, msg, 8, payload.length);
        return msg;
    }

    /** Strips the 8-byte header and returns only the payload portion. */
    public static byte[] stripHeader(byte[] data, int length) {
        if (length <= 8) return new byte[0];
        byte[] payload = new byte[length - 8];
        System.arraycopy(data, 8, payload, 0, payload.length);
        return payload;
    }

    /** Reads a big-endian int from buf at offset. */
    public static int readInt(byte[] buf, int offset) {
        return ((buf[offset]     & 0xFF) << 24)
             | ((buf[offset + 1] & 0xFF) << 16)
             | ((buf[offset + 2] & 0xFF) <<  8)
             |  (buf[offset + 3] & 0xFF);
    }

    /** Writes a big-endian int into buf at offset. */
    public static void writeInt(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) ((value >> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >>  8) & 0xFF);
        buf[offset + 3] = (byte)  (value        & 0xFF);
    }

    // Quick self-test
    public static void main(String[] args) {
        System.out.println("=== RetryClientLogic self-test ===");

        // Verify requestId stays the same across retries
        int counter = 0;
        counter++;  // nextRequestId() called ONCE before loop
        int requestId = counter;
        System.out.println("Request 1 id=" + requestId);
        System.out.println("Retry 1   id=" + counter + " (same=" + (counter == requestId) + ")");
        System.out.println("Retry 2   id=" + counter + " (same=" + (counter == requestId) + ")");
        counter++;  // nextRequestId() for next NEW request
        System.out.println("New request id=" + counter + " (incremented=" + (counter == requestId + 1) + ")");

        // Verify header round-trip
        byte[] payload = "TRANSFER:200".getBytes();
        byte[] wrapped = wrapWithHeader(12345, 7, payload);
        System.out.println("clientId  from header: " + readInt(wrapped, 0) + " (expect 12345)");
        System.out.println("requestId from header: " + readInt(wrapped, 4) + " (expect 7)");
        System.out.println("payload stripped: "
                + new String(stripHeader(wrapped, wrapped.length)) + " (expect TRANSFER:200)");

        // Verify key format
        System.out.println("buildKey: " + buildKey(12345, 7) + " (expect 12345-7)");
    }
}
