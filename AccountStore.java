import java.util.HashMap;
import java.util.Map;

public class AccountStore {

    private final Map<Integer, Account> accounts = new HashMap<>();
    private int nextAccountNumber = 1000;

    // Open Account 

    public String openAccount(String name, String password, Currency currency, float initialBalance) {
        if (name == null || name.trim().isEmpty()) {
            return "ERROR: Name cannot be empty.";
        }
        if (password == null || password.trim().isEmpty()) {
            return "ERROR: Password cannot be empty.";
        }
        if (initialBalance < 0) {
            return "ERROR: Initial balance cannot be negative.";
        }

        int newAccountNo = nextAccountNumber++;
        Account acc = new Account(newAccountNo, name, password, currency, initialBalance);
        accounts.put(newAccountNo, acc);
        return "Account opened successfully. Account Number: " + newAccountNo;
    }

    // Close Account 

    public String closeAccount(String name, int accountNo, String password) {
        Account acc = accounts.get(accountNo);
        if (acc == null) {
            return "ERROR: Account " + accountNo + " does not exist.";
        }
        if (!acc.getName().equals(name)) {
            return "ERROR: Account does not belong to " + name + ".";
        }
        if (!acc.getPassword().equals(password)) {
            return "ERROR: Incorrect password.";
        }
        accounts.remove(accountNo);
        return "Account " + accountNo + " closed successfully.";
    }

    // Deposit Funds

    public String deposit(String name, int accountNo, String password, Currency currency, float amount) {
        Account acc = accounts.get(accountNo);
        if (acc == null) {
            return "ERROR: Account " + accountNo + " does not exist.";
        }
        if (!acc.getName().equals(name)) {
            return "ERROR: Account does not belong to " + name + ".";
        }
        if (!acc.getPassword().equals(password)) {
            return "ERROR: Incorrect password.";
        }
        if (amount <= 0) {
            return "ERROR: Deposit amount must be positive.";
        }
        if (acc.getCurrency() != currency) {
            return "ERROR: Currency mismatch. Account currency is "
                    + acc.getCurrency() + " but deposit currency is " + currency + ".";
        }
        acc.deposit(amount);
        return "Deposit successful. New balance: " + acc.getBalance() + " " + acc.getCurrency();
    }

    // Withdraw Funds

    public String withdraw(String name, int accountNo, String password, Currency currency, float amount) {
        Account acc = accounts.get(accountNo);
        if (acc == null) {
            return "ERROR: Account " + accountNo + " does not exist.";
        }
        if (!acc.getName().equals(name)) {
            return "ERROR: Account does not belong to " + name + ".";
        }
        if (!acc.getPassword().equals(password)) {
            return "ERROR: Incorrect password.";
        }
        if (amount <= 0) {
            return "ERROR: Withdrawal amount must be positive.";
        }
        if (acc.getCurrency() != currency) {
            return "ERROR: Currency mismatch. Account currency is "
                    + acc.getCurrency() + " but withdrawal currency is " + currency + ".";
        }
        if (acc.getBalance() < amount) {
            return "ERROR: Insufficient funds. Balance: " + acc.getBalance() + " " + acc.getCurrency();
        }
        acc.withdraw(amount);
        return "Withdrawal successful. New balance: " + acc.getBalance() + " " + acc.getCurrency();
    }

    // Check Balance - idempotent 

    public String checkBalance(String name, int accountNo, String password) {
        Account acc = accounts.get(accountNo);
        if (acc == null) {
            return "ERROR: Account " + accountNo + " does not exist.";
        }
        if (!acc.getName().equals(name)) {
            return "ERROR: Account does not belong to " + name + ".";
        }
        if (!acc.getPassword().equals(password)) {
            return "ERROR: Incorrect password.";
        }
        return "Current balance of account " + accountNo
                + " = " + acc.getBalance() + " " + acc.getCurrency();
    }

    // Transfer Funds (non-idempotent)

    public String transferMoney(String senderName, int senderAccountNo, String password,
                                int recipientAccountNo, float amount) {
        Account sender = accounts.get(senderAccountNo);
        Account recipient = accounts.get(recipientAccountNo);

        if (sender == null) {
            return "ERROR: Sender account " + senderAccountNo + " does not exist.";
        }
        if (recipient == null) {
            return "ERROR: Recipient account " + recipientAccountNo + " does not exist.";
        }
        if (!sender.getName().equals(senderName)) {
            return "ERROR: Account " + senderAccountNo + " does not belong to " + senderName + ".";
        }
        if (!sender.getPassword().equals(password)) {
            return "ERROR: Incorrect password.";
        }
        if (amount <= 0) {
            return "ERROR: Transfer amount must be positive.";
        }
        if (sender.getCurrency() != recipient.getCurrency()) {
            return "ERROR: Currency mismatch. Cannot transfer between "
                    + sender.getCurrency() + " and " + recipient.getCurrency() + " accounts.";
        }
        if (sender.getBalance() < amount) {
            return "ERROR: Insufficient funds. Balance: "
                    + sender.getBalance() + " " + sender.getCurrency();
        }

        sender.withdraw(amount);
        recipient.deposit(amount);
        return "Transfer successful. Transferred " + amount + " " + sender.getCurrency()
                + " from account " + senderAccountNo + " to account " + recipientAccountNo
                + ". Your new balance: " + sender.getBalance() + " " + sender.getCurrency();
    }
}
