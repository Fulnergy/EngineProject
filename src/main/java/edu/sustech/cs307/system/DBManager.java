package edu.sustech.cs307.system;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import org.pmw.tinylog.Logger;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.index.BPlusTree;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;

public class DBManager {
    private final MetaManager metaManager;
    /* --- --- --- */
    private final DiskManager diskManager;
    private final BufferPool bufferPool;
    private final RecordManager recordManager;
    private TransactionManager transactionManager;
    private final IntFunction<PageReplacer> replacerFactory;
    /** 索引名 → B+ 树; key 格式: "tableName.indexName" */
    private final Map<String, BPlusTree> indexes = new HashMap<>();

    public DBManager(DiskManager diskManager, BufferPool bufferPool, RecordManager recordManager,
                     MetaManager metaManager) {
        this(diskManager, bufferPool, recordManager, metaManager, null, ClockReplacer::new);
    }

    public DBManager(DiskManager diskManager, BufferPool bufferPool, RecordManager recordManager,
                     MetaManager metaManager, TransactionManager transactionManager,
                     IntFunction<PageReplacer> replacerFactory) {
        this.diskManager = diskManager;
        this.bufferPool = bufferPool;
        this.recordManager = recordManager;
        this.metaManager = metaManager;
        this.replacerFactory = replacerFactory;
        this.transactionManager = transactionManager == null ? new TransactionManager(this) : transactionManager;
    }

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public BufferPool getBufferPool() {
        return bufferPool;
    }

    public RecordManager getRecordManager() {
        return recordManager;
    }

    public DiskManager getDiskManager() {
        return diskManager;
    }

    public MetaManager getMetaManager() {
        return metaManager;
    }

    public boolean isDirExists(String dir) {
        File file = new File(dir);
        return file.exists() && file.isDirectory();
    }

    /**
     * Displays a formatted table listing all available tables in the database.
     * The output is presented in a bordered ASCII table format with centered table
     * names.
     * Each table name is displayed in a separate row within the ASCII borders.
     */
    private static String getSeparator(int width) {
        StringBuilder separator = new StringBuilder("|");
        for(int i=0;i<width+2;i++)separator.append("-");
        separator.append("|");
        return separator.toString();
    }

    public void showTables() {
        Set<String> tables = metaManager.getTableNames();
        int width = 6;
        for(String s:tables){
            if(s.length()>width)width=s.length();
        }
        String separator = getSeparator(width);
        Logger.info(separator);
        StringBuilder rowTable = new StringBuilder("|");
        for(int i=0;i<(width-4)/2;i++) rowTable.append(" ");
        rowTable.append("tables");
        for(int i=0;i<(width-3)/2;i++) rowTable.append(" ");
        rowTable.append("|");
        Logger.info(rowTable);
        Logger.info(separator);
        for(String s:tables){
            StringBuilder sb = new StringBuilder("|");
            for(int i=0;i<(width-s.length()+2)/2;i++) sb.append(" ");
            sb.append(s);
            for(int i=0;i<(width-s.length()+3)/2;i++) sb.append(" ");
            sb.append("|");
            Logger.info(sb);
        }
        Logger.info(separator);
    }

    public void descTable(String table_name) {
        try {
            TableMeta tableMeta = metaManager.getTable(table_name);
            int fieldWidth = 5;
            int typeWidth = 4;
            for (ColumnMeta col : tableMeta.columns_list) {
                if (col.name.length() > fieldWidth) fieldWidth = col.name.length();
                String typeStr = col.type.name();
                if (typeStr.length() > typeWidth) typeWidth = typeStr.length();
            }

            String format = "| %-" + fieldWidth + "s | %-" + typeWidth + "s |";
            int totalWidth = fieldWidth + typeWidth + 7;
            StringBuilder separatorBuilder = new StringBuilder("|");
            for (int i = 0; i < totalWidth - 2; i++) separatorBuilder.append("-");
            separatorBuilder.append("|");
            String separator = separatorBuilder.toString();

            Logger.info(separator);
            Logger.info(String.format(format, "Field", "Type"));
            Logger.info(separator);
            for (ColumnMeta col : tableMeta.columns_list) {
                String typeStr = col.type.name().toLowerCase();
                Logger.info(String.format(format, col.name, typeStr));
            }
            Logger.info(separator);
        } catch (DBException e) {
            Logger.error(e.getMessage());
        }
    }

    /**
     * Creates a new table in the database with specified name and column metadata.
     * This method sets up both the table metadata and the physical storage
     * structure.
     *
     * @param table_name The name of the table to be created
     * @param columns    List of column metadata defining the table structure
     * @throws DBException If there is an error during table creation
     */
    public void createTable(String table_name, ArrayList<ColumnMeta> columns) throws DBException {
        TableMeta tableMeta = new TableMeta(
                table_name, columns);
        metaManager.createTable(tableMeta);
        String table_folder = String.format("%s/%s", diskManager.getCurrentDir(), table_name);
        File file_folder = new File(table_folder);
        if (!file_folder.exists()) {
            file_folder.mkdirs();
        }
        int record_size = 0;
        for (var col : columns) {
            record_size += col.len;
        }
        String data_file = String.format("%s/%s", table_name, "data");
        recordManager.CreateFile(data_file, record_size);
    }

    /**
     * Drops a table from the database by removing its metadata and associated
     * files.
     *
     * @param table_name The name of the table to be dropped
     * @throws DBException If the table directory does not exist or encounters IO
     *                     errors during deletion
     */
        public void dropTable(String table_name) throws DBException {
        String data_file = String.format("%s/%s", table_name, "data");
        recordManager.DeleteFile(data_file);
        metaManager.dropTable(table_name);
        String table_folder = String.format("%s/%s", diskManager.getCurrentDir(), table_name);
        File folder = new File(table_folder);
        if (folder.exists()) {
            deleteDirectory(folder);
        }
    }

    /**
     * 重命名表：更新元数据和磁盘目录名。
     */
    public void renameTable(String oldName, String newName) throws DBException {
        // 刷新缓冲池中旧表的页面
        bufferPool.FlushAllPages(String.format("%s/%s", oldName, "data"));
        bufferPool.DeleteAllPages(String.format("%s/%s", oldName, "data"));
        // 重命名磁盘目录
        String oldDir = String.format("%s/%s", diskManager.getCurrentDir(), oldName);
        String newDir = String.format("%s/%s", diskManager.getCurrentDir(), newName);
        File oldFolder = new File(oldDir);
        File newFolder = new File(newDir);
        if (oldFolder.exists() && !newFolder.exists()) {
            oldFolder.renameTo(newFolder);
        }
        // 更新 DiskManager 的文件页表
        if (diskManager.filePages != null) {
            String oldDataKey = String.format("%s/%s", oldName, "data");
            String newDataKey = String.format("%s/%s", newName, "data");
            Integer pages = diskManager.filePages.remove(oldDataKey);
            if (pages != null) {
                diskManager.filePages.put(newDataKey, pages);
            }
        }
        // 更新元数据
        metaManager.renameTable(oldName, newName);
    }

    /**
     * Recursively deletes a directory and all its contents.
     * If the given file is a directory, it first deletes all its entries
     * recursively.
     * Finally deletes the file/directory itself.
     *
     * @param file The file or directory to be deleted
     * @throws IOException If deletion of any file or directory fails
     */
    private void deleteDirectory(File file) throws DBException {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteDirectory(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new DBException(ExceptionTypes.BadIOError("File deletion failed: " + file.getAbsolutePath()));
        }
    }

    /**
     * Checks if a table exists in the database.
     *
     * @param table the name of the table to check
     * @return true if the table exists, false otherwise
     */
    public boolean isTableExists(String table) {
        return metaManager.getTableNames().contains(table);
    }

    /**
     * Closes the database manager and performs cleanup operations.
     * This method flushes all pages in the buffer pool, dumps disk manager
     * metadata,
     * and saves meta manager state to JSON format.
     *
     * @throws DBException if an error occurs during the closing process
     */
    public void closeDBManager() throws DBException {
        this.bufferPool.FlushAllPages(null);
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
    }

    public void beginTransaction() throws DBException {
        transactionManager.begin();
    }

    public void commitTransaction() throws DBException{
        transactionManager.commit();
    }

    public void persistRuntimeState() throws DBException {
        this.bufferPool.FlushAllPages("");
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
    }

    // ==================== 索引管理 ====================

    /** 为指定表的指定列创建 B+ 树索引 */
    public void createIndex(String tableName, String columnName, String indexName) throws DBException {
        metaManager.createIndex(tableName, columnName, indexName);
        BPlusTree tree = new BPlusTree(4);
        // 扫描全表, 把该列的值插入到 B+ 树中
        edu.sustech.cs307.physicalOperator.SeqScanOperator scanner =
                new edu.sustech.cs307.physicalOperator.SeqScanOperator(tableName, this);
        scanner.Begin();
        while (scanner.hasNext()) {
            scanner.Next();
            edu.sustech.cs307.tuple.Tuple tuple = scanner.Current();
            if (tuple == null) continue;
            int colIdx = getColumnIndex(tableName, columnName);
            if (colIdx >= 0) {
                edu.sustech.cs307.value.Value key = (edu.sustech.cs307.value.Value) tuple.getValues()[colIdx];
                edu.sustech.cs307.record.RID rid = null;
                if (tuple instanceof edu.sustech.cs307.tuple.TableTuple tableTuple) {
                    rid = tableTuple.getRID();
                }
                if (key != null && rid != null) {
                    tree.insert(key, rid);
                }
            }
        }
        scanner.Close();
        String key = tableName + "." + indexName;
        indexes.put(key, tree);
        // == 打印 B+ 树每个节点 ==
        org.pmw.tinylog.Logger.info("Index '" + indexName + "' created on " + tableName + "(" + columnName + ")");
        org.pmw.tinylog.Logger.info("\n" + tree.printTree());
    }

    /** 删除索引 */
    public void dropIndex(String tableName, String indexName) throws DBException {
        metaManager.dropIndex(tableName, indexName);
        String key = tableName + "." + indexName;
        indexes.remove(key);
    }

    /** 获取表的索引 (有列名匹配的 B+ 树就返回) */
    public BPlusTree getIndex(String tableName, String columnName) {
        TableMeta meta;
        try {
            meta = metaManager.getTable(tableName);
        } catch (DBException e) {
            return null;
        }
        if (meta.getIndexes() == null) return null;
        for (String idxName : meta.getIndexes().keySet()) {
            String key = tableName + "." + idxName;
            BPlusTree tree = indexes.get(key);
            if (tree != null) {
                // 检查这个索引是否在该列上 (简化: 索引名为 columnName)
                // 完整实现应该记录索引对应的列
                return tree;
            }
        }
        return null;
    }

    /** 获取所有索引 (用于打印等) */
    public Map<String, BPlusTree> getIndexes() {
        return indexes;
    }

    /** 获取某列在某表中的索引位置 */
    private int getColumnIndex(String tableName, String columnName) throws DBException {
        TableMeta meta = metaManager.getTable(tableName);
        for (int i = 0; i < meta.columns_list.size(); i++) {
            if (meta.columns_list.get(i).name.equals(columnName)) {
                return i;
            }
        }
        return -1;
    }
}
