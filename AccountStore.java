import java.util.HashMap;
// holds all accounts in memory + contains handler methods 
public class AccountStore {
    private HashMap<Integer, Account> accounts = new HashMap<>();
    private int nextAccountNumber = 1000;

    // OPEN ACCOUNT:
    public String openAccount(String name, String password, String currency, float initialBalance) {
        int newAccountNo = nextAccountNumber++; 
        Account acc = new Account(newAccountNo, name, password, currency, initialBalance);
        accounts.put(newAccountNo, acc);
        return "Account opened successfully. Account Number: " + newAccountNo;
        // add smt for the monitor updates part ?
    }

    // CLOSE ACCOUNT:
    public String closeAccount(String name, int accountNo, String password) {
        if (!accounts.containsKey(accountNo)) {
            return "ERROR: Account " + accountNo + " does not exist.";
        }
        Account acc = accounts.get(accountNo);
        if (!acc.name.equals(name)) {
            return "ERROR: Account does not belong to " + name;
        }
        if (!acc.password.equals(password)) {
            return "ERROR: Incorrect password.";
        }
        accounts.remove(accountNo);
        return "Account " + accountNo + " closed successfully.";
        // add smt for the monitor updates part ?
    }

    // DEPOSIT:
    public String deposit(String name, int accountNo, String password, String currency, float amount) {
        if (!accounts.containsKey(accountNo)) {
            return "ERROR: Account " + accountNo + " does not exist.";
        }
        Account acc = accounts.get(accountNo);
        if (!acc.name.equals(name)) {
            return "ERROR: Account does not belong to " + name;
        }
        if (!acc.password.equals(password)) {
            return "ERROR: Incorrect password.";
        }
        acc.balance += amount;
        return "Deposit successful. New balance: " + acc.balance;
        // add smt for the monitor updates part ?
    }
    
    // WITHDRAW:
    public String withdraw(String name, int accountNo, String password, String currency, float amount) {
        if (!accounts.containsKey(accountNo)) {
            return "ERROR: Account " + accountNo + " does not exist.";
        }
        Account acc = accounts.get(accountNo);
        if (!acc.name.equals(name)) {
            return "ERROR: Account does not belong to " + name;
        }
        if (!acc.password.equals(password)) {
            return "ERROR: Incorrect password.";
        }
        if (acc.balance < amount) {
            return "ERROR: Insufficient funds.";
        }
        acc.balance -= amount;
        return "Withdrawal successful. New balance: " + acc.balance;
        // add smt for the monitor updates part ?
    }

    // CHECK BALANCE (IDEMPOTENT OPERATION):
    public String checkBalance(String name, int accountNo, String password) {
        if (!accounts.containsKey(accountNo)) {
            return "ERROR: Account " + accountNo + " does not exist.";
        }
        Account acc = accounts.get(accountNo);
        if (!acc.name.equals(name)) {
            return "ERROR: Account does not belong to " + name;
        }
        if (!acc.password.equals(password)) {
            return "ERROR: Incorrect password.";
        }
        return "Current balance of account " + accountNo + " = " + acc.balance + " " + acc.currency;
        }

    // TRANSFER MONEY (NON-IDEMPOTENT OPERATION):
    public String transferMoney(String senderName, int senderAccountNo, String password, int recipientAccountNo, float amount) {
        if (!accounts.containsKey(senderAccountNo)) {
            return "ERROR: Account " + senderAccountNo + " does not exist.";
        }
        if (!accounts.containsKey(recipientAccountNo)) {
            return "ERROR: Account " + recipientAccountNo + " does not exist.";
        }

        Account senderAcc = accounts.get(senderAccountNo);
        Account recipientAcc = accounts.get(recipientAccountNo);

        if (!senderAcc.name.equals(senderName)) {
            return "ERROR: Account " + senderAccountNo + " does not belong to " + senderName;
        }
        if (!senderAcc.password.equals(password)) {
            return "ERROR: Incorrect password.";
        }
        if (senderAcc.balance < amount) {
            return "ERROR: Insufficient funds.";
        }

        // transfer:
        senderAcc.balance -= amount;
        recipientAcc.balance += amount;
        return "SUCCESS! Transferred " + amount + " from account " + senderAccountNo + " to account " + recipientAccountNo + ". Your current balance is " + senderAcc.balance + " " + senderAcc.currency;
    }
}