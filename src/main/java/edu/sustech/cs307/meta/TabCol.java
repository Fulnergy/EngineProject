package edu.sustech.cs307.meta;

public class TabCol {
    private String tableName;
    private String columnName;

    public TabCol(String tableName, String columnName) {
        this.tableName = tableName;
        this.columnName = columnName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        TabCol tabCol = (TabCol) obj;
        if (!columnName.equals(tabCol.columnName)) return false;
        // null table name acts as wildcard
        if (tableName == null || tabCol.tableName == null) return true;
        return tableName.equals(tabCol.tableName);
    }

    @Override
    public int hashCode() {
        int result = columnName.hashCode();
        result = 31 * result + (tableName != null ? tableName.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return tableName != null
                ? tableName + "." + columnName
                : columnName;
    }
}
