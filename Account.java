public class Account {
    public int accountNumber;
    public String name;
    public String password;
    public String currency;
    public float balance;

    public Account(int accountNumber, String name, String password, String currency, float balance) {
        this.accountNumber = accountNumber;
        this.name = name;
        this.password = password;
        this.currency = currency;
        this.balance = balance;
    }
}
