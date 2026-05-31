package edu.sustech.cs307.physicalOperator;

import java.util.ArrayList;
import java.util.Collection;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.JoinTuple;
import edu.sustech.cs307.tuple.Tuple;
import net.sf.jsqlparser.expression.Expression;

public class NestedLoopJoinOperator implements PhysicalOperator {

    private final PhysicalOperator leftOperator;
    private final PhysicalOperator rightOperator;
    private final Collection<Expression> expr;

    private Tuple currentLeft;
    private Tuple currentRight;
    private Tuple currentJoinTuple;
    private boolean isOpen;

    public NestedLoopJoinOperator(PhysicalOperator leftOperator, PhysicalOperator rightOperator,
            Collection<Expression> expr) {
        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
        this.expr = expr;
        this.isOpen = false;
    }

    @Override
    public void Begin() throws DBException {
        leftOperator.Begin();
        rightOperator.Begin();
        isOpen = true;
        currentLeft = null;
        currentRight = null;
        currentJoinTuple = null;

        // 定位到第一个左元组
        if (leftOperator.hasNext()) {
            leftOperator.Next();
            currentLeft = leftOperator.Current();
        }
    }

    @Override
    public boolean hasNext() throws DBException {
        if (!isOpen || currentLeft == null) return false;

        // 当前右算子还有更多元组
        if (rightOperator.hasNext()) return true;

        // 右算子耗尽，尝试推进左算子
        rightOperator.Close();
        if (!leftOperator.hasNext()) return false;

        leftOperator.Next();
        currentLeft = leftOperator.Current();
        rightOperator.Begin();
        return rightOperator.hasNext();
    }

    @Override
    public void Next() throws DBException {
        rightOperator.Next();
        currentRight = rightOperator.Current();

        TabCol[] leftSchema = currentLeft.getTupleSchema();
        TabCol[] rightSchema = currentRight.getTupleSchema();
        TabCol[] joinSchema = new TabCol[leftSchema.length + rightSchema.length];
        System.arraycopy(leftSchema, 0, joinSchema, 0, leftSchema.length);
        System.arraycopy(rightSchema, 0, joinSchema, leftSchema.length, rightSchema.length);

        currentJoinTuple = new JoinTuple(currentLeft, currentRight, joinSchema);
    }

    @Override
    public Tuple Current() {
        return currentJoinTuple;
    }

    @Override
    public void Close() {
        leftOperator.Close();
        rightOperator.Close();
        currentLeft = null;
        currentRight = null;
        currentJoinTuple = null;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        ArrayList<ColumnMeta> leftSchema = leftOperator.outputSchema();
        ArrayList<ColumnMeta> rightSchema = rightOperator.outputSchema();
        if (leftSchema != null) schema.addAll(leftSchema);
        if (rightSchema != null) schema.addAll(rightSchema);
        return schema;
    }
}
