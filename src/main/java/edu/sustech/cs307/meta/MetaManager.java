package edu.sustech.cs307.meta;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;

public class MetaManager {
    private static final String META_FILE = "meta_data.json";
    private final String ROOT_DIR;
    private final Map<String, TableMeta> tables;
    private final ObjectMapper objectMapper;

    public MetaManager(String root_dir) throws DBException {
        this.objectMapper = new ObjectMapper();
        this.tables = new HashMap<>();
        this.ROOT_DIR = root_dir;
        loadFromJson();
    }

    public void createTable(TableMeta tableMeta) throws DBException {
        String tableName = tableMeta.tableName;
        if (tables.containsKey(tableName)) {
            throw new DBException(ExceptionTypes.TableAlreadyExist(tableName));
        }
        if (tableMeta.columnCount() == 0) {
            throw new DBException(ExceptionTypes.TableHasNoColumn(tableName));
        }
        tables.put(tableName, tableMeta);
        saveToJson();
    }

    public void dropTable(String tableName) throws DBException {
        if (!tables.containsKey(tableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        }
        tables.remove(tableName);
        saveToJson();
    }

    /** 为表创建索引 (记录到元数据中) */
    public void createIndex(String tableName, String columnName, String indexName) throws DBException {
        TableMeta table = getTable(tableName);
        if (!table.hasColumn(columnName)) {
            throw new DBException(ExceptionTypes.ColumnDoesNotExist(columnName));
        }
        if (table.getIndexes() == null) {
            table.setIndexes(new java.util.HashMap<>());
        }
        table.getIndexes().put(indexName, TableMeta.IndexType.BTREE);
        saveToJson();
    }

    /** 删除索引 (从元数据中移除) */
    public void dropIndex(String tableName, String indexName) throws DBException {
        TableMeta table = getTable(tableName);
        if (table.getIndexes() == null || !table.getIndexes().containsKey(indexName)) {
            return; // 索引不存在, 不报错 (idempotent)
        }
        table.getIndexes().remove(indexName);
        saveToJson();
    }

    public void addColumnInTable(String tableName, ColumnMeta column) throws DBException {
        if (!tables.containsKey(tableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        }
        this.tables.get(tableName).addColumn(column);
    }

    public void dropColumnInTable(String tableName, String columnName) throws DBException {
        if (!tables.containsKey(tableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        }
        this.tables.get(tableName).dropColumn((columnName));
    }

    public TableMeta getTable(String tableName) throws DBException {
        if (tables.containsKey(tableName)) {
            return tables.get(tableName);
        }
        throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        // return null;
    }

    public Set<String> getTableNames() {
        return this.tables.keySet();
    }

    /** 从事务回滚中恢复: 清空内存中的表数据, 重新从磁盘 JSON 加载 */
    public void reloadFromDisk() throws DBException {
        tables.clear();
        loadFromJson();
    }

    public void saveToJson() throws DBException {
        // check the root directory exists
        if (!new File(ROOT_DIR).exists()) {
            // create it
            new File(ROOT_DIR).mkdirs();
        }

        try (Writer writer = new FileWriter(String.format("%s/%s", ROOT_DIR, META_FILE))) {
            objectMapper.writeValue(writer, tables);
        } catch (Exception e) {
            throw new DBException(ExceptionTypes.UnableSaveMetadata(e.getMessage()));
        }
    }

    private void loadFromJson() throws DBException {
        File file = new File(ROOT_DIR + "/" + META_FILE);
        if (!file.exists())
            return;

        try (Reader reader = new FileReader(ROOT_DIR + "/" + META_FILE)) {
            TypeReference<Map<String, TableMeta>> typeRef = new TypeReference<>() {
            };
            Map<String, TableMeta> loadedTables = objectMapper.readValue(reader, typeRef);
            if (loadedTables != null) {
                tables.putAll(loadedTables);
            }
        } catch (Exception e) {
            throw new DBException(ExceptionTypes.UnableLoadMetadata(e.getMessage()));
        }
    }
}
