import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * BankProtocol
 *
 * Central place for request/reply message definitions and marshalling helpers.
 *
 * Why this class exists:
 * - The assignment requires ALL client/server data to be sent as raw bytes.
 * - Different operations have different parameters, so we need a protocol.
 * - We avoid Java serialization / stream-based object I/O entirely.
 *
 * Wire-format overview:
 *
 * Request payload (inside Retry header):
 *   byte opCode
 *   ... operation-specific fields ...
 *
 * Reply payload (inside Retry header):
 *   byte statusCode      (0 = success, 1 = error)
 *   int  messageLength
 *   byte[] messageUtf8
 *
 * Primitive encoding rules:
 * - int   : 4 bytes, big-endian
 * - float : encoded via Float.floatToIntBits(), then big-endian int
 * - String: int length (bytes), then UTF-8 bytes
 *
 * IMPORTANT:
 * - This class only handles payload bytes (the part after the 8-byte
 *   clientId/requestId header). The outer header is handled by
 *   RetryClientLogic.wrapWithHeader()/stripHeader().
 */
public final class BankProtocol {

    private BankProtocol() {
        // Utility class; no instances.
    }

    // ---------------------------------------------------------------------
    // Operation codes (request opCode values)
    // ---------------------------------------------------------------------

    public static final byte OP_OPEN_ACCOUNT      = 1;
    public static final byte OP_CLOSE_ACCOUNT     = 2;
    public static final byte OP_DEPOSIT           = 3;
    public static final byte OP_WITHDRAW          = 4;
    public static final byte OP_REGISTER_MONITOR  = 5;
    public static final byte OP_CHECK_BALANCE     = 6; // idempotent
    public static final byte OP_TRANSFER_MONEY    = 7; // non-idempotent

    // ---------------------------------------------------------------------
    // Reply status codes
    // ---------------------------------------------------------------------

    public static final byte STATUS_SUCCESS = 0;
    public static final byte STATUS_ERROR   = 1;

    // ---------------------------------------------------------------------
    // Invocation semantics options (for startup args)
    // ---------------------------------------------------------------------

    public enum InvocationSemantics {
        AT_LEAST_ONCE,
        AT_MOST_ONCE;

        /**
         * Parses flexible user input from command-line / menu text.
         */
        public static InvocationSemantics fromString(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("Semantics cannot be null.");
            }
            String s = raw.trim().toLowerCase();
            if (s.equals("at-least-once") || s.equals("alo") || s.equals("atleastonce")) {
                return AT_LEAST_ONCE;
            }
            if (s.equals("at-most-once") || s.equals("amo") || s.equals("atmostonce")) {
                return AT_MOST_ONCE;
            }
            throw new IllegalArgumentException(
                    "Unknown semantics: " + raw + " (use at-least-once or at-most-once)");
        }
    }

    // ---------------------------------------------------------------------
    // Decoded request / reply models
    // ---------------------------------------------------------------------

    /**
     * Generic decoded request model.
     *
     * We use one class for all operations to keep server dispatch simple.
     * Only the relevant fields are populated depending on opCode.
     */
    public static class Request {
        public byte opCode;

        public String name;
        public String password;
        public String currency;

        public int accountNo;
        public int recipientAccountNo;

        public float amount;
        public float initialBalance;

        public int monitorIntervalSeconds;
    }

    /**
     * Decoded reply model.
     */
    public static class Reply {
        public boolean success;
        public String message;
    }

    // ---------------------------------------------------------------------
    // Request marshalling helpers
    // ---------------------------------------------------------------------

    public static byte[] marshalOpenAccountRequest(
            String name, String password, String currency, float initialBalance) {
        ByteWriter writer = new ByteWriter();
        writer.putByte(OP_OPEN_ACCOUNT);
        writer.putString(name);
        writer.putString(password);
        writer.putString(currency);
        writer.putFloat(initialBalance);
        return writer.toByteArray();
    }

    public static byte[] marshalCloseAccountRequest(String name, int accountNo, String password) {
        ByteWriter writer = new ByteWriter();
        writer.putByte(OP_CLOSE_ACCOUNT);
        writer.putString(name);
        writer.putInt(accountNo);
        writer.putString(password);
        return writer.toByteArray();
    }

    public static byte[] marshalDepositRequest(
            String name, int accountNo, String password, String currency, float amount) {
        ByteWriter writer = new ByteWriter();
        writer.putByte(OP_DEPOSIT);
        writer.putString(name);
        writer.putInt(accountNo);
        writer.putString(password);
        writer.putString(currency);
        writer.putFloat(amount);
        return writer.toByteArray();
    }

    public static byte[] marshalWithdrawRequest(
            String name, int accountNo, String password, String currency, float amount) {
        ByteWriter writer = new ByteWriter();
        writer.putByte(OP_WITHDRAW);
        writer.putString(name);
        writer.putInt(accountNo);
        writer.putString(password);
        writer.putString(currency);
        writer.putFloat(amount);
        return writer.toByteArray();
    }

    public static byte[] marshalRegisterMonitorRequest(int intervalSeconds) {
        ByteWriter writer = new ByteWriter();
        writer.putByte(OP_REGISTER_MONITOR);
        writer.putInt(intervalSeconds);
        return writer.toByteArray();
    }

    public static byte[] marshalCheckBalanceRequest(String name, int accountNo, String password) {
        ByteWriter writer = new ByteWriter();
        writer.putByte(OP_CHECK_BALANCE);
        writer.putString(name);
        writer.putInt(accountNo);
        writer.putString(password);
        return writer.toByteArray();
    }

    public static byte[] marshalTransferMoneyRequest(
            String senderName,
            int senderAccountNo,
            String password,
            int recipientAccountNo,
            float amount) {
        ByteWriter writer = new ByteWriter();
        writer.putByte(OP_TRANSFER_MONEY);
        writer.putString(senderName);
        writer.putInt(senderAccountNo);
        writer.putString(password);
        writer.putInt(recipientAccountNo);
        writer.putFloat(amount);
        return writer.toByteArray();
    }

    // ---------------------------------------------------------------------
    // Request unmarshalling (server-side)
    // ---------------------------------------------------------------------

    /**
     * Decodes one request payload (after the 8-byte Retry header).
     *
     * @throws IllegalArgumentException if payload is malformed.
     */
    public static Request unmarshalRequest(byte[] payload, int length) {
        ByteReader reader = new ByteReader(payload, length);
        Request req = new Request();

        req.opCode = reader.readByte();

        switch (req.opCode) {
            case OP_OPEN_ACCOUNT:
                req.name = reader.readString();
                req.password = reader.readString();
                req.currency = reader.readString();
                req.initialBalance = reader.readFloat();
                break;

            case OP_CLOSE_ACCOUNT:
                req.name = reader.readString();
                req.accountNo = reader.readInt();
                req.password = reader.readString();
                break;

            case OP_DEPOSIT:
                req.name = reader.readString();
                req.accountNo = reader.readInt();
                req.password = reader.readString();
                req.currency = reader.readString();
                req.amount = reader.readFloat();
                break;

            case OP_WITHDRAW:
                req.name = reader.readString();
                req.accountNo = reader.readInt();
                req.password = reader.readString();
                req.currency = reader.readString();
                req.amount = reader.readFloat();
                break;

            case OP_REGISTER_MONITOR:
                req.monitorIntervalSeconds = reader.readInt();
                break;

            case OP_CHECK_BALANCE:
                req.name = reader.readString();
                req.accountNo = reader.readInt();
                req.password = reader.readString();
                break;

            case OP_TRANSFER_MONEY:
                req.name = reader.readString();
                req.accountNo = reader.readInt(); // sender account
                req.password = reader.readString();
                req.recipientAccountNo = reader.readInt();
                req.amount = reader.readFloat();
                break;

            default:
                throw new IllegalArgumentException("Unknown opCode: " + req.opCode);
        }

        if (reader.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Malformed request: extra unread bytes remain (" + reader.remaining() + ")");
        }

        return req;
    }

    // ---------------------------------------------------------------------
    // Reply marshalling/unmarshalling
    // ---------------------------------------------------------------------

    public static byte[] marshalReply(boolean success, String message) {
        ByteWriter writer = new ByteWriter();
        writer.putByte(success ? STATUS_SUCCESS : STATUS_ERROR);
        writer.putString(message == null ? "" : message);
        return writer.toByteArray();
    }

    public static Reply unmarshalReply(byte[] payload, int length) {
        ByteReader reader = new ByteReader(payload, length);
        Reply reply = new Reply();
        byte status = reader.readByte();
        reply.success = (status == STATUS_SUCCESS);
        reply.message = reader.readString();

        if (reader.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Malformed reply: extra unread bytes remain (" + reader.remaining() + ")");
        }

        return reply;
    }

    // ---------------------------------------------------------------------
    // Internal low-level byte writer
    // ---------------------------------------------------------------------

    /**
     * Small growable byte buffer with explicit putX methods.
     *
     * We intentionally avoid DataOutputStream / ObjectOutputStream.
     */
    private static class ByteWriter {
        private byte[] data = new byte[64];
        private int position = 0;

        void putByte(byte value) {
            ensureCapacity(1);
            data[position++] = value;
        }

        void putInt(int value) {
            ensureCapacity(4);
            data[position++] = (byte) ((value >>> 24) & 0xFF);
            data[position++] = (byte) ((value >>> 16) & 0xFF);
            data[position++] = (byte) ((value >>> 8) & 0xFF);
            data[position++] = (byte) (value & 0xFF);
        }

        void putFloat(float value) {
            putInt(Float.floatToIntBits(value));
        }

        void putString(String text) {
            String safe = (text == null) ? "" : text;
            byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
            putInt(bytes.length);
            ensureCapacity(bytes.length);
            System.arraycopy(bytes, 0, data, position, bytes.length);
            position += bytes.length;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(data, position);
        }

        private void ensureCapacity(int additional) {
            int needed = position + additional;
            if (needed <= data.length) {
                return;
            }
            int newSize = data.length;
            while (newSize < needed) {
                newSize *= 2;
            }
            data = Arrays.copyOf(data, newSize);
        }
    }

    // ---------------------------------------------------------------------
    // Internal low-level byte reader
    // ---------------------------------------------------------------------

    /**
     * Strict reader that throws IllegalArgumentException on malformed payloads.
     */
    private static class ByteReader {
        private final byte[] data;
        private final int limit;
        private int position;

        ByteReader(byte[] source, int length) {
            if (source == null) {
                throw new IllegalArgumentException("source cannot be null");
            }
            if (length < 0 || length > source.length) {
                throw new IllegalArgumentException("Invalid length: " + length);
            }
            this.data = source;
            this.limit = length;
            this.position = 0;
        }

        byte readByte() {
            require(1);
            return data[position++];
        }

        int readInt() {
            require(4);
            int value = ((data[position] & 0xFF) << 24)
                    | ((data[position + 1] & 0xFF) << 16)
                    | ((data[position + 2] & 0xFF) << 8)
                    | (data[position + 3] & 0xFF);
            position += 4;
            return value;
        }

        float readFloat() {
            return Float.intBitsToFloat(readInt());
        }

        String readString() {
            int length = readInt();
            if (length < 0) {
                throw new IllegalArgumentException("Negative string length: " + length);
            }
            require(length);
            String text = new String(data, position, length, StandardCharsets.UTF_8);
            position += length;
            return text;
        }

        boolean hasRemaining() {
            return position < limit;
        }

        int remaining() {
            return limit - position;
        }

        private void require(int n) {
            if (position + n > limit) {
                throw new IllegalArgumentException(
                        "Malformed payload: need " + n + " bytes but only "
                                + (limit - position) + " available");
            }
        }
    }
}
