package edu.sustech.cs307.logicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.TabCol;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;

import java.util.Collections;
import java.util.List;

public class LogicalAggregateOperator extends LogicalOperator {

    private final LogicalOperator child;

    private final String aggregateFunction;

    private final boolean isStar;

    private final String aggregateColumnName;

    private final String outputColumnName;

    private final List<TabCol> groupByColumns;

    public LogicalAggregateOperator(LogicalOperator child, String aggregateFunction,
                                    boolean isStar, String aggregateColumnName,
                                    String outputColumnName) {
        this(child, aggregateFunction, isStar, aggregateColumnName, outputColumnName, Collections.emptyList());
    }

    public LogicalAggregateOperator(LogicalOperator child, String aggregateFunction,
                                    boolean isStar, String aggregateColumnName,
                                    String outputColumnName, List<TabCol> groupByColumns) {
        super(Collections.singletonList(child));
        this.child = child;
        this.aggregateFunction = aggregateFunction;
        this.isStar = isStar;
        this.aggregateColumnName = aggregateColumnName;
        this.outputColumnName = outputColumnName;
        this.groupByColumns = groupByColumns;
    }

    public LogicalAggregateOperator(LogicalOperator child, Function function) throws DBException {
        super(Collections.singletonList(child));
        this.child = child;
        this.groupByColumns = Collections.emptyList();

        String functionName = function.getName().toUpperCase();
        this.aggregateFunction = functionName;

        if (!"COUNT".equals(functionName)) {
            throw new DBException(ExceptionTypes.UnsupportedCommand(
                    "Aggregate function: " + functionName));
        }

        boolean star = false;
        String colName = null;
        String outName = "count";

        var params = function.getParameters();
        if (params != null && !params.getExpressions().isEmpty()) {
            Expression paramExpr = params.getExpressions().get(0);
            if (paramExpr instanceof AllColumns) {
                star = true;
                outName = "count(*)";
            } else if (paramExpr instanceof Column col) {
                star = false;
                colName = col.getColumnName();
                outName = "count(" + colName + ")";
            }
        } else {
            star = true;
            outName = "count(*)";
        }

        this.isStar = star;
        this.aggregateColumnName = colName;
        this.outputColumnName = outName;
    }

    public LogicalOperator getChild() {
        return child;
    }

    public String getAggregateFunction() {
        return aggregateFunction;
    }

    public boolean isStar() {
        return isStar;
    }

    public String getAggregateColumnName() {
        return aggregateColumnName;
    }

    public String getOutputColumnName() {
        return outputColumnName;
    }

    public List<TabCol> getGroupByColumns() {
        return groupByColumns;
    }

    public boolean hasGroupBy() {
        return groupByColumns != null && !groupByColumns.isEmpty();
    }


    public static LogicalAggregateOperator createFromFunction(LogicalOperator child, Function function)
            throws DBException {
        String functionName = function.getName().toUpperCase();

        // 未来扩展：校验支持的函数名
        if (!"COUNT".equals(functionName)) {
            throw new DBException(ExceptionTypes.UnsupportedCommand(
                    "Aggregate function: " + functionName));
        }

        boolean isStar = false;
        String aggregateColumnName = null;
        String outputColumnName = "count";

        var params = function.getParameters();
        if (params != null && !params.getExpressions().isEmpty()) {
            Expression paramExpr = params.getExpressions().get(0);
            if (paramExpr instanceof AllColumns) {
                isStar = true;
                outputColumnName = "count(*)";
            } else if (paramExpr instanceof Column col) {
                isStar = false;
                aggregateColumnName = col.getColumnName();
                outputColumnName = "count(" + aggregateColumnName + ")";
            }
        } else {
            isStar = true;
            outputColumnName = "count(*)";
        }

        return new LogicalAggregateOperator(child, functionName, isStar,
                aggregateColumnName, outputColumnName);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String funcStr = isStar
                ? aggregateFunction + "(*)"
                : aggregateFunction + "(" + aggregateColumnName + ")";
        String nodeHeader = "AggregateOperator(function=" + funcStr
                + ", output=" + outputColumnName + ")";
        if (hasGroupBy()) {
            nodeHeader += " [GROUP BY " + groupByColumns + "]";
        }
        String[] childLines = child.toString().split("\\R");

        sb.append(nodeHeader);
        if (childLines.length > 0) {
            sb.append("\n└── ").append(childLines[0]);
            for (int i = 1; i < childLines.length; i++) {
                sb.append("\n    ").append(childLines[i]);
            }
        }
        return sb.toString();
    }
}
