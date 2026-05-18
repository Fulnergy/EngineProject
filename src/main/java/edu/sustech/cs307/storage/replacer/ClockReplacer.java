package edu.sustech.cs307.storage.replacer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClockReplacer implements PageReplacer {

    private final int capacity;
    private final List<Integer> frames;               // 环形数组 (null = 空槽)
    private final Map<Integer, FrameInfo> frameMap;    // frameId → {refBit, pinned}
    private int clockHand;
    private int evictableCount;                        // 可淘汰的 frame 数量

    private static class FrameInfo {
        int refBit;      // 1 = 最近被访问过, 0 = 没被访问过
        boolean pinned;

        FrameInfo() {
            this.refBit = 1;
            this.pinned = true;
        }
    }

    public ClockReplacer(int numPages) {
        this.capacity = numPages;
        this.frames = new ArrayList<>(numPages);
        for (int i = 0; i < numPages; i++) {
            frames.add(null);
        }
        this.frameMap = new HashMap<>();
        this.clockHand = 0;
        this.evictableCount = 0;
    }

    /**
     * 从钟针位置开始转圈, 找 refBit=0 的 evictable frame 淘汰.
     * refBit=1 → 改为 0 (给第二次机会), 继续转.
     * refBit=0 → 淘汰, 清空槽位, 从 frameMap 移除, 返回 frameId.
     * 跳过空槽和 pinned frames.
     *
     * @return 被淘汰的 frameId; 无 evictable frame 返回 -1
     */
    @Override
    public int Victim() {
        if (evictableCount == 0) {
            return -1;
        }
        int checked = 0;
        while (checked < capacity * 2) {
            Integer frameId = frames.get(clockHand);
            if (frameId == null) {
                advanceHand();
                checked++;
                continue;
            }
            FrameInfo info = frameMap.get(frameId);
            if (info == null || info.pinned) {
                advanceHand();
                checked++;
                continue;
            }
            if (info.refBit == 1) {
                // 给第二次机会
                info.refBit = 0;
                advanceHand();
                checked++;
            } else {
                // refBit == 0: 淘汰
                frames.set(clockHand, null);
                frameMap.remove(frameId);
                evictableCount--;
                advanceHand();
                return frameId;
            }
        }
        return -1;
    }

    /**
     * 钉住 frame, 使其不能被淘汰.
     * - 已在 frameMap 且 !pinned: 标记为 pinned
     * - 已在 frameMap 且 pinned: 不操作
     * - 新 frame: 找空槽插入, refBit=1, pinned=true
     */
    @Override
    public void Pin(int frameId) {
        FrameInfo existing = frameMap.get(frameId);
        if (existing != null) {
            if (!existing.pinned) {
                existing.pinned = true;
                evictableCount--;
            }
            return;
        }
        // 全新的 frame
        if (frameMap.size() >= capacity) {
            throw new RuntimeException("REPLACER IS FULL");
        }
        int slot = findEmptySlot();
        frames.set(slot, frameId);
        FrameInfo info = new FrameInfo();
        info.pinned = true;
        frameMap.put(frameId, info);
    }

    /**
     * 取消钉住, frame 重新变为可淘汰.
     * - frame 不在 frameMap 中: 抛异常 "UNPIN PAGE NOT FOUND"
     * - frame 已是 evictable (!pinned): 抛异常 "UNPIN PAGE NOT FOUND"
     * - frame 是 pinned: 设为 evictable, refBit=1
     */
    @Override
    public void Unpin(int frameId) {
        FrameInfo info = frameMap.get(frameId);
        if (info == null) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        if (!info.pinned) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        info.pinned = false;
        info.refBit = 1;
        evictableCount++;
    }

    @Override
    public int size() {
        return frameMap.size();
    }

    private void advanceHand() {
        clockHand = (clockHand + 1) % capacity;
    }

    private int findEmptySlot() {
        for (int i = 0; i < capacity; i++) {
            if (frames.get(i) == null) {
                return i;
            }
        }
        throw new RuntimeException("No empty slot available");
    }
}
