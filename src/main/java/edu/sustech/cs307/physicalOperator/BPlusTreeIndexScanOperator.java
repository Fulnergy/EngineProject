package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.index.BPlusTree;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;

import java.util.*;

/**
 * 基于 B+ 树索引的扫描算子 (火山模型).
 *
 * Begin() 时从 B+ 树全量取出所有 RID 并排序,
 * Next() 时通过 RID 直接从文件读取记录.
 */
public class BPlusTreeIndexScanOperator implements PhysicalOperator {

    private final BPlusTree index;
    private final String tableName;
    private final DBManager dbManager;
    private final TableMeta tableMeta;

    private RecordFileHandle fileHandle;
    private final List<RID> rids = new ArrayList<>();
    private int position = -1;
    private Record currentRecord;
    private boolean isOpen = false;

    public BPlusTreeIndexScanOperator(BPlusTree index, String tableName, DBManager dbManager) {
        this.index = index;
        this.tableName = tableName;
        this.dbManager = dbManager;
        TableMeta meta;
        try {
            meta = dbManager.getMetaManager().getTable(tableName);
        } catch (DBException e) {
            meta = null;
        }
        this.tableMeta = meta;
    }

    @Override
    public void Begin() throws DBException {
        rids.clear();
        Iterator<Map.Entry<Value, RID>> iter = index.MoreThan(null, true);
        while (iter.hasNext()) {
            rids.add(iter.next().getValue());
        }
        rids.sort(Comparator.comparingInt((RID r) -> r.pageNum).thenComparingInt(r -> r.slotNum));
        fileHandle = dbManager.getRecordManager().OpenFile(tableName);
        position = 0;
        isOpen = true;
    }

    @Override
    public boolean hasNext() throws DBException {
        return isOpen && position < rids.size();
    }

    @Override
    public void Next() throws DBException {
        if (!isOpen || position >= rids.size()) {
            currentRecord = null;
            return;
        }
        currentRecord = fileHandle.GetRecord(rids.get(position));
        position++;
    }

    @Override
    public Tuple Current() {
        if (!isOpen || currentRecord == null) return null;
        return new TableTuple(tableName, tableMeta, currentRecord,
                position > 0 ? rids.get(position - 1) : null);
    }

    @Override
    public void Close() {
        if (!isOpen) return;
        try { dbManager.getRecordManager().CloseFile(fileHandle); } catch (DBException ignored) {}
        fileHandle = null;
        currentRecord = null;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return tableMeta.columns_list;
    }
}
