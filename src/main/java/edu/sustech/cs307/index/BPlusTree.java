package edu.sustech.cs307.index;

import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.value.Value;

import java.util.*;

/**
 * 内存 B+ 树索引, 实现 Index 接口.
 *
 * 结构:
 *   InternalNode: keys + children (路由)
 *   LeafNode:     keys + values(RID) + next (数据 + 链表)
 *
 * 操作: insert / remove / EqualTo / LessThan / MoreThan / Range / printTree
 */
public class BPlusTree implements Index {

    private final int order;
    private final int maxKeys;       // order - 1
    private final int minKeys;       // ceil(order/2) - 1

    private Node root;
    private LeafNode firstLeaf;

    public BPlusTree(int order) {
        this.order = order;
        this.maxKeys = order - 1;
        this.minKeys = Math.max(1, (order + 1) / 2 - 1);
        this.root = new LeafNode();
        this.firstLeaf = (LeafNode) root;
    }

    // ============ 节点类型 ============

    private class InternalNode extends Node {
        List<Node> children;
        InternalNode() {
            this.keys = new ArrayList<>();
            this.children = new ArrayList<>();
        }
        @Override boolean isLeaf() { return false; }
        @Override public String toString() {
            return "InternalNode[keys=" + keys + "]";
        }
    }

    private class LeafNode extends Node {
        List<RID> values;
        LeafNode next;
        LeafNode() {
            this.keys = new ArrayList<>();
            this.values = new ArrayList<>();
            this.next = null;
        }
        @Override boolean isLeaf() { return true; }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("LeafNode[");
            for (int i = 0; i < keys.size(); i++) {
                sb.append(keys.get(i)).append("→").append(values.get(i));
                if (i < keys.size() - 1) sb.append(", ");
            }
            sb.append("]→").append(next != null ? "next" : "null");
            return sb.toString();
        }
    }

    private abstract class Node {
        List<Value> keys;
        abstract boolean isLeaf();
    }

    // ============ 公共操作 ============

    /** 插入 key-value */
    public void insert(Value key, RID value) {
        LeafNode leaf = findLeaf(key);
        int pos = Collections.binarySearch(leaf.keys, key, ValueComparer.INSTANCE);
        int insertPos = pos >= 0 ? pos : -pos - 1;
        leaf.keys.add(insertPos, key);
        leaf.values.add(insertPos, value);
        if (leaf.keys.size() > maxKeys) splitLeaf(leaf);
    }

    /** 删除 key-value */
    public void remove(Value key, RID value) {
        LeafNode leaf = findLeaf(key);
        int pos = Collections.binarySearch(leaf.keys, key, ValueComparer.INSTANCE);
        if (pos < 0) return;
        while (pos < leaf.keys.size() && ValueComparer.INSTANCE.compare(leaf.keys.get(pos), key) == 0) {
            if (leaf.values.get(pos).equals(value)) {
                leaf.keys.remove(pos);
                leaf.values.remove(pos);
                return;
            }
            pos++;
        }
    }

    // ============ Index 接口 ============

    @Override
    public RID EqualTo(Value value) {
        LeafNode leaf = findLeaf(value);
        int pos = Collections.binarySearch(leaf.keys, value, ValueComparer.INSTANCE);
        return pos >= 0 ? leaf.values.get(pos) : null;
    }

    @Override
    public Iterator<Map.Entry<Value, RID>> LessThan(Value value, boolean isEqual) {
        List<Map.Entry<Value, RID>> results = new ArrayList<>();
        LeafNode leaf = firstLeaf;
        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                int cmp = ValueComparer.INSTANCE.compare(leaf.keys.get(i), value);
                if (cmp < 0 || (isEqual && cmp == 0)) {
                    results.add(new AbstractMap.SimpleEntry<>(leaf.keys.get(i), leaf.values.get(i)));
                } else return results.iterator();
            }
            leaf = leaf.next;
        }
        return results.iterator();
    }

    @Override
    public Iterator<Map.Entry<Value, RID>> MoreThan(Value value, boolean isEqual) {
        List<Map.Entry<Value, RID>> results = new ArrayList<>();
        LeafNode leaf = firstLeaf;
        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                int cmp = ValueComparer.INSTANCE.compare(leaf.keys.get(i), value);
                if (cmp > 0 || (isEqual && cmp == 0)) {
                    results.add(new AbstractMap.SimpleEntry<>(leaf.keys.get(i), leaf.values.get(i)));
                }
            }
            leaf = leaf.next;
        }
        return results.iterator();
    }

    @Override
    public Iterator<Map.Entry<Value, RID>> Range(Value low, Value high, boolean leftEqual, boolean rightEqual) {
        List<Map.Entry<Value, RID>> results = new ArrayList<>();
        LeafNode leaf = firstLeaf;
        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                Value k = leaf.keys.get(i);
                int cmpLow = ValueComparer.INSTANCE.compare(k, low);
                int cmpHigh = ValueComparer.INSTANCE.compare(k, high);
                boolean aboveLow = cmpLow > 0 || (leftEqual && cmpLow == 0);
                boolean belowHigh = cmpHigh < 0 || (rightEqual && cmpHigh == 0);
                if (aboveLow && belowHigh) {
                    results.add(new AbstractMap.SimpleEntry<>(k, leaf.values.get(i)));
                }
            }
            leaf = leaf.next;
        }
        return results.iterator();
    }

    // ============ 内部方法 ============

    private LeafNode findLeaf(Value key) {
        Node node = root;
        while (!node.isLeaf()) {
            InternalNode in = (InternalNode) node;
            int idx = upperBound(in.keys, key);
            node = in.children.get(idx);
        }
        return (LeafNode) node;
    }

    private int upperBound(List<Value> keys, Value key) {
        int lo = 0, hi = keys.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ValueComparer.INSTANCE.compare(keys.get(mid), key) <= 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** 分裂叶子: 拆成两半, 新分隔 key 提升到父节点 */
    private void splitLeaf(LeafNode leaf) {
        int mid = leaf.keys.size() / 2;
        LeafNode newLeaf = new LeafNode();
        newLeaf.keys.addAll(leaf.keys.subList(mid, leaf.keys.size()));
        newLeaf.values.addAll(leaf.values.subList(mid, leaf.values.size()));
        leaf.keys.subList(mid, leaf.keys.size()).clear();
        leaf.values.subList(mid, leaf.values.size()).clear();
        newLeaf.next = leaf.next;
        leaf.next = newLeaf;
        insertInParent(leaf, newLeaf.keys.get(0), newLeaf);
    }

    /** 分裂内部节点 */
    private void splitInternal(InternalNode node) {
        int mid = node.keys.size() / 2;
        Value promoted = node.keys.get(mid);
        InternalNode newNode = new InternalNode();
        newNode.keys.addAll(node.keys.subList(mid + 1, node.keys.size()));
        newNode.children.addAll(node.children.subList(mid + 1, node.children.size()));
        node.keys.subList(mid, node.keys.size()).clear();
        node.children.subList(mid + 1, node.children.size()).clear();
        insertInParent(node, promoted, newNode);
    }

    /** 分裂后向父节点插入新分隔 key */
    private void insertInParent(Node left, Value key, Node right) {
        if (left == root) {
            InternalNode newRoot = new InternalNode();
            newRoot.keys.add(key);
            newRoot.children.add(left);
            newRoot.children.add(right);
            root = newRoot;
            return;
        }
        InternalNode parent = findParent(root, left);
        int idx = parent.children.indexOf(left);
        parent.keys.add(idx, key);
        parent.children.add(idx + 1, right);
        if (parent.keys.size() > maxKeys) splitInternal(parent);
    }

    /** 从 subtree 开始查找 target 的父节点 */
    private InternalNode findParent(Node subtree, Node target) {
        if (subtree.isLeaf()) return null;
        InternalNode in = (InternalNode) subtree;
        if (in.children.contains(target)) return in;
        for (Node child : in.children) {
            if (!child.isLeaf()) {
                InternalNode result = findParent(child, target);
                if (result != null) return result;
            }
        }
        return null;
    }

    // ============ 打印 ============

    /** 按层打印 B+ 树结构, 包含叶子链详情 */
    public String printTree() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== B+ Tree (order=").append(order).append(") ===\n");
        List<Node> level = new ArrayList<>();
        level.add(root);
        int depth = 0;
        while (!level.isEmpty()) {
            sb.append("Depth ").append(depth).append(": ");
            List<Node> nextLevel = new ArrayList<>();
            for (Node node : level) {
                sb.append("[");
                for (int i = 0; i < node.keys.size(); i++) {
                    sb.append(node.keys.get(i));
                    if (i < node.keys.size() - 1) sb.append(", ");
                }
                sb.append("] ");
                if (!node.isLeaf()) nextLevel.addAll(((InternalNode) node).children);
            }
            sb.append("\n");
            level = nextLevel;
            depth++;
        }
        sb.append("\nLeaf chain:\n");
        LeafNode leaf = firstLeaf;
        while (leaf != null) {
            sb.append("  ").append(leaf).append("\n");
            leaf = leaf.next;
        }
        return sb.toString();
    }

    public int getOrder() { return order; }

    /** Value 比较器, 委托给 ValueComparer (DBException → RuntimeException) */
    static class ValueComparer implements Comparator<Value> {
        static final ValueComparer INSTANCE = new ValueComparer();
        @Override
        public int compare(Value a, Value b) {
            try {
                return edu.sustech.cs307.value.ValueComparer.compare(a, b);
            } catch (edu.sustech.cs307.exception.DBException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
