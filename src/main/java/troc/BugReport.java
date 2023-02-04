package troc;

import lombok.Data;

import java.util.List;

@Data
public class BugReport {
    private boolean bugFound = false;

    private String createTableSQL;
    private List<String> initializeStatements;
    private String initialTable;
    private Transaction tx1, tx2;
    private String inputSchedule;
    private String submittedOrder;
    private TxnPairResult execRes;
    private TxnPairResult inferredRes;


    public String toString() {
        StringBuilder sb = new StringBuilder("=============================");
        sb.append("BUG REPORT\n")
                .append(" -- Create Table SQL: ").append(createTableSQL).append("\n")
                .append(" -- InitializeStatements:").append("\n");
        for (String stmt : initializeStatements) {
            sb.append("\t").append(stmt).append(";\n");
        }
        sb.append(" -- Initial Table: \n").append(initialTable).append("\n");
        sb.append(" -- Tx1: ").append(tx1).append("\n");
        sb.append(" -- Tx2: ").append(tx2).append("\n");
        sb.append(" -- Input Schedule: ").append(inputSchedule).append("\n");
        sb.append(" -- Submitted Order: ").append(submittedOrder).append("\n");
        sb.append(" -- Execution Result: ").append(execRes).append("\n");
        sb.append(" -- Inferred Result: ").append(inferredRes).append("\n");
        return sb.toString();
    }
}
