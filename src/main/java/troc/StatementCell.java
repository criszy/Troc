package troc;

import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

enum StatementType {
    UNKNOWN,
    SELECT, SELECT_SHARE, SELECT_UPDATE,
    UPDATE, DELETE, INSERT, SET,
    BEGIN, COMMIT, ROLLBACK,
}

@Slf4j
public class StatementCell {
    Transaction tx;
    int statementId;
    String statement;
    StatementType type;
    String wherePrefix = "";
    String whereClause = "";
    String forPostfix = "";
    HashMap<String, String> values = new HashMap<>();
    boolean blocked;
    boolean aborted;
    View view;
    ArrayList<Object> result;
    int newRowId;

    StatementCell(Transaction tx, int statementId) {
        this.tx = tx;
        this.statementId = statementId;
    }

    public StatementCell(Transaction tx, int statementId, String statement) {
        this.tx = tx;
        this.statementId = statementId;
        this.statement = statement.replace(";", "");
        this.type = StatementType.valueOf(this.statement.split(" ")[0]);
        this.parseStatement();
    }

    private void parseStatement() {
        int whereIdx, forIdx = -1;
        StatementType realType = type;
        String stmt = this.statement;
        try {
            switch (type) {
                case BEGIN:
                case COMMIT:
                case ROLLBACK:
                    break;
                case SELECT:
                    forIdx = stmt.indexOf("FOR ");
                    if (forIdx == -1) {
                        forIdx = stmt.indexOf("LOCK IN SHARE MODE");
                        if (forIdx == -1) {
                            forPostfix = "";
                        }
                    }
                    if (forIdx != -1) {
                        String postfix = stmt.substring(forIdx);
                        stmt = stmt.substring(0, forIdx - 1);
                        forPostfix = " " + postfix;
                        if (postfix.equals("FOR UPDATE")) {
                            realType = StatementType.SELECT_UPDATE;
                        } else if (postfix.equals("FOR SHARE") || postfix.equals("LOCK IN SHARE MODE")) {
                            realType = StatementType.SELECT_SHARE;
                        } else {
                            throw new RuntimeException("Invalid postfix: " + this.statement);
                        }
                    }
                case UPDATE:
                    int setIdx = stmt.indexOf(" SET ");
                    if (setIdx != -1) {
                        whereIdx = stmt.indexOf(" WHERE ");
                        String setPairsStr;
                        if (whereIdx == -1) {
                            setPairsStr = stmt.substring(setIdx);
                        } else {
                            setPairsStr = stmt.substring(setIdx + 5, whereIdx);
                        }
                        setPairsStr = setPairsStr.replace(" ", "");
                        String[] setPairsList = setPairsStr.split(",");
                        for (String setPair : setPairsList) {
                            int eqIdx = setPair.indexOf("=");
                            String col = setPair.substring(0, eqIdx);
                            String val = setPair.substring(eqIdx+1);
                            if (val.startsWith("\"") && val.endsWith("\"")) {
                                val = val.substring(1, val.length() - 1);
                            }
                            this.values.put(col, val);
                        }
                    }
                case DELETE:
                    whereIdx = stmt.indexOf("WHERE");
                    if (whereIdx == -1) {
                        wherePrefix = stmt;
                        whereClause = "TRUE";
                    } else {
                        wherePrefix = stmt.substring(0, whereIdx - 1);
                        whereClause = stmt.substring(whereIdx + 6);
                    }
                    this.type = realType;
                    recomputeStatement();
                    break;
                case INSERT:
                    Pattern pattern = Pattern.compile("INTO " + TableTool.TableName
                            + "\\s*\\((.*?)\\) VALUES\\s*\\((.*?)\\)");
                    Matcher matcher = pattern.matcher(this.statement);
                    if (!matcher.find()) {
                        throw new RuntimeException("parse INSERT statement failed");
                    }
                    String[] cols = matcher.group(1).split(",\\s*");
                    String[] vals = matcher.group(2).split(",\\s*");
                    if (cols.length != vals.length) {
                        throw new RuntimeException("Parse insert statement failed: " + this.statement);
                    }
                    for (int i = 0; i < cols.length; i++) {
                        String val = vals[i];
                        if (val.startsWith("\"") && val.endsWith("\"")) {
                            val = val.substring(1, val.length() - 1);
                        }
                        this.values.put(cols[i], val);
                    }
                    break;
                default:
                    throw new RuntimeException("Invalid statement: " + this.statement);
            }
        } catch (Exception e) {
            log.info("Parse statement failed: {}", statement);
            e.printStackTrace();
        }
    }

    public void makeChooseRow(int rowId) {
        String query = null;
        Statement statement;
        ResultSet rs;
        try {
            query = String.format("SELECT * FROM %s WHERE (%s) AND %s = %d",
                    TableTool.TableName, this.whereClause, TableTool.RowIdColName, rowId);
            statement = TableTool.conn.createStatement();
            rs = statement.executeQuery(query);
            boolean match = rs.next();
            statement.close();
            rs.close();
            if (match) return;
            query = String.format("SELECT (%s) FROM %s WHERE %s = %d",
                    this.whereClause, TableTool.TableName, TableTool.RowIdColName, rowId);
            statement = TableTool.conn.createStatement();
            rs = statement.executeQuery(query);
            if (!rs.next()) {
                log.info("Choose row failed, rowId:{}, statement:{}", rowId, this.statement);
                return;
            }
            Object res = rs.getObject(1);
            if (res == null) {
                this.whereClause = "(" + this.whereClause + ") IS NULL";
            } else {
                this.whereClause = "NOT (" + this.whereClause + ")";
            }
            recomputeStatement();
        } catch (SQLException e) {
            log.info("Execute query failed: {}", query);
            throw new RuntimeException("Execution failed: ", e);
        }
    }

    public void negateCondition() {
        String query = "SELECT (" + whereClause + ") as yes from " + TableTool.TableName + " limit 1";
        TableTool.executeQueryWithCallback(query, (rs)->{
            try {
                if (!rs.next()) {
                    String res = rs.getString("yes");
                    if (res == null || res.equals("null")) {
                        whereClause = "(" + whereClause + ") IS NULL";
                    } else if (res.equals("0")) {
                        whereClause = "NOT (" + whereClause + ")";
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    public void recomputeStatement() {
        this.statement = wherePrefix + " WHERE " + whereClause + forPostfix;
    }

    public String toString() {
        String res = tx.txId + "-" + statementId;
        if (blocked) {
            res += "(B)";
        }
        if (aborted) {
            res += "(A)";
        }
        return res;
    }

    public boolean equals(StatementCell that) {
        if (that == null) {
            return false;
        }
        return tx.txId == that.tx.txId && statementId == that.statementId;
    }

    public StatementCell copy() {
        StatementCell copy = new StatementCell(tx, statementId);
        copy.statement = statement;
        copy.type = type;
        copy.wherePrefix = wherePrefix;
        copy.whereClause = whereClause;
        copy.forPostfix = forPostfix;
        copy.values = values;
        copy.blocked = false;
        copy.result = null;
        return copy;
    }
}
