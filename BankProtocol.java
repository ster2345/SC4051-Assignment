import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class BankProtocol {

    private BankProtocol() {}

    public static final byte OP_OPEN_ACCOUNT     = 1;
    public static final byte OP_CLOSE_ACCOUNT    = 2;
    public static final byte OP_DEPOSIT          = 3;
    public static final byte OP_WITHDRAW         = 4;
    public static final byte OP_REGISTER_MONITOR = 5;
    public static final byte OP_CHECK_BALANCE    = 6;
    public static final byte OP_TRANSFER_MONEY   = 7;

    public static final byte STATUS_SUCCESS = 0;
    public static final byte STATUS_ERROR   = 1;

    public static final int PASSWORD_FIXED_BYTES = 16;

    public enum InvocationSemantics {
        AT_LEAST_ONCE,
        AT_MOST_ONCE;

        public static InvocationSemantics fromString(String raw) {
            if (raw == null) throw new IllegalArgumentException("Semantics cannot be null.");
            switch (raw.trim().toLowerCase()) {
                case "at-least-once": case "alo": case "atleastonce": return AT_LEAST_ONCE;
                case "at-most-once":  case "amo": case "atmostonce":  return AT_MOST_ONCE;
                default: throw new IllegalArgumentException(
                        "Unknown semantics: " + raw + " (use at-least-once or at-most-once)");
            }
        }
    }


    // only the fields relevant to the opCode are populated.

    public static class Request {
        public byte     opCode;
        public String   name;
        public String   password;
        public Currency currency;
        public int      accountNo;
        public int      recipientAccountNo;
        public float    amount;
        public float    initialBalance;
        public int      monitorIntervalSeconds;
    }

    public static class Reply {
        public boolean success;
        public String  message;
    }


    public static byte[] marshalOpenAccountRequest(
            String name, String password, Currency currency, float initialBalance) {
        ByteWriter w = new ByteWriter();
        w.putByte(OP_OPEN_ACCOUNT);
        w.putString(name);
        w.putFixedPassword(password);
        w.putCurrency(currency);
        w.putFloat(initialBalance);
        return w.toByteArray();
    }

    public static byte[] marshalCloseAccountRequest(String name, int accountNo, String password) {
        ByteWriter w = new ByteWriter();
        w.putByte(OP_CLOSE_ACCOUNT);
        w.putString(name);
        w.putInt(accountNo);
        w.putFixedPassword(password);
        return w.toByteArray();
    }

    public static byte[] marshalDepositRequest(
            String name, int accountNo, String password, Currency currency, float amount) {
        ByteWriter w = new ByteWriter();
        w.putByte(OP_DEPOSIT);
        w.putString(name);
        w.putInt(accountNo);
        w.putFixedPassword(password);
        w.putCurrency(currency);
        w.putFloat(amount);
        return w.toByteArray();
    }

    public static byte[] marshalWithdrawRequest(
            String name, int accountNo, String password, Currency currency, float amount) {
        ByteWriter w = new ByteWriter();
        w.putByte(OP_WITHDRAW);
        w.putString(name);
        w.putInt(accountNo);
        w.putFixedPassword(password);
        w.putCurrency(currency);
        w.putFloat(amount);
        return w.toByteArray();
    }

    public static byte[] marshalRegisterMonitorRequest(int intervalSeconds) {
        ByteWriter w = new ByteWriter();
        w.putByte(OP_REGISTER_MONITOR);
        w.putInt(intervalSeconds);
        return w.toByteArray();
    }

    public static byte[] marshalCheckBalanceRequest(String name, int accountNo, String password) {
        ByteWriter w = new ByteWriter();
        w.putByte(OP_CHECK_BALANCE);
        w.putString(name);
        w.putInt(accountNo);
        w.putFixedPassword(password);
        return w.toByteArray();
    }

    public static byte[] marshalTransferMoneyRequest(
            String senderName, int senderAccountNo, String password,
            int recipientAccountNo, float amount) {
        ByteWriter w = new ByteWriter();
        w.putByte(OP_TRANSFER_MONEY);
        w.putString(senderName);
        w.putInt(senderAccountNo);
        w.putFixedPassword(password);
        w.putInt(recipientAccountNo);
        w.putFloat(amount);
        return w.toByteArray();
    }

    public static Request unmarshalRequest(byte[] payload, int length) {
        ByteReader r = new ByteReader(payload, length);
        Request req  = new Request();
        req.opCode   = r.readByte();

        switch (req.opCode) {
            case OP_OPEN_ACCOUNT:
                req.name           = r.readString();
                req.password       = r.readFixedPassword();
                req.currency       = r.readCurrency();   
                req.initialBalance = r.readFloat();
                break;
            case OP_CLOSE_ACCOUNT:
                req.name      = r.readString();
                req.accountNo = r.readInt();
                req.password  = r.readFixedPassword();
                break;
            case OP_DEPOSIT:
                req.name      = r.readString();
                req.accountNo = r.readInt();
                req.password  = r.readFixedPassword();
                req.currency  = r.readCurrency();
                req.amount    = r.readFloat();
                break;
            case OP_WITHDRAW:
                req.name      = r.readString();
                req.accountNo = r.readInt();
                req.password  = r.readFixedPassword();
                req.currency  = r.readCurrency();
                req.amount    = r.readFloat();
                break;
            case OP_REGISTER_MONITOR:
                req.monitorIntervalSeconds = r.readInt();
                break;
            case OP_CHECK_BALANCE:
                req.name      = r.readString();
                req.accountNo = r.readInt();
                req.password  = r.readFixedPassword();
                break;
            case OP_TRANSFER_MONEY:
                req.name               = r.readString();
                req.accountNo          = r.readInt();
                req.password           = r.readFixedPassword();
                req.recipientAccountNo = r.readInt();
                req.amount             = r.readFloat();
                break;
            default:
                throw new IllegalArgumentException("Unknown opCode: " + req.opCode);
        }

        if (r.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Malformed request: " + r.remaining() + " extra bytes remain");
        }
        return req;
    }



    public static byte[] marshalReply(boolean success, String message) {
        ByteWriter w = new ByteWriter();
        w.putByte(success ? STATUS_SUCCESS : STATUS_ERROR);
        w.putString(message == null ? "" : message);
        return w.toByteArray();
    }

    public static Reply unmarshalReply(byte[] payload, int length) {
        ByteReader r  = new ByteReader(payload, length);
        Reply reply   = new Reply();
        reply.success = (r.readByte() == STATUS_SUCCESS);
        reply.message = r.readString();
        if (r.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Malformed reply: " + r.remaining() + " extra bytes remain");
        }
        return reply;
    }



    private static class ByteWriter {
        private byte[] data = new byte[64];
        private int    pos  = 0;

        void putByte(byte value) {
            ensureCapacity(1);
            data[pos++] = value;
        }

        void putInt(int value) {
            ensureCapacity(4);
            data[pos++] = (byte) ((value >>> 24) & 0xFF);
            data[pos++] = (byte) ((value >>> 16) & 0xFF);
            data[pos++] = (byte) ((value >>>  8) & 0xFF);
            data[pos++] = (byte)  (value         & 0xFF);
        }

        void putFloat(float value) { putInt(Float.floatToIntBits(value)); }

        void putString(String text) {
            byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
            putInt(bytes.length);
            ensureCapacity(bytes.length);
            System.arraycopy(bytes, 0, data, pos, bytes.length);
            pos += bytes.length;
        }

        //password fixed-size byte field (zero-padded)
        
        void putFixedPassword(String password) {
            byte[] bytes = (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > PASSWORD_FIXED_BYTES) {
                throw new IllegalArgumentException(
                        "Password too long for fixed field (max "
                                + PASSWORD_FIXED_BYTES + " bytes, got " + bytes.length + ")");
            }

            ensureCapacity(PASSWORD_FIXED_BYTES);

            System.arraycopy(bytes, 0, data, pos, bytes.length);
            for (int i = bytes.length; i < PASSWORD_FIXED_BYTES; i++) {
                data[pos + i] = 0;
            }
            pos += PASSWORD_FIXED_BYTES;
        }

        void putCurrency(Currency currency) {
            ensureCapacity(1);
            data[pos++] = (byte) currency.ordinal();
        }

        byte[] toByteArray() { return Arrays.copyOf(data, pos); }

        private void ensureCapacity(int extra) {
            int needed = pos + extra;
            if (needed <= data.length) return;
            int newSize = data.length;
            while (newSize < needed) newSize *= 2;
            data = Arrays.copyOf(data, newSize);
        }
    }


    private static class ByteReader {
        private final byte[] data;
        private final int    limit;
        private       int    pos;

        ByteReader(byte[] source, int length) {
            if (source == null)                        throw new IllegalArgumentException("source null");
            if (length < 0 || length > source.length) throw new IllegalArgumentException("bad length");
            this.data  = source;
            this.limit = length;
            this.pos   = 0;
        }

        byte readByte() { require(1); return data[pos++]; }

        int readInt() {
            require(4);
            int v = ((data[pos] & 0xFF) << 24) | ((data[pos+1] & 0xFF) << 16)
                  | ((data[pos+2] & 0xFF) << 8) |  (data[pos+3] & 0xFF);
            pos += 4;
            return v;
        }

        float readFloat() { return Float.intBitsToFloat(readInt()); }

        String readString() {
            int len = readInt();
            if (len < 0) throw new IllegalArgumentException("Negative string length: " + len);
            require(len);
            String s = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        
        // trims trailing zero padding bytes
        
        String readFixedPassword() {
            require(PASSWORD_FIXED_BYTES);

            int start = pos;
            int end = start + PASSWORD_FIXED_BYTES;

            int effectiveEnd = end;
            while (effectiveEnd > start && data[effectiveEnd - 1] == 0) {
                effectiveEnd--;
            }

            String password = new String(
                    data,
                    start,
                    effectiveEnd - start,
                    StandardCharsets.UTF_8);

            pos = end;
            return password;
        }

        Currency readCurrency() {
            byte ordinal = readByte();
            Currency[] values = Currency.values();
            if (ordinal < 0 || ordinal >= values.length) {
                throw new IllegalArgumentException("Unknown currency ordinal: " + ordinal);
            }
            return values[ordinal];
        }

        boolean hasRemaining() { return pos < limit; }
        int     remaining()    { return limit - pos; }

        private void require(int n) {
            if (pos + n > limit) throw new IllegalArgumentException(
                    "Need " + n + " bytes but only " + (limit - pos) + " available");
        }
    }
}

