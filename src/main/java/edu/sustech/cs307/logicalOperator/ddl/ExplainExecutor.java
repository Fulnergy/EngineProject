package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.exception.DBException;

import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statement;
import org.pmw.tinylog.Logger;

public class ExplainExecutor implements DMLExecutor {

    private final ExplainStatement explainStatement;
    private final DBManager dbManager;

    public ExplainExecutor(ExplainStatement explainStatement, DBManager dbManager) {
        this.explainStatement = explainStatement;
        this.dbManager = dbManager;
    }

    @Override
    public void execute() throws DBException {
        Statement innerStmt = explainStatement.getStatement();


        LogicalOperator logicalOp = LogicalPlanner.resolveAndPlan(dbManager, innerStmt.toString());

        if (logicalOp != null) {
            Logger.info(logicalOp.toString());
        }
    }
}
