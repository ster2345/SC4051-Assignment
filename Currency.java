public enum Currency {
    SGD,   // Singapore Dollar  (ordinal 0)
    USD,   // US Dollar         (ordinal 1)
    EUR,   // Euro              (ordinal 2)
    GBP,   // British Pound     (ordinal 3)
    JPY;   // Japanese Yen      (ordinal 4)

    public static Currency fromString(String input) {
        if (input == null) throw new IllegalArgumentException("Currency input cannot be null.");
        try {
            return Currency.valueOf(input.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            StringBuilder valid = new StringBuilder();
            for (Currency c : values()) valid.append(c.name()).append(" ");
            throw new IllegalArgumentException(
                    "Unknown currency: '" + input + "'. Valid options: " + valid.toString().trim());
        }
    }

    public static String menuOptions() {
        StringBuilder sb = new StringBuilder();
        Currency[] vals = values();
        for (int i = 0; i < vals.length; i++) {
            sb.append(vals[i].name());
            if (i < vals.length - 1) sb.append(", ");
        }
        return sb.toString();
    }
}
