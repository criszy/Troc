package troc;


import lombok.Data;

import java.util.ArrayList;

@Data
public class Transaction {
    int txId;
    SQLConnection conn;
    IsolationLevel isolationlevel;
    ArrayList<StatementCell> statements;

    // states for analysis
    ArrayList<Transaction> snapTxs;
    View snapView;
    boolean blocked;
    boolean committed;
    boolean finished;
    boolean aborted;
    ArrayList<Lock> locks;
    ArrayList<StatementCell> blockedStatements;

    public Transaction(int txId) {
        this.txId = txId;
        statements = new ArrayList<>();
    }

    public Transaction(int txId, IsolationLevel isolationlevel, SQLConnection conn) {
        this(txId);
        this.conn = conn;
        this.isolationlevel = isolationlevel;
        clearStates();
    }

    void clearStates() {
        this.snapTxs = new ArrayList<>();
        this.snapView = new View();
        this.blocked = false;
        this.committed = false;
        this.finished = false;
        this.aborted = false;
        this.locks = new ArrayList<>();
        this.blockedStatements = new ArrayList<>();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Transaction{%d, %s}, with statements:\n", txId, isolationlevel));
        if (statements != null) {
            for (StatementCell stmt : statements) {
                sb.append("\t").append(stmt.statement).append(";\n");
            }
        }
        return sb.toString();
    }
}
