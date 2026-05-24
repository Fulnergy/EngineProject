package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;

import java.util.ArrayList;
import java.util.List;

public class AggregateOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final String aggregateFunction;
    private final String outputColumnName;
    private Tuple resultTuple;
    private boolean isDone;

    public AggregateOperator(PhysicalOperator child, String aggregateFunction, String outputColumnName) {
        this.child = child;
        this.aggregateFunction = aggregateFunction;
        this.outputColumnName = outputColumnName;
        this.resultTuple = null;
        this.isDone = false;
    }

    @Override
    public boolean hasNext() {
        return !isDone;
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        long count = 0;

        while (child.hasNext()) {
            child.Next();
            count++;
        }

        String funcUpper = aggregateFunction.toUpperCase();
        List<Value> values = new ArrayList<>();
        switch (funcUpper) {
            case "COUNT":
                values.add(new Value(count, ValueType.INTEGER));
                break;
            default:
                throw new DBException(
                        edu.sustech.cs307.exception.ExceptionTypes.UnsupportedCommand(
                                "Aggregate function: " + aggregateFunction));
        }

        resultTuple = new TempTuple(values);
        isDone = false;
    }

    @Override
    public void Next() {
        isDone = true;
    }

    @Override
    public Tuple Current() {
        return resultTuple;
    }

    @Override
    public void Close() {
        child.Close();
        resultTuple = null;
        isDone = true;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.add(new ColumnMeta("aggregate", outputColumnName, ValueType.INTEGER, Value.INT_SIZE, 0));
        return schema;
    }
}
