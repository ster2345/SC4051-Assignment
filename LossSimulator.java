import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Random;

/**
 * LossSimulator — Week 3
 *
 * Wraps DatagramSocket.send() to probabilistically drop outgoing packets,
 * simulating an unreliable UDP network.
 *
 * ─── Design ───────────────────────────────────────────────────────────────
 *
 * Two separate loss probabilities are used (as recommended in the spec):
 *
 *   requestLossProb  — used on the CLIENT side when sending a request.
 *                      Simulates the server never receiving the request.
 *
 *   replyLossProb   — used on the SERVER side when sending a reply.
 *                      Simulates the client never receiving the reply.
 *                      This is the key scenario that forces a retry and
 *                      exposes at-least-once vs at-most-once differences.
 *
 * To use on the client:
 *   LossSimulator sim = new LossSimulator(socket, 0.3, 0.0);
 *   sim.send(packet, "REQUEST");
 *
 * To use on the server:
 *   LossSimulator sim = new LossSimulator(socket, 0.0, 0.3);
 *   sim.send(packet, "REPLY");
 *
 * Setting both to 0.0 disables simulation (pass-through mode — useful for
 * Experiment 3, idempotent operations with no loss).
 *
 * ─── Experiment configuration guide ──────────────────────────────────────
 *
 *   Experiment 1 (at-least-once, non-idempotent, reply loss):
 *     server-side replyLossProb = 0.3
 *     semantics = at-least-once
 *     expected result: wrong balance (transfer applied multiple times)
 *
 *   Experiment 2 (at-most-once, non-idempotent, reply loss):
 *     server-side replyLossProb = 0.3
 *     semantics = at-most-once  (RequestHistoryManager enabled)
 *     expected result: correct balance (transfer applied exactly once)
 *
 *   Experiment 3 (idempotent operation, either semantic):
 *     operation = checkBalance
 *     either semantic is correct — state does not change on re-execution
 */
public class LossSimulator {

    // ─── Fields ──────────────────────────────────────────────────────────────

    private final DatagramSocket socket;
    private       double         requestLossProb;
    private       double         replyLossProb;
    private final Random         rng = new Random();

    // Statistics
    private int totalAttempts;
    private int totalDropped;

    // ─── Constructor ─────────────────────────────────────────────────────────

    /**
     * @param socket          The underlying UDP socket (may be null in unit tests).
     * @param requestLossProb Probability [0.0-1.0] of dropping REQUEST packets.
     * @param replyLossProb   Probability [0.0-1.0] of dropping REPLY packets.
     */
    public LossSimulator(DatagramSocket socket, double requestLossProb, double replyLossProb) {
        this.socket          = socket;
        this.requestLossProb = clamp(requestLossProb);
        this.replyLossProb   = clamp(replyLossProb);
        System.out.println("[LossSimulator] Initialised."
                + "  requestLoss=" + this.requestLossProb
                + "  replyLoss="   + this.replyLossProb);
    }

    /** Convenience — no loss (pass-through mode). */
    public LossSimulator(DatagramSocket socket) {
        this(socket, 0.0, 0.0);
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Attempts to send a packet, possibly dropping it based on the
     * appropriate probability for its direction.
     *
     * @param packet  The datagram to send.
     * @param label   "REQUEST" or "REPLY" or "CALLBACK" — determines which
     *                loss probability to apply.
     * @throws IOException if the underlying socket.send() throws.
     */
    public void send(DatagramPacket packet, String label) throws IOException {
        totalAttempts++;
        double prob = label.equalsIgnoreCase("REQUEST") ? requestLossProb : replyLossProb;

        if (prob > 0.0 && rng.nextDouble() < prob) {
            totalDropped++;
            System.out.println("[LossSimulator] DROPPED " + label + " packet #" + totalAttempts
                    + "  (prob=" + prob
                    + ", totalDropped=" + totalDropped + ")");
            // Silently discard — do not call socket.send()
            return;
        }

        socket.send(packet);
        System.out.println("[LossSimulator] SENT " + label + " packet #" + totalAttempts);
    }

    /**
     * Overload with default label "PACKET".
     */
    public void send(DatagramPacket packet) throws IOException {
        send(packet, "PACKET");
    }

    // ─── Configuration ───────────────────────────────────────────────────────

    public void setRequestLossProb(double p) {
        this.requestLossProb = clamp(p);
        System.out.println("[LossSimulator] requestLossProb updated to " + requestLossProb);
    }

    public void setReplyLossProb(double p) {
        this.replyLossProb = clamp(p);
        System.out.println("[LossSimulator] replyLossProb updated to " + replyLossProb);
    }

    public double getRequestLossProb() { return requestLossProb; }
    public double getReplyLossProb()   { return replyLossProb; }

    // ─── Statistics ──────────────────────────────────────────────────────────

    public int getTotalAttempts() { return totalAttempts; }
    public int getTotalDropped()  { return totalDropped; }

    /**
     * Prints a summary for the experiment report.
     */
    public void printStats() {
        System.out.println("=== LossSimulator Stats ===");
        System.out.println("  Total send() calls : " + totalAttempts);
        System.out.println("  Packets dropped    : " + totalDropped);
        System.out.println("  Packets sent       : " + (totalAttempts - totalDropped));
        System.out.printf ("  Effective drop rate: %.1f%%%n",
                totalAttempts > 0 ? 100.0 * totalDropped / totalAttempts : 0.0);
        System.out.println("===========================");
    }

    /** Resets counters between experiment runs. */
    public void resetStats() {
        totalAttempts = 0;
        totalDropped  = 0;
        System.out.println("[LossSimulator] Stats reset.");
    }

    // ─── Internal ────────────────────────────────────────────────────────────

    private static double clamp(double p) {
        return Math.max(0.0, Math.min(1.0, p));
    }

    // ─── Quick self-test ─────────────────────────────────────────────────────

    /**
     * Runs 20 simulated sends at 50% request loss and 30% reply loss,
     * then prints stats to verify probabilities are applied correctly.
     */
    public static void main(String[] args) throws Exception {
        // Use a loopback socket for testing
        java.net.DatagramSocket sock = new java.net.DatagramSocket();
        LossSimulator sim = new LossSimulator(sock, 0.5, 0.3);

        byte[] data = "test".getBytes();
        java.net.DatagramPacket pkt = new java.net.DatagramPacket(
                data, data.length,
                java.net.InetAddress.getByName("127.0.0.1"), 9998);

        System.out.println("--- Simulating 10 REQUEST sends (50% loss expected) ---");
        for (int i = 0; i < 10; i++) {
            try { sim.send(pkt, "REQUEST"); } catch (IOException ignored) {}
        }

        System.out.println("--- Simulating 10 REPLY sends (30% loss expected) ---");
        for (int i = 0; i < 10; i++) {
            try { sim.send(pkt, "REPLY"); } catch (IOException ignored) {}
        }

        sim.printStats();
        sock.close();
    }
}
