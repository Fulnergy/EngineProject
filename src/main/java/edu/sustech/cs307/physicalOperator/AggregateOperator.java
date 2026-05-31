package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AggregateOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final String aggregateFunction;
    private final String outputColumnName;
    private final String aggregateColumnName;
    private final boolean isStar;
    private final List<TabCol> groupByColumns;

    private final List<Tuple> resultTuples;
    private int currentIndex;
    private boolean isOpen;

    public AggregateOperator(PhysicalOperator child, String aggregateFunction,
                             String outputColumnName, String aggregateColumnName,
                             boolean isStar, List<TabCol> groupByColumns) {
        this.child = child;
        this.aggregateFunction = aggregateFunction;
        this.outputColumnName = outputColumnName;
        this.aggregateColumnName = aggregateColumnName;
        this.isStar = isStar;
        this.groupByColumns = groupByColumns != null ? groupByColumns : new ArrayList<>();
        this.resultTuples = new ArrayList<>();
        this.currentIndex = -1;
        this.isOpen = false;
    }

    @Override
    public boolean hasNext() {
        return isOpen && currentIndex < resultTuples.size();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        resultTuples.clear();

        String funcUpper = aggregateFunction.toUpperCase();

        if (!groupByColumns.isEmpty()) {
            // GROUP BY: collect all tuples, group by key, compute aggregate per group
            Map<List<Value>, List<Tuple>> groups = new LinkedHashMap<>();

            while (child.hasNext()) {
                child.Next();
                Tuple tuple = child.Current();
                if (tuple == null) continue;

                List<Value> groupKey = new ArrayList<>();
                for (TabCol col : groupByColumns) {
                    groupKey.add(tuple.getValue(col));
                }
                groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(tuple);
            }

            for (Map.Entry<List<Value>, List<Tuple>> entry : groups.entrySet()) {
                List<Value> groupKey = entry.getKey();
                List<Tuple> groupTuples = entry.getValue();
                Value aggResult = computeAggregate(funcUpper, groupTuples);

                List<Value> resultValues = new ArrayList<>(groupKey);
                resultValues.add(aggResult);
                resultTuples.add(new TempTuple(resultValues));
            }
        } else {
            // No GROUP BY: single aggregate over all tuples
            List<Tuple> allTuples = new ArrayList<>();
            while (child.hasNext()) {
                child.Next();
                Tuple tuple = child.Current();
                if (tuple != null) {
                    allTuples.add(tuple);
                }
            }

            Value aggResult = computeAggregate(funcUpper, allTuples);

            List<Value> resultValues = new ArrayList<>();
            resultValues.add(aggResult);
            resultTuples.add(new TempTuple(resultValues));
        }

        currentIndex = 0;
        isOpen = true;
    }

    private Value computeAggregate(String funcUpper, List<Tuple> tuples) throws DBException {
        switch (funcUpper) {
            case "COUNT":
                return computeCount(tuples);
            case "MAX":
                return computeMax(tuples);
            case "MIN":
                return computeMin(tuples);
            default:
                throw new DBException(
                        ExceptionTypes.UnsupportedCommand("Aggregate function: " + aggregateFunction));
        }
    }

    private Value computeCount(List<Tuple> tuples) throws DBException {
        if (isStar) {
            return new Value((long) tuples.size(), ValueType.INTEGER);
        }
        // COUNT(column): count non-null values
        long count = 0;
        TabCol col = resolveAggColumn();
        for (Tuple tuple : tuples) {
            Value v = tuple.getValue(col);
            if (v != null && v.value != null) {
                count++;
            }
        }
        return new Value(count, ValueType.INTEGER);
    }

    private Value computeMax(List<Tuple> tuples) throws DBException {
        TabCol col = resolveAggColumn();
        Value maxVal = null;
        for (Tuple tuple : tuples) {
            Value v = tuple.getValue(col);
            if (v == null || v.value == null) continue;
            if (maxVal == null || ValueComparer.compare(v, maxVal) > 0) {
                maxVal = v;
            }
        }
        return maxVal != null ? maxVal : new Value(0L);
    }

    private Value computeMin(List<Tuple> tuples) throws DBException {
        TabCol col = resolveAggColumn();
        Value minVal = null;
        for (Tuple tuple : tuples) {
            Value v = tuple.getValue(col);
            if (v == null || v.value == null) continue;
            if (minVal == null || ValueComparer.compare(v, minVal) < 0) {
                minVal = v;
            }
        }
        return minVal != null ? minVal : new Value(0L);
    }

    /**
     * Resolve the TabCol for the aggregate column using child's output schema.
     */
    private TabCol resolveAggColumn() {
        String tableName = null;
        if (aggregateColumnName != null) {
            ArrayList<ColumnMeta> childSchema = child.outputSchema();
            if (childSchema != null) {
                for (ColumnMeta cm : childSchema) {
                    if (cm.name.equals(aggregateColumnName)) {
                        tableName = cm.tableName;
                        break;
                    }
                }
            }
        }
        return new TabCol(tableName, aggregateColumnName);
    }

    @Override
    public void Next() {
        if (isOpen && currentIndex < resultTuples.size()) {
            currentIndex++;
        }
    }

    @Override
    public Tuple Current() {
        if (isOpen && currentIndex > 0 && currentIndex <= resultTuples.size()) {
            return resultTuples.get(currentIndex - 1);
        }
        return null;
    }

    @Override
    public void Close() {
        child.Close();
        resultTuples.clear();
        currentIndex = -1;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        int offset = 0;

        // GROUP BY columns come first in output
        if (!groupByColumns.isEmpty()) {
            ArrayList<ColumnMeta> childSchema = child.outputSchema();
            for (TabCol gbCol : groupByColumns) {
                ColumnMeta found = null;
                if (childSchema != null) {
                    for (ColumnMeta cm : childSchema) {
                        boolean tableMatch = gbCol.getTableName() == null
                                || cm.tableName.equals(gbCol.getTableName());
                        if (tableMatch && cm.name.equals(gbCol.getColumnName())) {
                            found = cm;
                            break;
                        }
                    }
                }
                if (found != null) {
                    schema.add(new ColumnMeta(found.tableName, found.name, found.type,
                            found.len, offset));
                    offset += found.len;
                }
            }
        }

        // Aggregate result column
        ValueType aggType = ValueType.INTEGER; // COUNT always returns INTEGER
        int aggLen = Value.INT_SIZE;
        if ("MAX".equalsIgnoreCase(aggregateFunction) || "MIN".equalsIgnoreCase(aggregateFunction)) {
            // MAX/MIN return the same type as the input column
            if (aggregateColumnName != null) {
                ArrayList<ColumnMeta> childSchema = child.outputSchema();
                if (childSchema != null) {
                    for (ColumnMeta cm : childSchema) {
                        if (cm.name.equals(aggregateColumnName)) {
                            aggType = cm.type;
                            aggLen = cm.len;
                            break;
                        }
                    }
                }
            }
        }
        schema.add(new ColumnMeta("aggregate", outputColumnName, aggType, aggLen, offset));

        return schema;
    }
}
