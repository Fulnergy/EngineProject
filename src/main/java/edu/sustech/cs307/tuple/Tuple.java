package edu.sustech.cs307.tuple;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.PlainSelect;

public abstract class Tuple {
    /** 用于执行 EXISTS 子查询，由 DBEntry 初始化时设置 */
    protected static edu.sustech.cs307.system.DBManager dbManager;

    public static void setDBManager(edu.sustech.cs307.system.DBManager dbm) {
        dbManager = dbm;
    }

    public abstract Value getValue(TabCol tabCol) throws DBException;

    public abstract TabCol[] getTupleSchema();

    public abstract Value[] getValues() throws DBException;

    public boolean eval_expr(Expression expr) throws DBException {
        return evaluateCondition(this, expr);
    }

    private boolean evaluateCondition(Tuple tuple, Expression whereExpr) {
        if (whereExpr instanceof AndExpression andExpr) {
            // Recursively evaluate left and right expressions
            return evaluateCondition(tuple, andExpr.getLeftExpression())
                    && evaluateCondition(tuple, andExpr.getRightExpression());
        } else if (whereExpr instanceof OrExpression orExpr) {
            return evaluateCondition(tuple, orExpr.getLeftExpression())
                    || evaluateCondition(tuple, orExpr.getRightExpression());
        } else if (whereExpr instanceof BinaryExpression binaryExpression) {
            return evaluateBinaryExpression(tuple, binaryExpression);
        } else if (whereExpr instanceof InExpression inExpr) {
            return evaluateInExpression(tuple, inExpr);
        } else if (whereExpr instanceof ExistsExpression existsExpr) {
            return evaluateExistsExpression(tuple, existsExpr);
        } else {
            return true; // For non-binary and non-AND expressions, just return true for now
        }
    }

    private boolean evaluateBinaryExpression(Tuple tuple, BinaryExpression binaryExpr) {
        Expression leftExpr = binaryExpr.getLeftExpression();
        Expression rightExpr = binaryExpr.getRightExpression();
        String operator = binaryExpr.getStringExpression();
        Value leftValue = null;
        Value rightValue = null;

        try {
            if (leftExpr instanceof Column leftColumn) {
                //get table name
                String table_name = leftColumn.getTableName();
                if (tuple instanceof TableTuple) {
                    TableTuple tableTuple = (TableTuple) tuple;
                    table_name = tableTuple.getTableName();
                }
                leftValue = tuple.getValue(new TabCol(table_name, leftColumn.getColumnName()));
                if (leftValue.type == ValueType.CHAR) {
                    leftValue = new Value(leftValue.toString());
                }
            } else {
                leftValue = getConstantValue(leftExpr); // Handle constant left value
            }

            if (rightExpr instanceof Column rightColumn) {
                //get table name
                String table_name = rightColumn.getTableName();
                if (tuple instanceof TableTuple) {
                    TableTuple tableTuple = (TableTuple) tuple;
                    table_name = tableTuple.getTableName();
                }
                rightValue = tuple.getValue(new TabCol(table_name, rightColumn.getColumnName()));
            } else {
                rightValue = getConstantValue(rightExpr); // Handle constant right value

            }

            if (leftValue == null || rightValue == null)
                return false;

            int comparisonResult = ValueComparer.compare(leftValue, rightValue);
            if (operator.equals("=")) {
                return comparisonResult == 0;
            } else if (operator.equals(">")) {
                return comparisonResult > 0;
            } else if (operator.equals("<")) {
                return comparisonResult < 0;
            } else if (operator.equals(">=")) {
                return comparisonResult >= 0;
            } else if (operator.equals("<=")) {
                return comparisonResult <= 0;
            }

        } catch (DBException e) {
            e.printStackTrace(); // Handle exception properly
        }
        return false;
    }

    private Value getConstantValue(Expression expr) {
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        }
        return null; // Unsupported constant type
    }

    public Value evaluateExpression(Expression expr) throws DBException {
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        } else if (expr instanceof Column) {
            Column col = (Column) expr;
            return getValue(new TabCol(col.getTableName(), col.getColumnName()));
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(expr));
        }
    }

    @SuppressWarnings("unchecked")
    private boolean evaluateInExpression(Tuple tuple, InExpression inExpr) {
        try {
            Value leftValue = resolveExpressionValue(tuple, inExpr.getLeftExpression());
            if (leftValue == null) return inExpr.isNot();

            Expression rightExpr = inExpr.getRightExpression();
            if (rightExpr instanceof ExpressionList) {
                ExpressionList<?> exprList = (ExpressionList<?>) rightExpr;
                for (Object obj : exprList.getExpressions()) {
                    Expression item = (Expression) obj;
                    Value itemValue = getConstantValue(item);
                    if (itemValue != null && ValueComparer.compare(leftValue, itemValue) == 0) {
                        return !inExpr.isNot(); // found → IN=true, NOT IN=false
                    }
                }
                return inExpr.isNot(); // not found → IN=false, NOT IN=true
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean evaluateExistsExpression(Tuple tuple, ExistsExpression existsExpr) {
        if (dbManager == null) return false;
        try {
            Expression subExpr = existsExpr.getRightExpression();
            String subSql = subExpr.toString().trim();
            // 去掉外层括号 (SubSelect/ParenthesedSelect 的 toString 会带括号)
            if (subSql.startsWith("(") && subSql.endsWith(")")) {
                subSql = subSql.substring(1, subSql.length() - 1).trim();
            }
            edu.sustech.cs307.logicalOperator.LogicalOperator subLogical =
                    edu.sustech.cs307.optimizer.LogicalPlanner.resolveAndPlan(dbManager, subSql);
            if (subLogical == null) return false;

            edu.sustech.cs307.physicalOperator.PhysicalOperator subPhysical =
                    edu.sustech.cs307.optimizer.PhysicalPlanner.generateOperator(dbManager, subLogical);
            if (subPhysical == null) return false;

            subPhysical.Begin();
            boolean hasRow = subPhysical.hasNext();
            subPhysical.Close();

            return hasRow;
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析表达式值：列引用→从tuple取值，常量→转为Value */
    private Value resolveExpressionValue(Tuple tuple, Expression expr) throws DBException {
        if (expr instanceof Column col) {
            String tableName = col.getTableName();
            if (tuple instanceof TableTuple tableTuple) {
                tableName = tableTuple.getTableName();
            }
            Value v = tuple.getValue(new TabCol(tableName, col.getColumnName()));
            if (v != null && v.type == ValueType.CHAR) {
                v = new Value(v.toString());
            }
            return v;
        }
        return getConstantValue(expr);
    }

}
