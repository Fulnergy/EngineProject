package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOrderByOperator;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class OrderByOperator implements PhysicalOperator {

    private final PhysicalOperator child;
    private final LogicalOrderByOperator logicalOp;

    private final List<Tuple> sortedTuples;
    private int currentIndex;
    private boolean isOpen;

    public OrderByOperator(PhysicalOperator child, LogicalOrderByOperator logicalOp) {
        this.child = child;
        this.logicalOp = logicalOp;
        this.sortedTuples = new ArrayList<>();
        this.currentIndex = -1;
        this.isOpen = false;
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        sortedTuples.clear();

        // 收集子算子的所有元组
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple != null) {
                sortedTuples.add(tuple);
            }
        }

        // 构建排序比较器
        List<LogicalOrderByOperator.SortKey> sortKeys = logicalOp.getSortKeys();
        if (!sortKeys.isEmpty()) {
            Comparator<Tuple> comparator = (a, b) -> {
                try {
                    for (LogicalOrderByOperator.SortKey key : sortKeys) {
                        Value va = a.getValue(key.column());
                        Value vb = b.getValue(key.column());
                        if (va == null && vb == null) continue;
                        if (va == null) return key.ascending() ? -1 : 1;
                        if (vb == null) return key.ascending() ? 1 : -1;
                        int cmp = ValueComparer.compare(va, vb);
                        if (cmp != 0) {
                            return key.ascending() ? cmp : -cmp;
                        }
                    }
                    return 0;
                } catch (DBException e) {
                    throw new RuntimeException(e);
                }
            };
            sortedTuples.sort(comparator);
        }

        currentIndex = 0;
        isOpen = true;
    }

    @Override
    public boolean hasNext() {
        return isOpen && currentIndex < sortedTuples.size();
    }

    @Override
    public void Next() {
        if (isOpen && currentIndex < sortedTuples.size()) {
            currentIndex++;
        }
    }

    @Override
    public Tuple Current() {
        if (isOpen && currentIndex > 0 && currentIndex <= sortedTuples.size()) {
            return sortedTuples.get(currentIndex - 1);
        }
        return null;
    }

    @Override
    public void Close() {
        child.Close();
        sortedTuples.clear();
        currentIndex = -1;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return child.outputSchema();
    }
}
