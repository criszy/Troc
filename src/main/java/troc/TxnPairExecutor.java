package troc;

import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;

@Slf4j
public class TxnPairExecutor {

    private final ArrayList<StatementCell> submittedOrder;
    private final Transaction tx1;
    private final Transaction tx2;

    private TxnPairResult result;
    private ArrayList<StatementCell> actualSchedule;
    private ArrayList<Object> finalState;

    private boolean isDeadLock = false;
    private boolean timeout = false;
    private String exceptionMessage = "";
    private final Map<Integer, Boolean> txnAbort = new HashMap<>();

    public TxnPairExecutor(ArrayList<StatementCell> schedule, Transaction tx1, Transaction tx2) {
        this.submittedOrder = schedule;
        this.tx1 = tx1;
        this.tx2 = tx2;
    }

    TxnPairResult getResult() {
        if (result == null) {
            execute();
            result = new TxnPairResult(actualSchedule, finalState, isDeadLock);
        }
        return result;
    }

    private void execute() {
        TableTool.setIsolationLevel(tx1);
        TableTool.setIsolationLevel(tx2);
        actualSchedule = new ArrayList<>();
        txnAbort.put(1, false);
        txnAbort.put(2, false);
        BlockingQueue<StatementCell> queue1 = new SynchronousQueue<>();
        BlockingQueue<StatementCell> queue2 = new SynchronousQueue<>();
        BlockingQueue<StatementCell> communicationID = new SynchronousQueue<>();
        Thread producer = new Thread(new Producer(queue1, queue2, submittedOrder, communicationID));
        Thread consumer1 = new Thread(new Consumer(1, queue1, communicationID, submittedOrder.size()));
        Thread consumer2 = new Thread(new Consumer(2, queue2, communicationID, submittedOrder.size()));
        producer.start();
        consumer1.start();
        consumer2.start();
        try {
            producer.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        finalState = TableTool.getQueryResultAsList("SELECT * FROM " + TableTool.TableName);
    }

    class Producer implements Runnable {
        private final BlockingQueue<StatementCell> queue1;
        private final BlockingQueue<StatementCell> queue2;
        private final ArrayList<StatementCell> schedule;
        private final BlockingQueue<StatementCell> communicationID; // represent the execution feedback

        public Producer(BlockingQueue<StatementCell> queue1, BlockingQueue<StatementCell> queue2,
                ArrayList<StatementCell> schedule, BlockingQueue<StatementCell> communicationID) {
            this.queue1 = queue1;
            this.queue2 = queue2;
            this.schedule = schedule;
            this.communicationID = communicationID;
        }

        public void run() {
            Map<Integer, Boolean> txnBlock = new HashMap<>(); // whether a child thread is blocked
            txnBlock.put(1, false); // txn 1 is false
            txnBlock.put(2, false);
            Map<Integer, ArrayList<StatementCell>> blockedStmts = new HashMap<>(); // record blocked statements
            blockedStmts.put(1, null);
            blockedStmts.put(2, null);
            Map<Integer, BlockingQueue<StatementCell>> queues = new HashMap<>(); // record queues
            queues.put(1, queue1);
            queues.put(2, queue2);
            int queryID; // statement ID
            long startTs; // record time threshold
            timeout = false;
            isDeadLock = false;
            out:
            for (queryID = 0; queryID < schedule.size(); queryID++) {
                int txn = schedule.get(queryID).tx.txId;
                StatementCell statementCell = schedule.get(queryID).copy();
                int otherTxn = (txn == 1) ? 2 : 1;
                if (txnBlock.get(txn)) { // if a child thread is blocked
                    ArrayList<StatementCell> stmts = blockedStmts.get(txn);
                    stmts.add(statementCell);
                    blockedStmts.put(txn, stmts); // save its statements
                    continue;
                }
                try {
                    queues.get(txn).put(statementCell);
                } catch (InterruptedException e) {
                    log.info(" -- MainThread run exception");
                    log.info("Query: " + statementCell.statement);
                    log.info("Interrupted Exception: " + e.getMessage());
                }
                StatementCell queryReturn = communicationID.poll(); // communicate with a child thread
                startTs = System.currentTimeMillis();
                while (queryReturn == null) { // wait for 2s
                    if (System.currentTimeMillis() - startTs > 2000) { // child thread is blocked
                        log.info(txn + "-" + statementCell.statementId + ": time out");
                        txnBlock.put(txn, true); // record blocked transaction
                        StatementCell blockPoint = statementCell.copy();
                        blockPoint.blocked = true;
                        actualSchedule.add(blockPoint);
                        ArrayList<StatementCell> stmts = new ArrayList<>();
                        stmts.add(statementCell);
                        blockedStmts.put(txn, stmts);
                        break;
                    }
                    queryReturn = communicationID.poll();
                }
                if (queryReturn != null) { // success to receive feedback
                    if ((statementCell.type == StatementType.COMMIT || statementCell.type == StatementType.ROLLBACK)
                            && txnBlock.get(otherTxn)) {
                        StatementCell nextReturn = communicationID.poll();
                        while (nextReturn == null) {
                            if (System.currentTimeMillis() - startTs > 15000) { // child thread is blocked
                                log.info(" -- " + txn + "." + statementCell.statementId + ": time out");
                                timeout = true;
                                break;
                            }
                            nextReturn = communicationID.poll();
                        }
                        if (nextReturn != null) {
                            if (queryReturn.statement.equals(statementCell.statement)) {
                                statementCell.result = queryReturn.result;
                                blockedStmts.get(otherTxn).get(0).result = nextReturn.result;
                            } else {
                                statementCell.result = nextReturn.result;
                                blockedStmts.get(otherTxn).get(0).result = queryReturn.result;
                            }
                            actualSchedule.add(statementCell);
                            actualSchedule.add(blockedStmts.get(otherTxn).get(0));
                        } else {
                            log.info(" -- next return failed: " + statementCell.statement);
                            break;
                        }
                    } else if (queryReturn.statement.equals(statementCell.statement)) {
                        statementCell.result = queryReturn.result;
                        actualSchedule.add(statementCell);
                    } else {
                        isDeadLock = true;
                        log.info(" -- DeadLock happened(1)");
                        statementCell.blocked = true;
                        actualSchedule.add(statementCell);
                        break out;
                    }

                }
                if ((statementCell.type == StatementType.COMMIT
                        || statementCell.type == StatementType.ROLLBACK) && !exceptionMessage.contains("Deadlock") && !exceptionMessage.contains("lock=true")
                        && !(txnBlock.get(1) && txnBlock.get(2))) {
                    txnBlock.put(otherTxn, false);
                    if (blockedStmts.get(otherTxn) != null) {
                        for (int j = 1; j < blockedStmts.get(otherTxn).size(); j++) {
                            StatementCell blockedStmtCell = blockedStmts.get(otherTxn).get(j);
                            try {
                                queues.get(otherTxn).put(blockedStmtCell);
                            } catch (InterruptedException e) {
                                log.info(" -- MainThread blocked exception");
                                log.info("Query: " + statementCell.statement);
                                log.info("Interrupted Exception: " + e.getMessage());
                            }
                            StatementCell blockedReturn = communicationID.poll();
                            startTs = System.currentTimeMillis();
                            while (blockedReturn == null) {
                                if (System.currentTimeMillis() - startTs > 10000) {
                                    log.info(" -- " + txn + "." + statementCell.statementId + ": still time out");
                                    timeout = true;
                                    break;
                                }
                                blockedReturn = communicationID.poll();
                            }
                            if (blockedReturn != null) {
                                blockedStmtCell.result = blockedReturn.result;
                                actualSchedule.add(blockedStmtCell);

                            }
                        }
                    }
                }
                if (exceptionMessage.length() > 0 || (txnBlock.get(1) && txnBlock.get(2)) || timeout) {
                    if (exceptionMessage.contains("Deadlock") || exceptionMessage.contains("lock=true")
                            || (txnBlock.get(1) && txnBlock.get(2))) { // deadlock
                        log.info(" -- DeadLock happened(2)");
                        isDeadLock = true;
                        break;
                    }
                    if (exceptionMessage.contains("restart") || exceptionMessage.contains("aborted")
                            || exceptionMessage.contains("TransactionRetry")) {
                        txnAbort.put(txn, true);
                        statementCell.aborted = true;
                    }
                }
            }
            if (isDeadLock) {
                try {
                    tx1.conn.createStatement().executeUpdate("ROLLBACK"); // stop transaction
                    tx2.conn.createStatement().executeUpdate("ROLLBACK");
                } catch (SQLException e) {
                    log.info(" -- Deadlock Commit Failed");
                }
                log.info(" -- schedule execute failed");
            }
            StatementCell stopThread1 = new StatementCell(tx1, schedule.size());
            StatementCell stopThread2 = new StatementCell(tx2, schedule.size());
            try {
                while (communicationID.poll() != null);
            } catch (Exception ignored) {}
            try {
                queue1.put(stopThread1);
                queue2.put(stopThread2);
            } catch (InterruptedException e) {
                log.info(" -- MainThread stop child thread Interrupted exception: " + e.getMessage());
            }
        }
    }

    class Consumer implements Runnable {
        private final BlockingQueue<StatementCell> queue;
        private final BlockingQueue<StatementCell> communicationID; // represent the execution feedback
        private final int scheduleCount;
        private final int consumerId;

        public Consumer(int consumerId, BlockingQueue<StatementCell> queue,
                        BlockingQueue<StatementCell> communicationID, int scheduleCount) {
            this.consumerId = consumerId;
            this.queue = queue;
            this.communicationID = communicationID;
            this.scheduleCount = scheduleCount;
        }

        public void run() {
            try {
                while (true) {
                    StatementCell stmt = queue.take(); // communicate with main thread
                    if (stmt.statementId >= scheduleCount) break; // stop condition: schedule.size()
                    // execute a query
                    String query = stmt.statement;
                    try {
                        if (stmt.type == StatementType.SELECT || stmt.type == StatementType.SELECT_SHARE
                                || stmt.type == StatementType.SELECT_UPDATE) {
                            stmt.result = TableTool.getQueryResultAsListWithException(stmt.tx.conn, query);
                        } else {
                            stmt.tx.conn.createStatement().executeUpdate(query);
                        }
                        exceptionMessage = "";
                    } catch (SQLException e) {
                        log.info(" -- TXNThread threadExec exception");
                        log.info("Query {}: {}", stmt, query);
                        exceptionMessage = e.getMessage();
                        log.info("SQL Exception: " + exceptionMessage);
                        exceptionMessage = exceptionMessage + "; [Query] " + query;
                    } finally {
                        try {
                            communicationID.put(stmt); // communicate to main thread
                        } catch (InterruptedException e) { // communicationID.put()
                            log.info(" -- TXNThread threadExec exception");
                            log.info("Query {}: {}", stmt, query);
                            log.info("Interrupted Exception: " + e.getMessage());
                        }
                    }
                }
            } catch (InterruptedException e) {
                // thread stop
                log.info(" -- TXNThread run Interrupted exception: " + e.getMessage());
            }
        }
    }
}
