import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MonitorManager {

    // Inner class: holds registration info for one monitoring client.
    private static class MonitorEntry {
        final InetAddress address;
        final int         port;
        final long        expiryTimeMs;

        MonitorEntry(InetAddress address, int port, long expiryTimeMs) {
            this.address      = address;
            this.port         = port;
            this.expiryTimeMs = expiryTimeMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiryTimeMs;
        }

        long remainingSeconds() {
            return Math.max(0, (expiryTimeMs - System.currentTimeMillis()) / 1000);
        }

        @Override
        public String toString() {
            return address.getHostAddress() + ":" + port
                    + " (expires in ~" + remainingSeconds() + "s)";
        }
    }

    // Constants 
    /** Operation code placed in byte[0] of every callback message. */
    public static final byte OP_ACCOUNT_UPDATE = 0x0A;

    // Fields 
    private final DatagramSocket     socket;
    private final LossSimulator      lossSimulator;
    private final List<MonitorEntry> entries = new ArrayList<>();

    // Constructor 
    public MonitorManager(DatagramSocket socket, LossSimulator lossSimulator) {
        this.socket        = socket;
        this.lossSimulator = lossSimulator;
        System.out.println("[MonitorManager] Initialised.");
    }

    // Public API
    public void registerClient(InetAddress address, int port, int intervalSeconds) {
        long expiry = System.currentTimeMillis() + (long) intervalSeconds * 1000L;
        MonitorEntry entry = new MonitorEntry(address, port, expiry);
        entries.add(entry);
        System.out.println("[MonitorManager] Registered monitor client: " + entry
                + "  | Total registered: " + entries.size());
    }

    public void notifyMonitors(String updateDescription) {
        purgeExpired();

        if (entries.isEmpty()) {
            // No active monitors — skip silently
            return;
        }

        byte[] updateBytes = buildUpdateMessage(updateDescription);
        System.out.println("[MonitorManager] Notifying " + entries.size()
                + " monitor client(s): " + updateDescription);

        Iterator<MonitorEntry> it = entries.iterator();
        while (it.hasNext()) {
            MonitorEntry entry = it.next();
            if (entry.isExpired()) {
                System.out.println("[MonitorManager] Skipping expired client: " + entry);
                it.remove();
                continue;
            }
            try {
                DatagramPacket pkt = new DatagramPacket(
                        updateBytes, updateBytes.length, entry.address, entry.port);
                lossSimulator.send(pkt, "CALLBACK");
                System.out.println("[MonitorManager] Update sent to " + entry);
            } catch (IOException e) {
                System.err.println("[MonitorManager] Failed to notify " + entry
                        + ": " + e.getMessage());
            }
        }
    }

    /**
     * Convenience overload — accepts pre-built bytes directly.
     * Useful when the server already has a marshalled update message.
     */
    public void notifyMonitors(byte[] updateBytes) {
        purgeExpired();
        if (entries.isEmpty()) return;

        System.out.println("[MonitorManager] Notifying " + entries.size()
                + " client(s) with raw update bytes (len=" + updateBytes.length + ")");

        for (MonitorEntry entry : entries) {
            try {
                DatagramPacket pkt = new DatagramPacket(
                        updateBytes, updateBytes.length, entry.address, entry.port);
                lossSimulator.send(pkt, "CALLBACK");
            } catch (IOException e) {
                System.err.println("[MonitorManager] Send failed to " + entry
                        + ": " + e.getMessage());
            }
        }
    }

    public void purgeExpired() {
        int before = entries.size();
        entries.removeIf(MonitorEntry::isExpired);
        int removed = before - entries.size();
        if (removed > 0) {
            System.out.println("[MonitorManager] Purged " + removed
                    + " expired monitor(s).  Active: " + entries.size());
        }
    }

    /**
     * Returns the count of currently active (non-expired) registrations.
     */
    public int activeCount() {
        purgeExpired();
        return entries.size();
    }

    // Message helpers
    public static byte[] buildUpdateMessage(String updateDescription) {
        byte[] text = updateDescription.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] msg  = new byte[1 + text.length];
        msg[0]      = OP_ACCOUNT_UPDATE;
        System.arraycopy(text, 0, msg, 1, text.length);
        return msg;
    }

    /**
     * Parses a received callback packet back into its description string.
     * Used on the CLIENT side when a monitoring packet arrives.
     */
    public static String parseUpdateMessage(byte[] data, int length) {
        if (length < 2 || data[0] != OP_ACCOUNT_UPDATE) return null;
        return new String(data, 1, length - 1, java.nio.charset.StandardCharsets.UTF_8);
    }

    // Client-side helper: receive callbacks during monitoring window
    public static void receiveCallbacks(DatagramSocket clientSocket, int intervalSeconds) {
        long endTime = System.currentTimeMillis() + (long) intervalSeconds * 1000L;
        System.out.println("[MonitorManager-Client] Monitoring for "
                + intervalSeconds + "s. Waiting for callbacks…");
        try {
            clientSocket.setSoTimeout(1000);   // poll so we can check time
            byte[] buf = new byte[4096];

            while (System.currentTimeMillis() < endTime) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                try {
                    clientSocket.receive(pkt);
                    String update = parseUpdateMessage(pkt.getData(), pkt.getLength());
                    if (update != null) {
                        System.out.println("[CALLBACK] Account update received: " + update);
                    }
                } catch (SocketTimeoutException ignored) {
                    // Normal — loop again to re-check time
                }
            }
        } catch (IOException e) {
            System.err.println("[MonitorManager-Client] Error: " + e.getMessage());
        }
        System.out.println("[MonitorManager-Client] Monitoring interval expired.");
    }

    // Quick self-test
    public static void main(String[] args) throws Exception {
        DatagramSocket testSocket = new DatagramSocket(2224);
        LossSimulator  noLoss    = new LossSimulator(testSocket, 0.0, 0.0);
        MonitorManager mgr       = new MonitorManager(testSocket, noLoss);

        // Register two clients with different intervals
        mgr.registerClient(InetAddress.getByName("127.0.0.1"), 9001, 5);
        mgr.registerClient(InetAddress.getByName("127.0.0.1"), 9002, 2);
        System.out.println("Active: " + mgr.activeCount());   // 2

        // Simulate account update
        mgr.notifyMonitors("DEPOSIT|acc=1001|amount=200.00|newBalance=700.00");

        // Wait for second client to expire
        Thread.sleep(3000);
        mgr.purgeExpired();
        System.out.println("Active after 3s: " + mgr.activeCount());  // 1

        testSocket.close();
    }
}
