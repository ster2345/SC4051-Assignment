import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * Server
 *
 * UDP banking server entry point.
 *
 * Responsibilities:
 * 1) Receive request packets from clients.
 * 2) Decode the 8-byte retry header (clientId + requestId).
 * 3) Apply invocation semantics:
 *      - AT-LEAST-ONCE: process every request packet.
 *      - AT-MOST-ONCE : detect duplicates and resend cached reply.
 * 4) Unmarshal operation arguments from request payload bytes.
 * 5) Invoke AccountStore service methods.
 * 6) Marshal reply bytes and send them back over UDP.
 * 7) Notify monitor clients on successful account updates.
 *
 * Usage:
 *   java Server <port> <at-least-once|at-most-once> [replyLossProb]
 *
 * Example:
 *   java Server 2222 at-most-once 0.3
 */
public class Server {

	private static final int DEFAULT_PORT = 2222;
	private static final double DEFAULT_REPLY_LOSS_PROB = 0.0;
	private static final int BUFFER_SIZE = 8192;

	/**
	 * Small structured result used internally to keep request-processing code clear.
	 */
	private static class ProcessResult {
		final boolean success;
		final String message;
		final String monitorUpdate;

		ProcessResult(boolean success, String message, String monitorUpdate) {
			this.success = success;
			this.message = message;
			this.monitorUpdate = monitorUpdate;
		}
	}

	public static void main(String[] args) throws Exception {
		int port = (args.length >= 1) ? Integer.parseInt(args[0]) : DEFAULT_PORT;
		BankProtocol.InvocationSemantics semantics = (args.length >= 2)
				? BankProtocol.InvocationSemantics.fromString(args[1])
				: BankProtocol.InvocationSemantics.AT_MOST_ONCE;
		double replyLossProb = (args.length >= 3)
				? Double.parseDouble(args[2])
				: DEFAULT_REPLY_LOSS_PROB;

		DatagramSocket socket = new DatagramSocket(port);

		// Server-side loss simulator: request loss should be 0 here;
		// only reply/callback loss is simulated on server sends.
		LossSimulator serverLoss = new LossSimulator(socket, 0.0, replyLossProb);

		AccountStore accountStore = new AccountStore();
		RequestHistoryManager historyManager = new RequestHistoryManager();
		MonitorManager monitorManager = new MonitorManager(socket, serverLoss);

		System.out.println("[Server] Started on UDP port " + port);
		System.out.println("[Server] Semantics = " + semantics);
		System.out.println("[Server] replyLossProb = " + replyLossProb);

		// Main server loop: single-threaded as allowed by assignment assumptions.
		while (true) {
			byte[] recvBuffer = new byte[BUFFER_SIZE];
			DatagramPacket requestPacket = new DatagramPacket(recvBuffer, recvBuffer.length);
			socket.receive(requestPacket);

			int packetLength = requestPacket.getLength();
			byte[] packetData = requestPacket.getData();

			if (packetLength < 8) {
				// Cannot parse clientId/requestId header, so we cannot form a proper
				// correlated reply. We log and drop this malformed packet.
				System.err.println("[Server] Dropped malformed packet (<8 bytes). len=" + packetLength);
				continue;
			}

			int clientId = RetryClientLogic.readInt(packetData, 0);
			int requestId = RetryClientLogic.readInt(packetData, 4);
			String requestKey = RetryClientLogic.buildKey(clientId, requestId);

			System.out.println("\n[Server] Received request key=" + requestKey
					+ " from " + requestPacket.getAddress().getHostAddress()
					+ ":" + requestPacket.getPort());

			// AT-MOST-ONCE: if duplicate exists, resend cached full reply packet bytes.
			if (semantics == BankProtocol.InvocationSemantics.AT_MOST_ONCE) {
				byte[] cachedFullReply = historyManager.getCachedReply(clientId, requestId);
				if (cachedFullReply != null) {
					DatagramPacket replyPacket = new DatagramPacket(
							cachedFullReply,
							cachedFullReply.length,
							requestPacket.getAddress(),
							requestPacket.getPort());
					serverLoss.send(replyPacket, "REPLY");
					continue;
				}
			}

			byte[] requestPayload = RetryClientLogic.stripHeader(packetData, packetLength);
			ProcessResult result;

			try {
				result = processRequest(
						requestPayload,
						requestPayload.length,
						requestPacket,
						accountStore,
						monitorManager);
			} catch (Exception ex) {
				// Any parsing/processing failure is converted into a normal error reply,
				// so client still receives a meaningful response.
				String err = "ERROR: Bad request format or processing failure: " + ex.getMessage();
				result = new ProcessResult(false, err, null);
			}

			byte[] replyPayload = BankProtocol.marshalReply(result.success, result.message);
			byte[] fullReply = RetryClientLogic.wrapWithHeader(clientId, requestId, replyPayload);

			// Store the full reply bytes for duplicate re-send under AT-MOST-ONCE.
			if (semantics == BankProtocol.InvocationSemantics.AT_MOST_ONCE) {
				historyManager.storeReply(clientId, requestId, fullReply);
			}

			DatagramPacket replyPacket = new DatagramPacket(
					fullReply,
					fullReply.length,
					requestPacket.getAddress(),
					requestPacket.getPort());

			serverLoss.send(replyPacket, "REPLY");

			if (result.monitorUpdate != null) {
				monitorManager.notifyMonitors(result.monitorUpdate);
			}
		}
	}

	/**
	 * Decodes one operation and dispatches it to AccountStore / MonitorManager.
	 *
	 * Returns both user-facing message and optional monitor callback text.
	 */
	private static ProcessResult processRequest(
			byte[] payload,
			int payloadLength,
			DatagramPacket requestPacket,
			AccountStore accountStore,
			MonitorManager monitorManager) {

		BankProtocol.Request request = BankProtocol.unmarshalRequest(payload, payloadLength);
		String message;
		String update = null;

		switch (request.opCode) {
			case BankProtocol.OP_OPEN_ACCOUNT:
				message = accountStore.openAccount(
						request.name,
						request.password,
						request.currency,
						request.initialBalance);
				if (isSuccessMessage(message)) {
					update = "OPEN|name=" + request.name
							+ "|currency=" + request.currency
							+ "|initialBalance=" + request.initialBalance;
				}
				return new ProcessResult(isSuccessMessage(message), message, update);

			case BankProtocol.OP_CLOSE_ACCOUNT:
				message = accountStore.closeAccount(request.name, request.accountNo, request.password);
				if (isSuccessMessage(message)) {
					update = "CLOSE|acc=" + request.accountNo + "|name=" + request.name;
				}
				return new ProcessResult(isSuccessMessage(message), message, update);

			case BankProtocol.OP_DEPOSIT:
				message = accountStore.deposit(
						request.name,
						request.accountNo,
						request.password,
						request.currency,
						request.amount);
				if (isSuccessMessage(message)) {
					update = "DEPOSIT|acc=" + request.accountNo
							+ "|name=" + request.name
							+ "|amount=" + request.amount;
				}
				return new ProcessResult(isSuccessMessage(message), message, update);

			case BankProtocol.OP_WITHDRAW:
				message = accountStore.withdraw(
						request.name,
						request.accountNo,
						request.password,
						request.currency,
						request.amount);
				if (isSuccessMessage(message)) {
					update = "WITHDRAW|acc=" + request.accountNo
							+ "|name=" + request.name
							+ "|amount=" + request.amount;
				}
				return new ProcessResult(isSuccessMessage(message), message, update);

			case BankProtocol.OP_REGISTER_MONITOR:
				if (request.monitorIntervalSeconds <= 0) {
					return new ProcessResult(false,
							"ERROR: Monitor interval must be > 0 seconds.",
							null);
				}

				monitorManager.registerClient(
						requestPacket.getAddress(),
						requestPacket.getPort(),
						request.monitorIntervalSeconds);
				message = "Monitor registered for " + request.monitorIntervalSeconds + " seconds.";
				return new ProcessResult(true, message, null);

			case BankProtocol.OP_CHECK_BALANCE:
				message = accountStore.checkBalance(request.name, request.accountNo, request.password);
				return new ProcessResult(isSuccessMessage(message), message, null);

			case BankProtocol.OP_TRANSFER_MONEY:
				message = accountStore.transferMoney(
						request.name,
						request.accountNo,
						request.password,
						request.recipientAccountNo,
						request.amount);
				if (isSuccessMessage(message)) {
					update = "TRANSFER|from=" + request.accountNo
							+ "|to=" + request.recipientAccountNo
							+ "|amount=" + request.amount;
				}
				return new ProcessResult(isSuccessMessage(message), message, update);

			default:
				return new ProcessResult(false, "ERROR: Unsupported operation code.", null);
		}
	}

	/**
	 * Your AccountStore methods return human-readable text.
	 * We classify success/failure by checking the agreed prefix.
	 */
	private static boolean isSuccessMessage(String message) {
		return message != null && !message.startsWith("ERROR");
	}
}
