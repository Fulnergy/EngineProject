package demo;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.BPlusTreeIndexScanOperator;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.physicalOperator.SeqScanOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import edu.sustech.cs307.system.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

public class BPlusTreeDemoTest {

    @TempDir Path tempDir;

    @Test void demoWith100Rows() throws DBException {
        DBManager db = buildDb();
        System.out.println("=== 建表 + 插入 100 行 ===");
        exec(db, "create table big (id int, name char, score int)");
        for (int i = 1; i <= 100; i++) {
            int s = (i * 37 + 13) % 101;
            exec(db, "insert into big (id, name, score) values (" + i + ", 'u_" + i + "', " + s + ")");
        }
        System.out.println("100 rows inserted.\n");

        // 无索引
        System.out.println("=== 无索引: explain ===");
        exec(db, "explain select * from big where big.score = 50");
        System.out.print("Physical op: ");
        printPhysicalOp(db, "select * from big where big.score = 50");
        System.out.println();

        // 建索引
        System.out.println("=== CREATE INDEX ===");
        exec(db, "create index idx_score on big(score)");
        System.out.println("Indexes in DBManager: " + db.getIndexes().keySet());
        System.out.println();

        // 有索引
        System.out.println("=== 有索引: explain ===");
        exec(db, "explain select * from big where big.score = 50");
        System.out.print("Physical op: ");
        printPhysicalOp(db, "select * from big where big.score = 50");
        System.out.println();

        // 范围查询
        System.out.println("=== 范围查询 ===");
        exec(db, "select * from big where big.score > 90");

        // 多索引
        System.out.println("=== 第二个索引 ===");
        exec(db, "create index idx_id on big(id)");
        System.out.print("Physics op (score): ");
        printPhysicalOp(db, "select * from big where big.score = 50");
        System.out.print("Physics op (id): ");
        printPhysicalOp(db, "select * from big where big.id = 100");

        System.out.println("\n=== ALL DONE ===");
    }

    private void printPhysicalOp(DBManager db, String sql) throws DBException {
        LogicalOperator logicalOp = LogicalPlanner.resolveAndPlan(db, sql);
        PhysicalOperator physicalOp = PhysicalPlanner.generateOperator(db, logicalOp);
        String name = physicalOp.getClass().getSimpleName();
        System.out.println(name);
        physicalOp.Close();
    }

    private DBManager buildDb() throws DBException {
        var fileOffsets = new HashMap<String, Integer>();
        DiskManager dm = new DiskManager(tempDir.toString(), fileOffsets);
        BufferPool bp = new BufferPool(64, dm, new ClockReplacer(64));
        RecordManager rm = new RecordManager(dm, bp);
        MetaManager mm = new MetaManager(tempDir.resolve("meta").toString());
        return new DBManager(dm, bp, rm, mm, null, sz -> new ClockReplacer(sz));
    }

    private void exec(DBManager db, String sql) throws DBException {
        LogicalOperator logicalOp = LogicalPlanner.resolveAndPlan(db, sql);
        if (logicalOp == null) return;
        PhysicalOperator physicalOp = PhysicalPlanner.generateOperator(db, logicalOp);
        physicalOp.Begin();
        int count = 0;
        while (physicalOp.hasNext()) {
            physicalOp.Next();
            var t = physicalOp.Current();
            if (t != null) count++;
        }
        physicalOp.Close();
        db.getBufferPool().FlushAllPages("");
        if (count > 0) System.out.println("  -> " + count + " row(s)");
    }
}
