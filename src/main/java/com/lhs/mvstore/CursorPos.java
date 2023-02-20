package com.lhs.mvstore;

/**
 * A position in a cursor.
 * Instance represents a node in the linked list, which traces path
 * from a specific (target) key within a leaf node all the way up to te root
 * (bottom up path).
 */
public final class CursorPos {

    /**
     * The page at the current level.
     */
    public Page page;

    /**
     * Index of the key (within page above) used to go down to a lower level
     * in case of intermediate nodes, or index of the target key for leaf a node.
     * In a later case, it could be negative, if the key is not present.
     */
    public int index;

    /**
     * Next node in the linked list, representing the position within parent level,
     * or null, if we are at the root level already.
     */
    public CursorPos parent;


    public CursorPos(Page page, int index, CursorPos parent) {
        this.page = page;
        this.index = index;
        this.parent = parent;
    }

    /**
     * 迭代读入page
     *
     * Searches for a given key and creates a breadcrumb trail through a B-tree
     * rooted at a given Page. Resulting path starts at "insertion point" for a
     * given key and goes back to the root.
     *
     * @param page      root of the tree
     * @param key       the key to search for
     * @return head of the CursorPos chain (insertion point)
     */
    /**
     * 第一次调用时，page就是root页
     */
    static  CursorPos traverseDown(Page page, String key) {
        CursorPos cursorPos = null;
        //如果page不是叶子节点就继续搜索
        while (!page.isLeaf()) {
            //使用二分查找搜索
            int index = page.binarySearch(key) + 1;
            if (index < 0) {
                //如果index是负值，表示要插入的位置
                index = -index;
            }
            cursorPos = new CursorPos(page, index, cursorPos);
            // 这里读入子page(有IO操作)
            //找index对应的孩子节点 如果孩子节点不在内存中，便从磁盘加载
            page = page.getChildPage(index);
        }
        /**
         * 走完上面的循环后，page表示的便是叶子节点，在叶子节点上执行一次二分查找，定位到要插入的位置
         * 参数cursorPos表示当前叶子节点的父节点
         */
        return new CursorPos(page, page.binarySearch(key), cursorPos);
    }

    /**
     * Calculate the memory used by changes that are not yet stored.
     *
     * @param version the version
     * @return the amount of memory
     */
    int processRemovalInfo(long version) {
        int unsavedMemory = 0;
        for (CursorPos head = this; head != null; head = head.parent) {
            unsavedMemory += head.page.removePage(version);
        }
        return unsavedMemory;
    }

    @Override
    public String toString() {
        return "CursorPos{" +
                "page=" + page +
                ", index=" + index +
                ", parent=" + parent +
                '}';
    }
}
