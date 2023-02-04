package troc;

import com.beust.jcommander.Parameter;
import lombok.Data;

@Data
public class Options {

    @Parameter(names = { "--dbms" }, description = "Specifies the target DBMS")
    private String DBMS = "mysql";

    @Parameter(names = { "--set-case" }, description = "Whether use a specified case")
    private boolean setCase = false;

    @Parameter(names = { "--case-file" }, description = "Specifies the input file of the specified case")
    private String caseFile = "";

    @Parameter(names = { "--db" }, description = "Specifies the test database")
    private String dbName = "test";

    @Parameter(names = { "--table" }, description = "Specifies the test table")
    private String tableName = "troc";

    @Parameter(names = "--username", description = "The user name used to log into the DBMS")
    private String userName = "root";

    @Parameter(names = "--password", description = "The password used to log into the DBMS")
    private String password = "";

    @Parameter(names = "--host", description = "The host used to log into the DBMS")
    private String host = "127.0.0.1";

    @Parameter(names = "--port", description = "The port used to log into the DBMS")
    private int port = 3306;
}
