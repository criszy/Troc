package troc;

import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
public class TrocChecker {

    protected Transaction tx1;
    protected Transaction tx2;
    private String bugInfo;
    private HashMap<Integer, ArrayList<Version>> vData;
    private boolean isDeadlock;

    public TrocChecker(Transaction tx1, Transaction tx2) {
        this.tx1 = tx1;
        this.tx2 = tx2;
    }

    public void checkSchedule(String scheduleStr) {
        String[] schedule = scheduleStr.split("-");
        int len1 = tx1.statements.size();
        int len2 = tx2.statements.size();
        if (schedule.length != len1 + len2) {
            throw new RuntimeException("Invalid Schedule");
        }
        ArrayList<StatementCell> submittedOrder = new ArrayList<>();
        int idx1 = 0, idx2 = 0;
        for (String txId : schedule) {
            if (txId.equals("1")) {
                submittedOrder.add(tx1.statements.get(idx1++));
            } else if (txId.equals("2")) {
                submittedOrder.add(tx2.statements.get(idx2++));
            } else {
                throw new RuntimeException("Invalid Schedule");
            }
        }
        oracleCheck(submittedOrder);
    }

    public void checkRandom() {
        checkRandom(TableTool.CheckSize);
    }

    public void checkRandom(int count) {
        ArrayList<ArrayList<StatementCell>> submittedOrderList = ShuffleTool.sampleSubmittedTrace(tx1, tx2, count);
        for (ArrayList<StatementCell> submittedOrder : submittedOrderList) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {}
            oracleCheck(submittedOrder);
        }
    }

    public void checkAll() {
        ArrayList<ArrayList<StatementCell>> submittedOrderList = ShuffleTool.genAllSubmittedTrace(tx1, tx2);
        for (ArrayList<StatementCell> submittedOrder : submittedOrderList) {
            oracleCheck(submittedOrder);
        }
    }

    private boolean oracleCheck(ArrayList<StatementCell> schedule) {
        TableTool.allCase++;
        log.info("Check new schedule.");
        TableTool.recoverOriginalTable();
        bugInfo = "";
        TxnPairExecutor executor = new TxnPairExecutor(scheduleClone(schedule), tx1, tx2);
        TxnPairResult execResult = executor.getResult();
        // ArrayList<StatementCell> mvccSchedule = inferOracleOrderMVCC(scheduleClone(schedule));
        // TxnPairResult mvccResult = obtainOracleResults(mvccSchedule);
        TxnPairResult mvccResult = inferOracleMVCC(scheduleClone(schedule));
        bugInfo = " -- MVCC Error \n";
        if (TableTool.options.isSetCase()) {
            log.info("Schedule: " + schedule);
            log.info("Input schedule: " + getScheduleInputStr(schedule));
            log.info("Get execute result: " + execResult);
            log.info("MVCC-based oracle order: " + mvccResult.getOrder());
            log.info("MVCC-based oracle result: " + mvccResult);
            return compareOracles(execResult, mvccResult);
        }
        if (compareOracles(execResult, mvccResult)) {
            log.info("Schedule: " + schedule);
            log.info("Input schedule: " + getScheduleInputStr(schedule));
            log.info("Get execute result: " + execResult);
            log.info("MVCC-based oracle order: " + mvccResult.getOrder());
            log.info("MVCC-based oracle result: " + mvccResult);
            return true;
        }
        TableTool.bugReport.setInputSchedule(getScheduleInputStr(schedule));
        TableTool.bugReport.setSubmittedOrder(schedule.toString());
        TableTool.bugReport.setExecRes(execResult);
        TableTool.bugReport.setInferredRes(mvccResult);
        log.info(TableTool.bugReport.toString());
        return false;
    }

    private String getScheduleInputStr(ArrayList<StatementCell> schedule) {
        ArrayList<String> order = new ArrayList<>();
        for (StatementCell stmt : schedule) {
            order.add(Integer.toString(stmt.tx.txId));
        }
        return String.join("-", order);
    }

    private ArrayList<StatementCell> scheduleClone(ArrayList<StatementCell> schedule) {
        ArrayList<StatementCell> copied = new ArrayList<>();
        for (StatementCell stmt : schedule) {
            copied.add(stmt.copy());
        }
        return copied;
    }

    private TxnPairResult inferOracleMVCC(ArrayList<StatementCell> schedule) {
        TableTool.recoverOriginalTable();
        isDeadlock = false;
        ArrayList<StatementCell> oracleOrder = new ArrayList<>();
        TableTool.firstTxnInSerOrder = null;
        tx1.clearStates();
        tx2.clearStates();
        vData = TableTool.initVersionData();
        for (StatementCell stmt : schedule) {
            Transaction curTx = stmt.tx;
            Transaction otherTx = curTx == tx1 ? tx2 : tx1;
            if (curTx.blocked) {
                curTx.blockedStatements.add(stmt);
                continue;
            }
            boolean blocked = analyzeStmt(stmt, curTx, otherTx);
            if (blocked) {
                StatementCell blockPoint = stmt.copy();
                blockPoint.blocked = true;
                oracleOrder.add(blockPoint);
                curTx.blockedStatements.add(stmt);
            } else {
                oracleOrder.add(stmt);
                if (stmt.type == StatementType.COMMIT || stmt.type == StatementType.ROLLBACK) {
                    otherTx.blocked = false;
                    for (StatementCell blockedStmt : otherTx.blockedStatements) {
                        analyzeStmt(blockedStmt, otherTx, curTx);
                        oracleOrder.add(blockedStmt);
                    }
                }
            }
            if (curTx.blocked && otherTx.blocked) {
                isDeadlock = true;
                tx1.clearStates();
                tx2.clearStates();
                break;
            }
        }
        TableTool.viewToTable(newestView());
        ArrayList<Object> finalState = TableTool.getFinalStateAsList();
        TxnPairResult result = new TxnPairResult();
        result.setOrder(oracleOrder);
        result.setFinalState(finalState);
        result.setDeadBlock(isDeadlock);
        tx1.clearStates();
        tx2.clearStates();
        return result;
    }

    private boolean analyzeStmt(StatementCell stmt, Transaction curTx, Transaction otherTx) {
        if (curTx.aborted) {
            if (stmt.type != StatementType.COMMIT && stmt.type != StatementType.ROLLBACK) {
                stmt.aborted = true;
            }
            return false;
        }
        if (curTx.isolationlevel == IsolationLevel.READ_UNCOMMITTED
            && (stmt.type == StatementType.SELECT_SHARE || stmt.type == StatementType.SELECT_UPDATE)) {
                stmt.view = newView();
        } else {
            stmt.view = buildTxView(curTx, otherTx, false);
        }
        Lock lock = TableTool.getLock(stmt);
        if (lock.isConflict(otherTx) && !otherTx.aborted && !otherTx.committed) {
            curTx.blocked = true;
            TableTool.firstTxnInSerOrder = otherTx;
            return true;
        }
        if (curTx.aborted) {
            stmt.aborted = true;
            curTx.locks.clear();
            deleteVersion(curTx);
            return false;
        }
        if (lock.type != LockType.NONE) {
            curTx.locks.add(lock);
        }
        if (curTx.snapTxs.isEmpty() && isSnapshotPoint(stmt)) {
            curTx.snapTxs.addAll(Arrays.asList(TableTool.txInit, curTx));
            if (otherTx.committed) {
                curTx.snapTxs.add(otherTx);
            }
            curTx.snapView = snapshotView(curTx);
        }
        View view;
        if (stmt.type == StatementType.BEGIN || stmt.type == StatementType.COMMIT
                || stmt.type == StatementType.ROLLBACK) {
            // curTx.locks.clear();
            if (stmt.type == StatementType.BEGIN) {
                curTx.locks.clear();
            }
            if (stmt.type == StatementType.COMMIT) {
                curTx.committed = true;
            }
            if (stmt.type == StatementType.ROLLBACK) {
                curTx.aborted = true;
                curTx.finished = true;
                curTx.locks.clear();
                deleteVersion(curTx);
            }
        } else if (stmt.type == StatementType.SELECT) {
            if (curTx.isolationlevel == IsolationLevel.REPEATABLE_READ) {
                view = snapshotView(curTx);
            } else if (curTx.isolationlevel == IsolationLevel.READ_UNCOMMITTED) {
                view = newView();
            } else {
                view = buildTxView(curTx, otherTx, false);
            }
            stmt.result = queryOnView(stmt, view);
        } else if (stmt.type == StatementType.SELECT_SHARE || stmt.type == StatementType.SELECT_UPDATE) {
            if (curTx.isolationlevel == IsolationLevel.READ_UNCOMMITTED) {
                view = newView();
            } else {
                view = buildTxView(curTx, otherTx, false);
            }
            stmt.result = queryOnView(stmt, view);
        } else if (stmt.type == StatementType.UPDATE || stmt.type == StatementType.INSERT
                || stmt.type == StatementType.DELETE) {
            updateVersion(stmt, curTx, otherTx);
            if (stmt.type == StatementType.INSERT && stmt.newRowId > 0) {
                lock.lockObject.rowIds.add(stmt.newRowId);
            }
        } else {
            log.info("Weird Statement: {}", stmt);
        }
        return false;
    }

    private ArrayList<StatementCell> inferOracleOrderMVCC(ArrayList<StatementCell> schedule) {
        TableTool.recoverOriginalTable();
        isDeadlock = false;
        ArrayList<StatementCell> oracleOrder = new ArrayList<>();
        tx1.clearStates();
        tx2.clearStates();
        vData = TableTool.initVersionData();
        for (StatementCell stmt : schedule) {
            Transaction curTx = stmt.tx;
            Transaction otherTx = curTx == tx1 ? tx2 : tx1;
            if (curTx.blocked) {
                curTx.blockedStatements.add(stmt);
            } else if (otherTx.finished || stmt.type == StatementType.BEGIN) {
                oracleOrder.add(stmt);
            } else if (stmt.type == StatementType.COMMIT || stmt.type == StatementType.ROLLBACK) {
                if (stmt.type == StatementType.COMMIT) {
                    curTx.committed = true;
                } else {
                    deleteVersion(curTx);
                }
                oracleOrder.add(stmt);
                curTx.finished = true;
                curTx.locks.clear();
                if (otherTx.blocked) {
                    oracleOrder.addAll(otherTx.blockedStatements);
                    otherTx.blocked = false;
                    otherTx.blockedStatements.clear();
                }
            } else {
                stmt.view = buildTxView(curTx, otherTx, false);
                Lock lock = TableTool.getLock(stmt);
                if (lock.isConflict(otherTx)) {
                    curTx.blocked = true;
                    StatementCell blockPoint = stmt.copy();
                    blockPoint.blocked = true;
                    oracleOrder.add(blockPoint);
                    curTx.blockedStatements.add(stmt);
                } else {
                    oracleOrder.add(stmt);
                    if (stmt.type == StatementType.UPDATE || stmt.type == StatementType.DELETE
                            || stmt.type == StatementType.INSERT) {
                        updateVersion(stmt, curTx, otherTx);
                    }
                    if (lock.type != LockType.NONE) {
                        if (stmt.type == StatementType.INSERT && stmt.newRowId > 0) {
                            lock.lockObject.rowIds.add(stmt.newRowId);
                        }
                        curTx.locks.add(lock);
                    }
                }
            }
            if (curTx.blocked && otherTx.blocked) {
                isDeadlock = true;
                tx1.clearStates();
                tx2.clearStates();
                return oracleOrder;
            }
        }
        tx1.clearStates();
        tx2.clearStates();
        return oracleOrder;
    }

    private TxnPairResult obtainOracleResults(ArrayList<StatementCell> oracleOrder) {
        TableTool.recoverOriginalTable();
        vData = TableTool.initVersionData();
        tx1.clearStates();
        tx2.clearStates();
        for (StatementCell stmt : oracleOrder) {
            Transaction curTx = stmt.tx;
            Transaction otherTx = curTx == tx1 ? tx2 : tx1;
            if (curTx.snapTxs.isEmpty() && isSnapshotPoint(stmt)) {
                curTx.snapTxs.addAll(Arrays.asList(TableTool.txInit, curTx));
                if (otherTx.committed) {
                    curTx.snapTxs.add(otherTx);
                }
                curTx.snapView = snapshotView(curTx);
            }
            if (stmt.blocked || stmt.type == StatementType.BEGIN) {
                continue;
            }
            if (stmt.type == StatementType.COMMIT) {
                curTx.committed = true;
            }
            if (stmt.type == StatementType.ROLLBACK) {
                deleteVersion(curTx);
            }
            View view;
            if (stmt.type == StatementType.SELECT) {
                if (curTx.isolationlevel == IsolationLevel.REPEATABLE_READ) {
                    view = snapshotView(curTx);
                } else if (curTx.isolationlevel == IsolationLevel.READ_UNCOMMITTED) {
                    view = newView();
                } else {
                    view = buildTxView(curTx, otherTx, false);
                }
                stmt.result = queryOnView(stmt, view);
            }
            if (stmt.type == StatementType.SELECT_SHARE || stmt.type == StatementType.SELECT_UPDATE) {
                view = buildTxView(curTx, otherTx, false);
                stmt.result = queryOnView(stmt, view);
            }
            if (stmt.type == StatementType.UPDATE || stmt.type == StatementType.INSERT
                    || stmt.type == StatementType.DELETE) {
                updateVersion(stmt, curTx, otherTx);
            }
        }
        TableTool.viewToTable(newestView());
        ArrayList<Object> finalState = TableTool.getFinalStateAsList();
        TxnPairResult result = new TxnPairResult();
        result.setOrder(oracleOrder);
        result.setFinalState(finalState);
        result.setDeadBlock(isDeadlock);
        return result;
    }

    boolean isSnapshotPoint(StatementCell stmt) {
        switch (TableTool.dbms) {
            case MYSQL:
            case MARIADB:
                return stmt.type == StatementType.SELECT;
            case TIDB:
                return stmt.type == StatementType.BEGIN;
            default:
                throw new RuntimeException("Unexpected switch case: " + TableTool.dbms.name());
        }
    }

    void deleteVersion(Transaction curTx) {
        for (int rowId : vData.keySet()) {
            vData.get(rowId).removeIf(version -> version.tx == curTx);
        }
    }

    View newestView() {
        View view = new View();
        for (int rowid : vData.keySet()) {
            ArrayList<Version> versions = vData.get(rowid);
            if (versions == null || versions.isEmpty()) {
                continue;
            }
            Version latest = versions.get(versions.size() - 1);
            if (!latest.deleted) {
                view.data.put(rowid, latest.data);
            }
        }
        return view;
    }

    View snapshotView(Transaction curTx) {
        View view = new View();
        for (int rowId : vData.keySet()) {
            ArrayList<Version> versions = vData.get(rowId);
            for (int i = versions.size() - 1; i >= 0; i--) {
                Version version = versions.get(i);
                if (curTx.snapTxs.contains(version.tx)) {
                    if (!version.deleted) {
                        view.data.put(rowId, version.data);
                    }
                    break;
                }
            }
        }
        return view;
    }

    View newView() {
        View view = new View();
        for (int rowId : vData.keySet()) {
            ArrayList<Version> versions = vData.get(rowId);
            Version version = versions.get(versions.size()-1);
            if (!version.deleted) {
                view.data.put(rowId, version.data);
            }
        }
        return view;
    }

    View buildTxView(Transaction curTx, Transaction otherTx, boolean useDel) {
        ArrayList<Transaction> readTxs = new ArrayList<>(Arrays.asList(TableTool.txInit, curTx));
        if (otherTx.committed) {
            readTxs.add(otherTx);
        }
        View view = new View(useDel);
        for (int rowId : vData.keySet()) {
            ArrayList<Version> versions = vData.get(rowId);
            for (int i = versions.size() - 1; i >= 0; i--) {
                Version version = versions.get(i);
                if (readTxs.contains(version.tx)) {
                    if (!version.deleted) {
                        view.data.put(rowId, version.data);
                        if (useDel) {
                            view.deleted.put(rowId, false);
                        }
                    } else if (curTx.snapView.data.containsKey(rowId) && version.tx != curTx && useDel) {
                        view.data.put(rowId, curTx.snapView.data.get(rowId));
                        view.deleted.put(rowId, true);
                    }
                    break;
                }
            }
        }
        return view;
    }

    void updateVersion(StatementCell stmt, Transaction curTx, Transaction otherTx) {
        View curView = buildTxView(curTx, otherTx, false);
        View allView = curView;
        if (curTx.isolationlevel == IsolationLevel.REPEATABLE_READ) {
            allView = buildTxView(curTx, otherTx, true);
        }
        HashSet<Integer> rowIds = getAffectedRows(stmt, allView);
        String snapshotName = "update_version";
        TableTool.takeSnapshotForTable(snapshotName);
        TableTool.viewToTable(curView);
        boolean success = TableTool.executeOnTable(stmt.statement);
        int newRowId = TableTool.fillOneRowId();
        View newView = TableTool.tableToView();
        if (success) {
            if (stmt.type == StatementType.INSERT) {
                assert newRowId > 0;
                rowIds.add(newRowId);
                stmt.newRowId = newRowId;
            }
            for (int rowId : rowIds) {
                boolean deleted = allView.deleted != null && allView.deleted.containsKey(rowId)
                        && allView.deleted.get(rowId) || stmt.type == StatementType.DELETE;
                Object[] data;
                if (deleted) {
                    data = allView.data.get(rowId);
                } else {
                    data = newView.data.get(rowId);
                }
                if (!vData.containsKey(rowId)) {
                    vData.put(rowId, new ArrayList<>());
                }
                vData.get(rowId).add(new Version(data.clone(), curTx, deleted));
            }
        }
        TableTool.recoverTableFromSnapshot(snapshotName);
    }

    HashSet<Integer> getAffectedRows(StatementCell stmt, View view) {
        HashSet<Integer> res = new HashSet<>();
        String snapshotName = "affected_rows";
        TableTool.takeSnapshotForTable(snapshotName);
        TableTool.viewToTable(view);
        if (stmt.type == StatementType.DELETE || stmt.type == StatementType.UPDATE) {
            res.addAll(TableTool.getRowIdsFromWhere(stmt.whereClause));
        }
        if (stmt.type == StatementType.INSERT || stmt.type == StatementType.UPDATE) {
            HashSet<String> indexObjs = TableTool.getIndexObjs(stmt.values);
            String query = "SELECT * FROM " + TableTool.TableName;
            TableTool.executeQueryWithCallback(query, rs -> {
                try {
                    while (rs.next()) {
                        HashMap<String, String> rowValues = new HashMap<>();
                        for (String colName : TableTool.colNames) {
                            Object obj = rs.getObject(colName);
                            if (obj != null) {
                                if (obj instanceof byte[]) {
                                    rowValues.put(colName, TableTool.byteArrToHexStr((byte[]) obj));
                                } else {
                                    rowValues.put(colName, obj.toString());
                                }
                            }
                        }
                        HashSet<String> rowIndexObjs = TableTool.getIndexObjs(rowValues);
                        for (String indexObj : rowIndexObjs) {
                            if (indexObjs.contains(indexObj)) {
                                res.add(rs.getInt(TableTool.RowIdColName));
                                break;
                            }
                        }
                    }
                } catch (SQLException e) {
                    throw new RuntimeException("Get lock object failed: ", e);
                }
            });
        }
        TableTool.recoverTableFromSnapshot(snapshotName);
        return res;
    }


    ArrayList<Object> queryOnView(StatementCell stmt, View view) {
        TableTool.backupCurTable();
        TableTool.viewToTable(view);
        ArrayList<Object> res = TableTool.getQueryResultAsList(stmt.statement);
        TableTool.recoverCurTable();
        return res;
    }

    private boolean compareOracles(TxnPairResult execRes, TxnPairResult oracleRes) {
        log.info("txp: {}, all case: {}, skip: {}", TableTool.txPair, TableTool.allCase, TableTool.skipCase);
        ArrayList<StatementCell> execOrder = execRes.getOrder();
        ArrayList<StatementCell> oracleOrder = oracleRes.getOrder();
        int minLen = Math.min(execOrder.size(), oracleOrder.size());
        if (execRes.isDeadBlock() && !oracleRes.isDeadBlock()) {
            log.info("Ignore: Undecided");
            bugInfo += " -- Ignore: Undecided";
            TableTool.skipCase++;
            return true;
        }
        for (int i = 0; i < minLen; i++) {
            StatementCell oStmt = oracleOrder.get(i);
            StatementCell eStmt = execOrder.get(i);
            if (oStmt.aborted && eStmt.aborted) continue;
            if (oStmt.aborted) {
                log.info("Error: Missing abort");
                bugInfo += " -- Error: Missing abort";
                return false;
            }
            if (eStmt.aborted) {
                if (shouldNotAbort(eStmt)) {
                    log.info("Error: Unnecessary abort");
                    bugInfo += " -- Error: Unnecessary abort";
                    return false;
                } else {
                    log.info("Ignore: Undecided (because abort)");
                    bugInfo += " -- Ignore: Undecided (because abort)";
                    return true;
                }
            }
            if (!oStmt.blocked && !eStmt.blocked) {
                if (oStmt.type == StatementType.SELECT && oStmt.equals(eStmt)) {
                    if (!compareResultSets(oStmt.result, eStmt.result)) {
                        log.info("Error: Inconsistent query result");
                        log.info("query: " + oStmt.statement);
                        bugInfo += " -- Error: Inconsistent query result \n";
                        bugInfo += " -- query: " + oStmt.statement;
                        return false;
                    }
                }
            }
            if (oStmt.blocked && !eStmt.blocked) {
                log.info("Error: Missing lock");
                bugInfo += " -- Error: Missing lock";
                TableTool.skipCase++;
                return false;
            }
            if (!oStmt.blocked && eStmt.blocked) {
                if (shouldNotBlock(eStmt)) {
                    log.info("Error: Unnecessary lock");
                    bugInfo += " -- Error: Unnecessary lock";
                    return false;
                } else {
                    log.info("Ignore: Undecided (because lock)");
                    bugInfo += " -- Ignore: Undecided (because lock)";
                    return true;
                }
            }
        }
        if (!execRes.isDeadBlock() && !oracleRes.isDeadBlock()) {
            if (!compareResultSets(execRes.getFinalState(), oracleRes.getFinalState())) {
                log.info("Error: Inconsistent final database state");
                bugInfo += " -- Error: Inconsistent final database state";
                return false;
            }
        }
        return true;
    }

    private boolean shouldNotBlock(StatementCell stmt) {
        return false;
    }
    private boolean shouldNotAbort(StatementCell stmt) {
        return false;
    }

    private boolean compareResultSets(ArrayList<Object> resultSet1, ArrayList<Object> resultSet2) {
        if (resultSet1 == null && resultSet2 == null) {
            return true;
        } else if (resultSet1 == null || resultSet2 == null) {
            bugInfo += " -- One result is NULL\n";
            return false;
        }
        if (resultSet1.size() != resultSet2.size()) {
            bugInfo += " -- Number Of Data Different\n";
            return false;
        }
        List<String> rs1 = preprocessResultSet(resultSet1);
        List<String> rs2 = preprocessResultSet(resultSet2);
        for (int i = 0; i < rs1.size(); i++) {
            String result1 = rs1.get(i);
            String result2 = rs2.get(i);
            if (result1 == null && result2 == null) {
                continue;
            }
            if (result1 == null || result2 == null) {
                bugInfo += " -- (" + i + ") Values Different [" + result1 + ", " + result2 + "]\n";
                return false;
            }
            if (!result1.equals(result2)) {
                bugInfo += " -- (" + i + ") Values Different [" + result1 + ", " + result2 + "]\n";
                return false;
            }
        }
        return true;
    }

    private static List<String> preprocessResultSet(ArrayList<Object> resultSet) {
        return resultSet.stream().map(o -> {
            if (o == null) {
                return "[NULL]";
            } else {
                return o.toString();
            }
        }).sorted().collect(Collectors.toList());
    }
}
