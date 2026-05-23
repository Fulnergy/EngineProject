package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.system.DBManager;
import net.sf.jsqlparser.statement.drop.Drop;
import org.pmw.tinylog.Logger;

public class DropTableExecutor implements DMLExecutor {

    private final Drop dropStmt;
    private final DBManager dbManager;
    private final String sql;

    public DropTableExecutor(Drop dropStmt, DBManager dbManager, String sql) {
        this.dropStmt = dropStmt;
        this.dbManager = dbManager;
        this.sql = sql;
    }

    @Override
    public void execute() throws DBException {
        String dropType = dropStmt.getType();
        if (!dropType.equalsIgnoreCase("TABLE")) {
            throw new DBException(ExceptionTypes.UnsupportedCommand(sql));
        }
        String tableName = dropStmt.getName().getName();
        dbManager.dropTable(tableName);
        Logger.info("Successfully dropped table: {}", tableName);
    }
}
