import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * CallbackMonitor — Member C
 *
 * Server-side component that manages the monitoring/callback service described
 * in the lab manual (Section 5.2, Service 4).
 *
 * ─── How it works ────────────────────────────────────────────────────────────
 *
 *  1. A client sends a REGISTER_MONITOR request containing a monitor interval
 *     (in seconds).
 *
 *  2. The server calls registerClient() with the client's address, port, and
 *     the requested interval.  The record is stored in a list with an expiry
 *     timestamp = now + intervalSeconds.
 *
 *  3. Whenever any account update occurs (open / close / deposit / withdraw /
 *     transfer / check balance), the server calls notifyAll() with a descriptive
 *     update message.  This method:
 *       • Sends the message to every monitor client whose expiry has NOT passed.
 *       • Removes expired clients from the list.
 *
 *  4. The client blocks during the interval and collects incoming update packets.
 *     (No threads needed on the client side per the manual's simplification.)
 *
 *  5. Multiple clients can be registered concurrently (the list supports this).
 *
 * ─── Message format for callbacks ───────────────────────────────────────────
 *
 *  Callbacks are plain UTF-8 strings prepended with a 1-byte type tag:
 *    byte[0]  = 0x0A  (operation code: ACCOUNT_UPDATE)
 *    byte[1..] = UTF-8 update string, e.g.:
 *               "OPEN|acc=1001|name=Alice|currency=SGD|balance=500.00"
 *               "DEPOSIT|acc=1001|amount=200.00|newBalance=700.00"
 *               "WITHDRAW|acc=1001|amount=100.00|newBalance=600.00"
 *               "CLOSE|acc=1001|name=Alice"
 *               "TRANSFER|from=1001|to=1002|amount=50.00"
 *
 * Usage (inside server main loop — call notifyAll after every account update):
 *
 *   CallbackMonitor monitor = new CallbackMonitor(socket);
 *
 *   // When a REGISTER request arrives:
 *   monitor.registerClient(packet.getAddress(), packet.getPort(), intervalSecs);
 *
 *   // After any account operation:
 *   monitor.notifyAll("DEPOSIT|acc=1001|amount=200.00|newBalance=700.00");
 */
public class CallbackMonitor {

    // ─── Inner record ────────────────────────────────────────────────────────

    /**
     * Holds the registration info for one monitoring client.
     */
    private static class MonitorClient {
        InetAddress address;
        int         port;
        long        expiryTimeMs;   // System.currentTimeMillis() + interval * 1000

        MonitorClient(InetAddress address, int port, long expiryTimeMs) {
            this.address      = address;
            this.port         = port;
            this.expiryTimeMs = expiryTimeMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiryTimeMs;
        }

        @Override
        public String toString() {
            long remaining = (expiryTimeMs - System.currentTimeMillis()) / 1000;
            return address.getHostAddress() + ":" + port + " (expires in ~" + remaining + "s)";
        }
    }

    // ─── Fields ──────────────────────────────────────────────────────────────

    /** Operation code placed in byte[0] of every callback message. */
    public static final byte OP_ACCOUNT_UPDATE = 0x0A;

    private final DatagramSocket      socket;
    private final List<MonitorClient> monitors = new ArrayList<>();

    // ─── Constructor ─────────────────────────────────────────────────────────

    /**
     * @param socket  The server's existing DatagramSocket (shared for callbacks).
     */
    public CallbackMonitor(DatagramSocket socket) {
        this.socket = socket;
        System.out.println("[CallbackMonitor] Initialised.");
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Registers a new monitoring client.
     *
     * @param address         Client IP (from the received REGISTER packet).
     * @param port            Client port (from the received REGISTER packet).
     * @param intervalSeconds How long (seconds) the client wants to monitor.
     */
    public void registerClient(InetAddress address, int port, int intervalSeconds) {
        long expiry = System.currentTimeMillis() + (long) intervalSeconds * 1000;
        MonitorClient mc = new MonitorClient(address, port, expiry);
        monitors.add(mc);
        System.out.println("[CallbackMonitor] Registered client: " + mc
                + "  | Total active monitors: " + monitors.size());
    }

    /**
     * Sends an account-update callback to all non-expired monitoring clients,
     * then removes any expired clients from the list.
     *
     * Call this method from the server after every account-modifying operation.
     *
     * @param updateDescription  Human-readable update string (see class-level javadoc).
     */
    public void notifyAll(String updateDescription) {
        purgeExpired();   // clean up first so we only send to active clients

        if (monitors.isEmpty()) {
            // No active monitors — nothing to do.
            return;
        }

        byte[] updateBytes = buildUpdateMessage(updateDescription);
        System.out.println("[CallbackMonitor] Notifying " + monitors.size()
                + " client(s): " + updateDescription);

        Iterator<MonitorClient> it = monitors.iterator();
        while (it.hasNext()) {
            MonitorClient mc = it.next();
            if (mc.isExpired()) {
                System.out.println("[CallbackMonitor] Removing expired client: " + mc);
                it.remove();
                continue;
            }
            try {
                DatagramPacket pkt = new DatagramPacket(
                        updateBytes, updateBytes.length, mc.address, mc.port);
                socket.send(pkt);
                System.out.println("[CallbackMonitor] Sent update to " + mc);
            } catch (IOException e) {
                System.err.println("[CallbackMonitor] Failed to send to " + mc + ": " + e.getMessage());
            }
        }
    }

    /**
     * Returns the number of currently active (non-expired) monitor registrations.
     */
    public int activeCount() {
        purgeExpired();
        return monitors.size();
    }

    /**
     * Explicitly removes all expired monitor clients from the list.
     * Called automatically inside notifyAll() and activeCount().
     */
    public void purgeExpired() {
        int before = monitors.size();
        monitors.removeIf(MonitorClient::isExpired);
        int removed = before - monitors.size();
        if (removed > 0) {
            System.out.println("[CallbackMonitor] Purged " + removed
                    + " expired monitor(s).  Active: " + monitors.size());
        }
    }

    // ─── Message building ────────────────────────────────────────────────────

    /**
     * Constructs the raw bytes for a callback update message.
     *
     * Format:
     *   byte[0]   = OP_ACCOUNT_UPDATE (0x0A)
     *   byte[1..] = UTF-8 string of updateDescription
     */
    private static byte[] buildUpdateMessage(String updateDescription) {
        byte[] text  = updateDescription.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] msg   = new byte[1 + text.length];
        msg[0]       = OP_ACCOUNT_UPDATE;
        System.arraycopy(text, 0, msg, 1, text.length);
        return msg;
    }

    /**
     * Parses a received callback message back into its description string.
     * Clients call this when they receive a UDP packet during monitoring.
     *
     * @param data    raw packet bytes
     * @param length  number of valid bytes
     * @return        the update description string, or null if not a valid update
     */
    public static String parseUpdateMessage(byte[] data, int length) {
        if (length < 2 || data[0] != OP_ACCOUNT_UPDATE) return null;
        return new String(data, 1, length - 1, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ─── Client-side helper: block and receive callbacks ─────────────────────

    /**
     * Called on the CLIENT side after sending a REGISTER_MONITOR request.
     * Blocks and prints all incoming update messages until the interval expires.
     *
     * @param clientSocket    the client's DatagramSocket
     * @param intervalSeconds how long to wait (same value sent to server)
     */
    public static void receiveCallbacks(DatagramSocket clientSocket, int intervalSeconds) {
        long endTime = System.currentTimeMillis() + (long) intervalSeconds * 1000;
        System.out.println("[Client] Monitoring for " + intervalSeconds
                + " seconds. Waiting for callbacks…");
        try {
            clientSocket.setSoTimeout(1000);  // 1-second poll so we can check expiry
            byte[] buf = new byte[4096];

            while (System.currentTimeMillis() < endTime) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                try {
                    clientSocket.receive(pkt);
                    String update = parseUpdateMessage(pkt.getData(), pkt.getLength());
                    if (update != null) {
                        System.out.println("[CALLBACK] Account update: " + update);
                    }
                } catch (SocketTimeoutException e) {
                    // Normal — just loop again to check time
                }
            }
        } catch (IOException e) {
            System.err.println("[Client] Error receiving callbacks: " + e.getMessage());
        }
        System.out.println("[Client] Monitoring interval expired.");
    }

    // ─── Quick self-test ─────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        // Simulate a server socket
        DatagramSocket fakeSocket = new DatagramSocket(2223);
        CallbackMonitor cm = new CallbackMonitor(fakeSocket);

        // Register two clients with short intervals
        cm.registerClient(InetAddress.getByName("127.0.0.1"), 9001, 5);
        cm.registerClient(InetAddress.getByName("127.0.0.1"), 9002, 2);

        System.out.println("Active monitors: " + cm.activeCount());  // 2

        // Simulate an account update
        cm.notifyAll("DEPOSIT|acc=1001|amount=200.00|newBalance=700.00");

        // Wait for one client to expire
        Thread.sleep(3000);
        cm.purgeExpired();
        System.out.println("Active monitors after 3s: " + cm.activeCount());  // 1

        fakeSocket.close();
    }
}
