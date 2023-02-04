package troc.common;

import lombok.extern.slf4j.Slf4j;
import troc.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Slf4j
public abstract class Table {
    protected final String tableName;
    protected final boolean allPrimaryKey;
    protected boolean hasPrimaryKey;
    protected String createTableSql;
    protected final ArrayList<String> initializeStatements;
    protected final ArrayList<String> columnNames;
    protected final HashMap<String, Column> columns;
    protected int indexCnt = 0;
    protected ExprGen exprGenerator;

    public Table(String tableName) {
        this.tableName = tableName;
        this.allPrimaryKey = Randomly.getBoolean();
        this.hasPrimaryKey = false;
        createTableSql = "";
        initializeStatements = new ArrayList<>();
        columnNames = new ArrayList<>();
        columns = new HashMap<>();
    }

    public String getCreateTableSql() {
        return createTableSql;
    }

    public List<String> getInitializeStatements() {
        return initializeStatements;
    }

    public boolean create() {
        this.drop();
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(tableName).append("(");
        int columnCnt = 1 + Randomly.getNextInt(0, 6);
        for (int i = 0; i < columnCnt; i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append(getColumn(i));
        }
        sb.append(") ");
        sb.append(getTableOption());
        createTableSql = sb.toString();
        exprGenerator.setColumns(columns);
        return TableTool.executeOnTable(createTableSql);
    }

    protected abstract String getTableOption();

    protected abstract String getColumn(int idx);

    public void drop() {
        TableTool.executeOnTable("DROP TABLE IF EXISTS " + tableName);
    }

    public void initialize() {
        while (!this.create()) {
            log.info("Create table failed, {}", getCreateTableSql());
        }
        for (int i = 0; i < Randomly.getNextInt(5, 15); i++) {
            String initSQL;
            if (Randomly.getNextInt(0, 15) == 10) {
                initSQL = genAddIndexStatement();
            } else {
                initSQL = genInsertStatement();
            }
            initializeStatements.add(initSQL);
            TableTool.executeOnTable(initSQL);
        }
    }

    public String genSelectStatement() {
        String predicate = exprGenerator.genPredicate();
        List<String> selectedColumns = Randomly.nonEmptySubset(columnNames);
        String postfix = "";
        if (Randomly.getBoolean()) {
            if (Randomly.getBoolean()) {
                postfix = " FOR UPDATE";
            } else {
                if (TableTool.dbms != DBMS.TIDB) {
                    postfix = " LOCK IN SHARE MODE";
                }
            }
        }
        return "SELECT " + String.join(", ", selectedColumns) + " FROM "
                + tableName + " WHERE " + predicate + postfix;
    }

    public String genInsertStatement() {
        List<String> insertedCols = Randomly.nonEmptySubset(columnNames);
        for(String colName : columns.keySet()) {
            Column column = columns.get(colName);
            if ((column.isPrimaryKey() || column.isNotNull()) && !insertedCols.contains(colName)) {
                insertedCols.add(colName);
            }
        }
        List<String> insertedVals = new ArrayList<>();
        for (String colName : insertedCols) {
            Column column = columns.get(colName);
            insertedVals.add(column.getRandomVal());
        }
        String ignore = "";
        if (Randomly.getBoolean()) {
            ignore = "IGNORE ";
        }
        return "INSERT " + ignore + "INTO " + tableName + "(" + String.join(", ", insertedCols)
                + ") VALUES (" + String.join(", ", insertedVals) + ")";

    }

    public String genUpdateStatement() {
        String predicate = exprGenerator.genPredicate();
        List<String> updatedCols = Randomly.nonEmptySubset(columnNames);
        List<String> setPairs = new ArrayList<>();
        for (String colName : updatedCols) {
            setPairs.add(colName + "=" + columns.get(colName).getRandomVal());
        }
        return "UPDATE " + tableName + " SET " + String.join(", ", setPairs) + " WHERE " + predicate;
    }

    public String genDeleteStatement() {
        String predicate = exprGenerator.genPredicate();
        return "DELETE FROM " + tableName + " WHERE " + predicate;
    }

    public String genAddIndexStatement() {
        List<String> candidateColumns = Randomly.nonEmptySubset(columnNames);
        List<String> indexedColumns = new ArrayList<>();
        for (String colName: candidateColumns) {
            Column column = columns.get(colName);
            if (column.getDataType().isNumeric()) {
                indexedColumns.add(colName);
            } else if (column.getDataType().isString()){
                if (TableTool.dbms == DBMS.MYSQL || TableTool.dbms == DBMS.MARIADB || TableTool.dbms == DBMS.TIDB) {
                    indexedColumns.add(colName + "(5)");
                } else {
                    indexedColumns.add(colName);
                }
            }
        }
        String indexName = "i" + (indexCnt++);
        String unique = "";
        if (Randomly.getBoolean()) {
            unique = "UNIQUE ";
        }
        return "CREATE " + unique + "INDEX " + indexName + " ON " + tableName
                + " (" + String.join(", ", indexedColumns) + ")";
    }

    public void setIsolationLevel(SQLConnection conn, IsolationLevel isolationLevel) {
        String sql = "SET SESSION TRANSACTION ISOLATION LEVEL " + isolationLevel.getName();
        TableTool.executeWithConn(conn, sql);
    }

    public Transaction genTransaction(int txId) {
        IsolationLevel isolationLevel = Randomly.fromList(TableTool.possibleIsolationLevels);
        return genTransaction(txId, isolationLevel);
    }

    public Transaction genTransaction(int txId, IsolationLevel isolationLevel) {
        SQLConnection txConn = TableTool.genConnection();
        Transaction tx = new Transaction(txId, isolationLevel, txConn);
        setIsolationLevel(txConn, isolationLevel);
        int n = Randomly.getNextInt(TableTool.TxSizeMin, TableTool.TxSizeMax);
        ArrayList<StatementCell> statementList = new ArrayList<>();
        StatementCell cell = new StatementCell(tx, 0, "BEGIN");
        statementList.add(cell);
        for (int i = 1; i <= n; i++) {
            cell = new StatementCell(tx, i, genStatement());
            statementList.add(cell);
        }
        String lastStmt = "COMMIT";
        if (Randomly.getBoolean()) {
            lastStmt = "ROLLBACK";
        }
        cell = new StatementCell(tx, n+1, lastStmt);
        statementList.add(cell);
        tx.setStatements(statementList);
        return tx;
    }

    public String genStatement() {
        String statement;
        do {
            while (true) {
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    statement = genSelectStatement();
                    break;
                }
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    if (Randomly.getBooleanWithRatherLowProbability()) {
                        statement = genInsertStatement();
                    } else {
                        statement = genUpdateStatement();
                    }
                    break;
                }
                if (Randomly.getBooleanWithSmallProbability()) {
                    statement = genDeleteStatement();
                    break;
                }
            }
        } while (!TableTool.checkSyntax(statement));
        return statement;
    }

    @Override
    public String toString() {
        return String.format("[Table %s in DB %s Column:%s]", tableName, TableTool.DatabaseName,
                columnNames);
    }
}
