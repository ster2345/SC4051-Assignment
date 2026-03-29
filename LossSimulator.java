import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Random;

public class LossSimulator {

    private final DatagramSocket socket;
    private       double         requestLossProb;
    private       double         replyLossProb;
    private final Random         rng = new Random();

    // Statistics
    private int totalAttempts;
    private int totalDropped;

    // Constructor
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

    // Public API
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

    // Configuration 

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

    // Statistics 
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

    // Internal 

    private static double clamp(double p) {
        return Math.max(0.0, Math.min(1.0, p));
    }

    // Quick self-test 

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
