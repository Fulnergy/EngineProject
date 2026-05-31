package edu.sustech.cs307.logicalOperator;

import edu.sustech.cs307.meta.TabCol;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.OrderByElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LogicalOrderByOperator extends LogicalOperator {

    private final LogicalOperator child;
    private final List<OrderByElement> orderByElements;

    public LogicalOrderByOperator(LogicalOperator child, List<OrderByElement> orderByElements) {
        super(Collections.singletonList(child));
        this.child = child;
        this.orderByElements = orderByElements;
    }

    public LogicalOperator getChild() {
        return child;
    }

    public List<OrderByElement> getOrderByElements() {
        return orderByElements;
    }

    /**
     * 从 OrderByElement 列表提取排序元数据: (TabCol, isAsc)
     */
    public List<SortKey> getSortKeys() {
        List<SortKey> keys = new ArrayList<>();
        for (OrderByElement elem : orderByElements) {
            if (elem.getExpression() instanceof Column col) {
                String tableName = col.getTableName() != null ? col.getTableName().toString() : null;
                TabCol tabCol = new TabCol(tableName, col.getColumnName());
                keys.add(new SortKey(tabCol, elem.isAsc()));
            }
        }
        return keys;
    }

    public record SortKey(TabCol column, boolean ascending) {}

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("OrderByOperator(elements=").append(orderByElements).append(")");
        String[] childLines = child.toString().split("\\R");
        if (childLines.length > 0) {
            sb.append("\n└── ").append(childLines[0]);
            for (int i = 1; i < childLines.length; i++) {
                sb.append("\n    ").append(childLines[i]);
            }
        }
        return sb.toString();
    }
}
