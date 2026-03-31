import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Random;
import java.util.Scanner;

public class Client {

    private static final int DEFAULT_SERVER_PORT = 2222;
    private static final int BUFFER_SIZE         = 8192;
    private static final int TIMEOUT_MS          = 2000;
    private static final int MAX_RETRIES         = 3;

    private final DatagramSocket              socket;
    private final InetAddress                serverAddress;
    private final int                        serverPort;
    private final LossSimulator              requestLossSimulator;
    private final BankProtocol.InvocationSemantics semantics;

    private final int clientId;
    private int requestCounter = 0;

    public Client(String serverHost, int serverPort,
                  BankProtocol.InvocationSemantics semantics,
                  double requestLossProb) throws Exception {
        this.serverAddress        = InetAddress.getByName(serverHost);
        this.serverPort           = serverPort;
        this.semantics            = semantics;
        this.socket               = new DatagramSocket();
        this.requestLossSimulator = new LossSimulator(this.socket, requestLossProb, 0.0);
        this.clientId             = new Random().nextInt(100_000);

        System.out.println("[Client] Started. localPort=" + socket.getLocalPort());
        System.out.println("[Client] server=" + serverHost + ":" + serverPort);
        System.out.println("[Client] semantics=" + semantics + "  clientId=" + clientId);
        System.out.println("[Client] requestLossProb=" + requestLossProb);
    }

    public static void main(String[] args) throws Exception {
        String serverHost   = (args.length >= 1) ? args[0] : "127.0.0.1";
        int    serverPort   = (args.length >= 2) ? Integer.parseInt(args[1]) : DEFAULT_SERVER_PORT;
        BankProtocol.InvocationSemantics semantics = (args.length >= 3)
                ? BankProtocol.InvocationSemantics.fromString(args[2])
                : BankProtocol.InvocationSemantics.AT_MOST_ONCE;
        double requestLossProb = (args.length >= 4) ? Double.parseDouble(args[3]) : 0.0;

        Client client = new Client(serverHost, serverPort, semantics, requestLossProb);
        client.runMenu();
    }

    private void runMenu() {
        try (Scanner scanner = new Scanner(System.in)) {
            boolean running = true;
            while (running) {
                printMenu();
                int choice = readInt(scanner, "Choose option: ");
                try {
                    switch (choice) {
                        case 1: handleOpenAccount(scanner);    break;
                        case 2: handleCloseAccount(scanner);   break;
                        case 3: handleDeposit(scanner);        break;
                        case 4: handleWithdraw(scanner);       break;
                        case 5: handleRegisterMonitor(scanner);break;
                        case 6: handleCheckBalance(scanner);   break;
                        case 7: handleTransferMoney(scanner);  break;
                        case 8: running = false; System.out.println("[Client] Exiting."); break;
                        default: System.out.println("Invalid choice. Please try again.");
                    }
                } catch (Exception ex) {
                    System.out.println("Request failed: " + ex.getMessage());
                }
            }
        } finally {
            socket.close();
        }
    }

    private void handleOpenAccount(Scanner scanner) throws Exception {
        String   name           = readLine(scanner,  "Name: ");
        String   password       = readLine(scanner,  "Password: ");
        Currency currency       = readCurrency(scanner);
        float    initialBalance = readFloat(scanner, "Initial balance: ");

        byte[] payload = BankProtocol.marshalOpenAccountRequest(name, password, currency, initialBalance);
        printReply(invokeWithRetry(payload));
    }

    private void handleCloseAccount(Scanner scanner) throws Exception {
        String name      = readLine(scanner, "Name: ");
        int    accountNo = readInt(scanner,  "Account number: ");
        String password  = readLine(scanner, "Password: ");

        byte[] payload = BankProtocol.marshalCloseAccountRequest(name, accountNo, password);
        printReply(invokeWithRetry(payload));
    }

    private void handleDeposit(Scanner scanner) throws Exception {
        String   name      = readLine(scanner,  "Name: ");
        int      accountNo = readInt(scanner,   "Account number: ");
        String   password  = readLine(scanner,  "Password: ");
        Currency currency  = readCurrency(scanner);
        float    amount    = readFloat(scanner, "Deposit amount: ");

        byte[] payload = BankProtocol.marshalDepositRequest(name, accountNo, password, currency, amount);
        printReply(invokeWithRetry(payload));
    }

    private void handleWithdraw(Scanner scanner) throws Exception {
        String   name      = readLine(scanner,  "Name: ");
        int      accountNo = readInt(scanner,   "Account number: ");
        String   password  = readLine(scanner,  "Password: ");
        Currency currency  = readCurrency(scanner);
        float    amount    = readFloat(scanner, "Withdraw amount: ");

        byte[] payload = BankProtocol.marshalWithdrawRequest(name, accountNo, password, currency, amount);
        printReply(invokeWithRetry(payload));
    }

    private void handleRegisterMonitor(Scanner scanner) throws Exception {
        int intervalSeconds = readInt(scanner, "Monitor interval (seconds): ");

        byte[] payload = BankProtocol.marshalRegisterMonitorRequest(intervalSeconds);
        BankProtocol.Reply reply = invokeWithRetry(payload);
        printReply(reply);

        if (reply != null && reply.success) {
            MonitorManager.receiveCallbacks(socket, intervalSeconds);
        }
    }

    private void handleCheckBalance(Scanner scanner) throws Exception {
        String name      = readLine(scanner, "Name: ");
        int    accountNo = readInt(scanner,  "Account number: ");
        String password  = readLine(scanner, "Password: ");

        byte[] payload = BankProtocol.marshalCheckBalanceRequest(name, accountNo, password);
        printReply(invokeWithRetry(payload));
    }

    private void handleTransferMoney(Scanner scanner) throws Exception {
        String name               = readLine(scanner,  "Sender name: ");
        int    senderAccountNo    = readInt(scanner,   "Sender account number: ");
        String password           = readLine(scanner,  "Sender password: ");
        int    recipientAccountNo = readInt(scanner,   "Recipient account number: ");
        float  amount             = readFloat(scanner, "Transfer amount: ");

        byte[] payload = BankProtocol.marshalTransferMoneyRequest(
                name, senderAccountNo, password, recipientAccountNo, amount);
        printReply(invokeWithRetry(payload));
    }


    //  sends one logical request with retry
    //  requestId is generated once and never changed on retries

    private BankProtocol.Reply invokeWithRetry(byte[] requestPayload) throws Exception {
        int    requestId   = nextRequestId();
        String key         = RetryClientLogic.buildKey(clientId, requestId);
        byte[] fullRequest = RetryClientLogic.wrapWithHeader(clientId, requestId, requestPayload);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            System.out.println("[Client] Sending attempt " + attempt + "/" + MAX_RETRIES + " key=" + key);

            DatagramPacket sendPacket = new DatagramPacket(
                    fullRequest, fullRequest.length, serverAddress, serverPort);
            requestLossSimulator.send(sendPacket, "REQUEST");

            long deadline = System.currentTimeMillis() + TIMEOUT_MS;

            while (true) {
                int remaining = (int)(deadline - System.currentTimeMillis());
                if (remaining <= 0) break;

                socket.setSoTimeout(remaining);
                byte[]         recvBuffer  = new byte[BUFFER_SIZE];
                DatagramPacket replyPacket = new DatagramPacket(recvBuffer, recvBuffer.length);

                try {
                    socket.receive(replyPacket);
                } catch (SocketTimeoutException ex) {
                    break;
                }

                int    len  = replyPacket.getLength();
                byte[] data = replyPacket.getData();

                String callback = MonitorManager.parseUpdateMessage(data, len);
                if (callback != null) {
                    System.out.println("[CALLBACK] " + callback);
                    continue;
                }

                if (len < 8) {
                    System.out.println("[Client] Ignored malformed packet (len=" + len + ")");
                    continue;
                }

                int replyClientId  = RetryClientLogic.readInt(data, 0);
                int replyRequestId = RetryClientLogic.readInt(data, 4);

                if (replyClientId != clientId || replyRequestId != requestId) {
                    System.out.println("[Client] Ignored unrelated reply key="
                            + RetryClientLogic.buildKey(replyClientId, replyRequestId));
                    continue;
                }

                byte[] replyPayload = RetryClientLogic.stripHeader(data, len);
                return BankProtocol.unmarshalReply(replyPayload, replyPayload.length);
            }

            System.out.println("[Client] Timeout for key=" + key + ". Retrying...");
        }

        System.out.println("[Client] Failed after max retries.");
        return null;
    }

    private int nextRequestId() { return ++requestCounter; }

    private static void printMenu() {
        System.out.println();
        System.out.println("========== Banking Client ==========");
        System.out.println("1) Open account");
        System.out.println("2) Close account");
        System.out.println("3) Deposit");
        System.out.println("4) Withdraw");
        System.out.println("5) Register monitor (callback)");
        System.out.println("6) Check balance (idempotent)");
        System.out.println("7) Transfer money (non-idempotent)");
        System.out.println("8) Exit");
        System.out.println("====================================");
    }

    private static void printReply(BankProtocol.Reply reply) {
        if (reply == null) {
            System.out.println("No reply received (all retries exhausted).");
            return;
        }
        System.out.println((reply.success ? "SUCCESS: " : "ERROR: ") + reply.message);
    }

    private static Currency readCurrency(Scanner scanner) {
        while (true) {
            System.out.print("Currency (" + Currency.menuOptions() + "): ");
            String raw = scanner.nextLine().trim();
            try {
                return Currency.fromString(raw);
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    private static String readLine(Scanner scanner, String prompt) {
        System.out.print(prompt);
        return scanner.nextLine().trim();
    }

    private static int readInt(Scanner scanner, String prompt) {
        while (true) {
            System.out.print(prompt);
            try { return Integer.parseInt(scanner.nextLine().trim()); }
            catch (NumberFormatException ex) { System.out.println("Please enter a valid integer."); }
        }
    }

    private static float readFloat(Scanner scanner, String prompt) {
        while (true) {
            System.out.print(prompt);
            try { return Float.parseFloat(scanner.nextLine().trim()); }
            catch (NumberFormatException ex) { System.out.println("Please enter a valid number."); }
        }
    }
}

