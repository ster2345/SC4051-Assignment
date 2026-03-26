/**
 * Account — domain model for a single bank account.
 */
public class Account {

    private final int      accountNumber;
    private final String   name;
    private final String   password;
    private final Currency currency;
    private       float    balance;

    public Account(int accountNumber, String name, String password, Currency currency, float balance) {
        this.accountNumber = accountNumber;
        this.name          = name;
        this.password      = password;
        this.currency      = currency;
        this.balance       = balance;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public int      getAccountNumber() { return accountNumber; }
    public String   getName()          { return name; }
    public String   getPassword()      { return password; }
    public Currency getCurrency()      { return currency; }
    public float    getBalance()       { return balance; }

    // ── Package-level balance mutators (only AccountStore should call these) ─

    void deposit(float amount)  { this.balance += amount; }
    void withdraw(float amount) { this.balance -= amount; }

    @Override
    public String toString() {
        return "Account{no=" + accountNumber + ", name=" + name
                + ", currency=" + currency + ", balance=" + balance + "}";
    }
}
