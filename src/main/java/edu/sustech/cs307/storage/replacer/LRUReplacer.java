package edu.sustech.cs307.storage.replacer;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

public class LRUReplacer implements PageReplacer {

    private final int maxSize;
    private final Set<Integer> pinnedFrames = new HashSet<>();
    private final Set<Integer> LRUHash = new HashSet<>();
    private final LinkedList<Integer> LRUList = new LinkedList<>();

    public LRUReplacer(int numPages) {
        this.maxSize = numPages;
    }

    /**
     * 淘汰最久未使用的 frame.
     * 链表头部 = LRU (最久未使用), 尾部 = MRU (最近使用).
     * @return 被淘汰的 frameId; 无 evictable frame 返回 -1
     */
    public int Victim() {
        if (LRUList.isEmpty()) {
            return -1;
        }
        int victimId = LRUList.removeFirst();  // 头部 = LRU
        LRUHash.remove(victimId);
        return victimId;
    }

    /**
     * 钉住 frame, 使其不能被淘汰.
     * 情况1: 已在 pinnedFrames → 不操作
     * 情况2: 已在 LRU → 从 LRU 移除, 加入 pinned
     * 情况3: 新 frame → 检查容量后加入 pinned
     */
    public void Pin(int frameId) {
        if (pinnedFrames.contains(frameId)) {
            return;
        }
        if (LRUHash.contains(frameId)) {
            // 从 LRU 转移到 pinned
            LRUList.removeFirstOccurrence(frameId);
            LRUHash.remove(frameId);
            pinnedFrames.add(frameId);
            return;
        }
        // 全新的 frame
        if (LRUList.size() + pinnedFrames.size() >= maxSize) {
            throw new RuntimeException("REPLACER IS FULL");
        }
        pinnedFrames.add(frameId);
    }

    /**
     * 取消钉住, 让 frame 重新参与 LRU 淘汰.
     * 情况1: 已在 LRUHash → 抛异常 (已可淘汰, 不能重复 Unpin)
     * 情况2: 在 pinnedFrames → 从 pinned 移到 LRU 链表尾部 (MRU 端)
     * 情况3: 都不在 → 抛异常
     */
    public void Unpin(int frameId) {
        if (LRUHash.contains(frameId)) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        if (!pinnedFrames.contains(frameId)) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        pinnedFrames.remove(frameId);
        LRUHash.add(frameId);
        LRUList.addLast(frameId);  // 尾部 = MRU
    }

    public int size() {
        return LRUList.size() + pinnedFrames.size();
    }
}
