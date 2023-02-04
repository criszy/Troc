package troc;

import troc.common.Table;
import troc.mysql.MySQLTable;

public enum DBMS {
    MYSQL("mysql"), MARIADB("mysql"), TIDB("mysql");

    private final String protocol;

    DBMS(String protocol) {
        this.protocol = protocol;
    }

    public String getProtocol() {
        return protocol;
    }

    public Table buildTable(String tableName) {
        switch (TableTool.dbms) {
            case MYSQL:
            case MARIADB:
            case TIDB:
                return new MySQLTable(tableName);
            default:
                throw new IllegalStateException("Unexpected value: " + TableTool.dbms);
        }
    }
}
