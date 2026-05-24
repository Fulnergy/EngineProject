package edu.sustech.cs307.optimizer;

import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.LogicalFilterOperator;
import edu.sustech.cs307.logicalOperator.LogicalInsertOperator;
import edu.sustech.cs307.logicalOperator.LogicalJoinOperator;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.logicalOperator.LogicalProjectOperator;
import edu.sustech.cs307.logicalOperator.LogicalTableScanOperator;
import edu.sustech.cs307.logicalOperator.LogicalUpdateOperator;
import edu.sustech.cs307.logicalOperator.ddl.CreateTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ExplainExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ShowDatabaseExecutor;
import edu.sustech.cs307.system.DBManager;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.parser.JSqlParser;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

public class LogicalPlanner {
    private static final Pattern BEGIN_PATTERN = Pattern.compile("(?i)^BEGIN(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern START_TRANSACTION_PATTERN = Pattern.compile("(?i)^START\\s+TRANSACTION$");
    private static final Pattern SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^SAVEPOINT\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("(?i)^ROLLBACK$");
    private static final Pattern ROLLBACK_TO_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^ROLLBACK\\s+TO\\s+SAVEPOINT\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern RELEASE_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^RELEASE(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    // CREATE INDEX idx_name ON table_name(column_name)
    private static final Pattern CREATE_INDEX_PATTERN =
            Pattern.compile("(?i)^CREATE\\s+INDEX\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)$");
    // DROP INDEX idx_name
    private static final Pattern DROP_INDEX_PATTERN =
            Pattern.compile("(?i)^DROP\\s+INDEX\\s+(\\w+)$");

    public static LogicalOperator resolveAndPlan(DBManager dbManager, String sql) throws DBException {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        if (handleManualTransactionCommand(dbManager, sql)) {
            return null;
        }
        JSqlParser parser = new CCJSqlParserManager();
        Statement stmt = null;
        try {
            stmt = parser.parse(new StringReader(sql));
        } catch (JSQLParserException e) {
            throw new DBException(ExceptionTypes.InvalidSQL(sql, e.getMessage()));
        }
        LogicalOperator operator = null;
        // Query
        if (stmt instanceof Select selectStmt) {
            operator = handleSelect(dbManager, selectStmt);
        } else if (stmt instanceof Insert insertStmt) {
            operator = handleInsert(dbManager, insertStmt);
        } else if (stmt instanceof Update updateStmt) {
            operator = handleUpdate(dbManager, updateStmt);
        }else if (stmt instanceof Commit) {
            dbManager.commitTransaction();
            return null;
        }
        //todo: add condition of handleDelete
        // functional
        else if (stmt instanceof CreateTable createTableStmt) {
            CreateTableExecutor createTable = new CreateTableExecutor(createTableStmt, dbManager, sql);
            createTable.execute();
            return null;
        } else if (stmt instanceof ExplainStatement explainStatement) {
            ExplainExecutor explainExecutor = new ExplainExecutor(explainStatement, dbManager);
            explainExecutor.execute();
            return null;
        } else if (stmt instanceof ShowStatement showStatement) {
            ShowDatabaseExecutor showDatabaseExecutor = new ShowDatabaseExecutor(showStatement);
            showDatabaseExecutor.execute();
            return null;
        } else {
            throw new DBException(ExceptionTypes.UnsupportedCommand((stmt.toString())));
        }
        return operator;
    }


    public static LogicalOperator handleSelect(DBManager dbManager, Select selectStmt) throws DBException {
        PlainSelect plainSelect = selectStmt.getPlainSelect();
        if (plainSelect.getFromItem() == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand((plainSelect.toString())));
        }
        LogicalOperator root = new LogicalTableScanOperator(plainSelect.getFromItem().toString(), dbManager);

        int depth = 0;
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                root = new LogicalJoinOperator(
                        root,
                        new LogicalTableScanOperator(join.getRightItem().toString(), dbManager),
                        join.getOnExpressions(),
                        depth);
                depth += 1;
            }
        }

        // 在 Join 之后应用 Filter，Filter 的输入是 Join 的结果 (root)
        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }
        root = new LogicalProjectOperator(root, plainSelect.getSelectItems());
        return root;
    }

    private static LogicalOperator handleInsert(DBManager dbManager, Insert insertStmt) {
        return new LogicalInsertOperator(insertStmt.getTable().getName(), insertStmt.getColumns(),
                insertStmt.getValues());
    }

    private static LogicalOperator handleUpdate(DBManager dbManager, Update updateStmt) throws DBException {
        LogicalOperator root = new LogicalTableScanOperator(updateStmt.getTable().getName(), dbManager);
        return new LogicalUpdateOperator(root, updateStmt.getTable().getName(), updateStmt.getUpdateSets(),
                updateStmt.getWhere());
    }
    private static String normalizeSql(String sql) {
        String normalizedSql = sql == null ? "" : sql.trim();
        while (normalizedSql.endsWith(";")) {
            normalizedSql = normalizedSql.substring(0, normalizedSql.length() - 1).trim();
        }
        return normalizedSql;
    }

    private static boolean handleManualTransactionCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        Matcher m;
        // BEGIN / START TRANSACTION
        if (BEGIN_PATTERN.matcher(normalizedSql).matches() || START_TRANSACTION_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.beginTransaction();
            return true;
        }
        // ROLLBACK (standalone, 回滚整个事务)
        if (ROLLBACK_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.getTransactionManager().rollback();
            return true;
        }
        // SAVEPOINT <name>
        m = SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (m.matches()) {
            dbManager.getTransactionManager().savepoint(m.group(1));
            return true;
        }
        // ROLLBACK TO SAVEPOINT <name>
        m = ROLLBACK_TO_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (m.matches()) {
            dbManager.getTransactionManager().rollbackToSavepoint(m.group(1));
            return true;
        }
        // RELEASE SAVEPOINT <name>
        m = RELEASE_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (m.matches()) {
            dbManager.getTransactionManager().releaseSavepoint(m.group(1));
            return true;
        }
        // CREATE INDEX idx_name ON table_name(column_name)
        m = CREATE_INDEX_PATTERN.matcher(normalizedSql);
        if (m.matches()) {
            String indexName = m.group(1);
            String tableName = m.group(2);
            String columnName = m.group(3);
            dbManager.createIndex(tableName, columnName, indexName);
            return true;
        }
        // DROP INDEX idx_name [ON table_name]
        m = DROP_INDEX_PATTERN.matcher(normalizedSql);
        if (m.matches()) {
            String indexName = m.group(1);
            // 遍历所有表找到这个索引名并删除
            for (String tableName : dbManager.getMetaManager().getTableNames()) {
                try {
                    var tableMeta = dbManager.getMetaManager().getTable(tableName);
                    if (tableMeta.getIndexes() != null && tableMeta.getIndexes().containsKey(indexName)) {
                        dbManager.dropIndex(tableName, indexName);
                        break;
                    }
                } catch (DBException ignored) {}
            }
            return true;
        }
        return false;
    }


}
