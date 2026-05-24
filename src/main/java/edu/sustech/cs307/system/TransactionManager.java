package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 事务管理器 — 基于文件系统快照实现事务的 BEGIN/COMMIT/ROLLBACK 和 SAVEPOINT.
 *
 * 核心思路:
 * - BEGIN:  复制整个数据库目录到临时快照
 * - COMMIT: 持久化当前状态, 删除快照
 * - ROLLBACK: 用快照覆盖当前数据库目录, 恢复原状
 * - SAVEPOINT: 在当前事务内创建命名快照 (栈语义: 同名后创建的遮蔽先创建的)
 * - ROLLBACK TO SAVEPOINT: 恢复到指定保存点的快照
 * - RELEASE SAVEPOINT: 删除指定保存点的快照
 */
public class TransactionManager {

    private final DBManager dbManager;
    /** BEGIN 时创建的快照 (整个数据库目录的副本) */
    private Path transactionSnapshot;
    /** 当前是否在事务中 */
    private boolean inTransaction = false;
    /**
     * 保存点: key=名称, value=同名保存点的栈 (后创建的在上层).
     * 同名保存点按栈语义: 后创建的遮蔽先创建的;
     * rollbackToSavepoint/releaseSavepoint 操作栈顶.
     */
    private final Map<String, Deque<Path>> savepoints = new LinkedHashMap<>();

    public TransactionManager(DBManager dbManager) {
        this.dbManager = dbManager;
    }

    // ==================== 事务控制 ====================

    /** 开始事务: 创建快照. 已在事务中则抛出 TRANSACTION_ALREADY_ACTIVE. */
    public void begin() throws DBException {
        if (inTransaction) {
            throw new DBException(ExceptionTypes.TransactionAlreadyActive());
        }
        transactionSnapshot = createSnapshot();
        inTransaction = true;
    }

    /** 提交事务: 持久化 + 清理快照和保存点. */
    public void commit() throws DBException {
        dbManager.persistRuntimeState();
        cleanupTransaction();
    }

    /**
     * 回滚整个事务: 恢复到 BEGIN 前的快照.
     * 事务外调用时不报错 (空操作).
     */
    public void rollback() throws DBException {
        if (!inTransaction) return;
        restoreFromSnapshot(transactionSnapshot);
        cleanupTransaction();
    }

    // ==================== 保存点 ====================

    /** 创建保存点. 必须在事务中使用. 同名后创建的遮蔽先创建的 (栈语义). */
    public void savepoint(String savepointName) throws DBException {
        if (!inTransaction) {
            throw new DBException(ExceptionTypes.TransactionRequired());
        }
        Path snapshot = createSnapshot();
        savepoints.computeIfAbsent(savepointName, k -> new ArrayDeque<>()).push(snapshot);
    }

    /** 回滚到指定保存点 (pop 栈顶). 必须在事务中使用. */
    public void rollbackToSavepoint(String savepointName) throws DBException {
        if (!inTransaction) {
            throw new DBException(ExceptionTypes.TransactionRequired());
        }
        Deque<Path> stack = savepoints.get(savepointName);
        if (stack == null || stack.isEmpty()) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }
        Path snapshot = stack.pop();
        restoreFromSnapshot(snapshot);
        deleteSnapshot(snapshot);
        if (stack.isEmpty()) savepoints.remove(savepointName);
    }

    /** 释放保存点 (pop 栈顶). 必须在事务中使用. */
    public void releaseSavepoint(String savepointName) throws DBException {
        if (!inTransaction) {
            throw new DBException(ExceptionTypes.TransactionRequired());
        }
        Deque<Path> stack = savepoints.get(savepointName);
        if (stack == null || stack.isEmpty()) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }
        Path snapshot = stack.pop();
        deleteSnapshot(snapshot);
        if (stack.isEmpty()) savepoints.remove(savepointName);
    }

    public boolean isInTransaction() {
        return inTransaction;
    }

    // ==================== 内部实现 ====================

    /** 创建当前数据库目录的快照 (复制到临时目录) */
    private Path createSnapshot() throws DBException {
        dbManager.persistRuntimeState();
        try {
            Path snapshotDir = Files.createTempDirectory("cs307-txn-");
            copyDirectoryContents(getDbRoot(), snapshotDir);
            return snapshotDir;
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
    }

    /** 从快照恢复: 清空当前目录, 复制快照内容回来 */
    private void restoreFromSnapshot(Path snapshot) throws DBException {
        try {
            Path dbRoot = getDbRoot();
            if (Files.exists(dbRoot)) {
                try (Stream<Path> entries = Files.walk(dbRoot)) {
                    entries.sorted(Comparator.reverseOrder())
                            .filter(p -> !p.equals(dbRoot))
                            .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                }
            }
            copyDirectoryContents(snapshot, dbRoot);
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
    }

    /** 删除快照目录 (递归) */
    private void deleteSnapshot(Path snapshot) {
        if (snapshot == null || !Files.exists(snapshot)) return;
        try (Stream<Path> entries = Files.walk(snapshot)) {
            entries.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    /** 清理事务: 删除所有快照, 重置状态 */
    private void cleanupTransaction() {
        deleteSnapshot(transactionSnapshot);
        transactionSnapshot = null;
        for (Deque<Path> stack : savepoints.values()) {
            while (!stack.isEmpty()) deleteSnapshot(stack.pop());
        }
        savepoints.clear();
        inTransaction = false;
    }

    private Path getDbRoot() {
        return Path.of(dbManager.getDiskManager().getCurrentDir());
    }

    private void copyDirectoryContents(Path sourceRoot, Path targetRoot) throws IOException {
        if (!Files.exists(sourceRoot)) {
            Files.createDirectories(targetRoot);
            return;
        }
        Files.createDirectories(targetRoot);
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
