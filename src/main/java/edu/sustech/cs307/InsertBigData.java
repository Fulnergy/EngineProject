package edu.sustech.cs307;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.util.*;

/**
 * 直接调用存储层 API，向表 big 插入 10 万行随机数据。
 * <p>
 * 运行前需先建表：create table big (id int, name char, score int);
 */
public class InsertBigData {

    private static final String TABLE_NAME = "big";
    private static final int TOTAL_ROWS = 100_000;

    public static void main(String[] args) {
        Logger.getConfiguration().formatPattern("{date: HH:mm:ss.SSS} {level}: {message}").activate();

        try {
            // 1. 初始化引擎（同 DBEntry）
            Map<String, Integer> diskMeta = new HashMap<>(DiskManager.read_disk_manager_meta());
            DiskManager diskManager = new DiskManager(DBEntry.DB_NAME, diskMeta);
            BufferPool bufferPool = new BufferPool(DBEntry.POOL_SIZE, diskManager);
            RecordManager recordManager = new RecordManager(diskManager, bufferPool);
            MetaManager metaManager = new MetaManager(DBEntry.DB_NAME + "/meta");
            DBManager dbManager = new DBManager(diskManager, bufferPool, recordManager, metaManager);

            // 1.5 确保数据文件在 filePages 中已注册
            // REPL 的 exit 不会持久化 disk_manager_meta.json，所以数据文件可能不在 meta 中
            String dataFileName = TABLE_NAME + "/data";
            if (!diskManager.filePages.containsKey(dataFileName)) {
                File f = new File(DBEntry.DB_NAME + "/" + dataFileName);
                if (f.exists()) {
                    long fileSize = f.length();
                    int pages = (int) (fileSize / 4096);
                    diskManager.filePages.put(dataFileName, Math.max(pages, 1));
                    Logger.info("已注册数据文件 " + dataFileName + "，共 " + pages + " 页");
                }
            }

            // 2. 确认表存在
            if (!dbManager.isTableExists(TABLE_NAME)) {
                Logger.error("表 " + TABLE_NAME + " 不存在，请先执行建表语句。");
                return;
            }
            TableMeta tableMeta = metaManager.getTable(TABLE_NAME);
            Logger.info("表 " + TABLE_NAME + " 结构：");
            for (ColumnMeta col : tableMeta.columns_list) {
                Logger.info("  " + col.name + " (" + col.type + ", offset=" + col.offset + ", len=" + col.len + ")");
            }

            // 3. 打开文件句柄
            RecordFileHandle fileHandle = recordManager.OpenFile(TABLE_NAME);

            // 4. 逐行插入
            Random random = new Random(42);
            long startTime = System.currentTimeMillis();

            for (int id = 1; id <= TOTAL_ROWS; id++) {
                String name = "user_" + id;
                int score = random.nextInt(101);

                ByteBuf buffer = Unpooled.buffer();
                buffer.writeBytes(new Value((long) id).ToByte());           // id (INTEGER, 8 bytes)
                buffer.writeBytes(new Value(name).ToByte());                // name (CHAR, 64 bytes)
                buffer.writeBytes(new Value((long) score).ToByte());        // score (INTEGER, 8 bytes)

                fileHandle.InsertRecord(buffer);

                if (id % 10_000 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    Logger.info("已插入 " + id + " 行...（耗时 " + elapsed + " ms）");
                }
            }

            // 5. 刷盘并关闭
            recordManager.CloseFile(fileHandle);
            bufferPool.FlushAllPages("");
            DiskManager.dump_disk_manager_meta(diskManager);
            metaManager.saveToJson();

            long totalTime = System.currentTimeMillis() - startTime;
            Logger.info("插入完成！共 " + TOTAL_ROWS + " 行，总耗时 " + totalTime + " ms");

        } catch (DBException e) {
            Logger.error("插入失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
