package com.lhs.mvstore;

import com.lhs.util.DataUtils;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stored map.
 * <p>
 * All read and write operations can happen concurrently with all other
 * operations, without risk of corruption.
 *
 */
public class MVMap extends AbstractMap<String, String> implements ConcurrentMap<String, String> {

    /**
     * The store.
     */
    public final MVStore store;

    /**
     * Reference to the current root page.
     */
    //根page的引用
    private final AtomicReference<RootReference> root;

    private final int id;
    private final long createVersion;
    private final int keysPerPage;
    private final boolean singleWriter;
    private final String[] keysBuffer;
    private final String[] valuesBuffer;

    private final Object lock = new Object();
    private volatile boolean notificationRequested;

    /**
     * Whether the map is closed. Volatile so we don't accidentally write to a
     * closed map in multithreaded mode.
     */
    private volatile boolean closed;
    private boolean readOnly;
    private boolean isVolatile;
    /**
     * This designates the "last stored" version for a store which was
     * just open for the first time.
     */
    public static final long INITIAL_VERSION = -1;


    protected MVMap(Map<String, Object> config) {
        this((MVStore) config.get("store"),
                DataUtils.readHexInt(config, "id", 0),
                DataUtils.readHexLong(config, "createVersion", 0),
                new AtomicReference<>(),
                ((MVStore) config.get("store")).getKeysPerPage(),
                config.containsKey("singleWriter") && (Boolean) config.get("singleWriter")
        );
        setInitialRoot(createEmptyLeaf(), store.getCurrentVersion());
    }

    // constructor for cloneIt()
    @SuppressWarnings("CopyConstructorMissesField")
    protected MVMap(MVMap source) {
        this(source.store, source.id, source.createVersion,
                new AtomicReference<>(source.root.get()), source.keysPerPage, source.singleWriter);
    }

    // meta map constructor
    MVMap(MVStore store, int id) {
        this(store, id, 0, new AtomicReference<>(), store.getKeysPerPage(), false);
        setInitialRoot(createEmptyLeaf(), store.getCurrentVersion());
    }

    private MVMap(MVStore store, int id, long createVersion,
                  AtomicReference<RootReference> root, int keysPerPage, boolean singleWriter) {
        this.store = store;
        this.id = id;
        this.createVersion = createVersion;
        this.root = root;
        this.keysPerPage = keysPerPage;
        this.keysBuffer = singleWriter ? new String[keysPerPage] : null;
        this.valuesBuffer = singleWriter ? new String[keysPerPage] : null;
        this.singleWriter = singleWriter;
    }

    /**
     * Clone the current map.
     *
     * @return clone of this.
     */
    protected MVMap cloneIt() {
        return new MVMap(this);
    }

    /**
     * Get the metadata key for the root of the given map id.
     *
     * @param mapId the map id
     * @return the metadata key
     */
    static String getMapRootKey(int mapId) {
        return DataUtils.META_ROOT + Integer.toHexString(mapId);
    }

    /**
     * Get the metadata key for the given map id.
     *
     * @param mapId the map id
     * @return the metadata key
     */
    static String getMapKey(int mapId) {
        return DataUtils.META_MAP + Integer.toHexString(mapId);
    }

    /**
     * Add or replace a key-value pair.
     *
     * @param key   the key (may not be null)
     * @param value the value (may not be null)
     * @return the old value if the key existed, or null otherwise
     */
    @Override
    public String put(String key, String value) {
        DataUtils.checkArgument(value != null, "The value may not be null");
        return operate(key, value, DecisionMaker.PUT);
    }

    /**
     * Get the first key, or null if the map is empty.
     *
     * @return the first key, or null
     */
    public final String firstKey() {
        return getFirstLast(true);
    }

    /**
     * Get the last key, or null if the map is empty.
     *
     * @return the last key, or null
     */
    public final String lastKey() {
        return getFirstLast(false);
    }

    /**
     * Get the key at the given index.
     * <p>
     * This is a O(log(size)) operation.
     *
     * @param index the index
     * @return the key
     */
    public final String getKey(long index) {
        if (index < 0 || index >= sizeAsLong()) {
            return null;
        }
        Page p = getRootPage();
        long offset = 0;
        while (true) {
            if (p.isLeaf()) {
                if (index >= offset + p.getKeyCount()) {
                    return null;
                }
                String key = p.getKey((int) (index - offset));
                return key;
            }
            int i = 0, size = getChildPageCount(p);
            for (; i < size; i++) {
                long c = p.getCounts(i);
                if (index < c + offset) {
                    break;
                }
                offset += c;
            }
            if (i == size) {
                return null;
            }
            p = p.getChildPage(i);
        }
    }

    /**
     * Get the key list. The list is a read-only representation of all keys.
     * <p>
     * The get and indexOf methods are O(log(size)) operations. The result of
     * indexOf is cast to an int.
     *
     * @return the key list
     */
    public final List<String> keyList() {
        return new AbstractList<String>() {

            @Override
            public String get(int index) {
                return getKey(index);
            }

            @Override
            public int size() {
                return MVMap.this.size();
            }

            @Override
            @SuppressWarnings("unchecked")
            public int indexOf(Object key) {
                return (int) getKeyIndex((String) key);
            }

        };
    }

    /**
     * Get the index of the given key in the map.
     * <p>
     * This is a O(log(size)) operation.
     * <p>
     * If the key was found, the returned value is the index in the key array.
     * If not found, the returned value is negative, where -1 means the provided
     * key is smaller than any keys. See also Arrays.binarySearch.
     *
     * @param key the key
     * @return the index
     */
    public final long getKeyIndex(String key) {
        Page p = getRootPage();
        if (p.getTotalCount() == 0) {
            return -1;
        }
        long offset = 0;
        while (true) {
            int x = p.binarySearch(key);
            if (p.isLeaf()) {
                if (x < 0) {
                    offset = -offset;
                }
                return offset + x;
            }
            if (x++ < 0) {
                x = -x;
            }
            for (int i = 0; i < x; i++) {
                offset += p.getCounts(i);
            }
            p = p.getChildPage(x);
        }
    }

    /**
     * Get the first (lowest) or last (largest) key.
     *
     * @param first whether to retrieve the first key
     * @return the key, or null if the map is empty
     */
    private String getFirstLast(boolean first) {
        Page p = getRootPage();
        return getFirstLast(p, first);
    }

    private String getFirstLast(Page p, boolean first) {
        if (p.getTotalCount() == 0) {
            return null;
        }
        while (true) {
            if (p.isLeaf()) {
                return p.getKey(first ? 0 : p.getKeyCount() - 1);
            }
            p = p.getChildPage(first ? 0 : getChildPageCount(p) - 1);
        }
    }

    public int binarySearch(String key, Object storageObj, int size, int initialGuess) {
        String[] storage = (String[]) (storageObj);
        int low = 0;
        int high = size - 1;
        // the cached index minus one, so that
        // for the first time (when cachedCompare is 0),
        // the default value is used
        int x = initialGuess - 1;
        if (x < 0 || x > high) {
            x = high >>> 1;
        }
        while (low <= high) {
            // int compare = key.compareTo(storage[x]);
            int compare = compareString(key, storage[x]);
            if (compare > 0) {
                low = x + 1;
            } else if (compare < 0) {
                high = x - 1;
            } else {
                return x;
            }
            x = (low + high) >>> 1;
        }
        return -(low + 1);
    }

    public int compareString(String key, String other) {
        if (isNumeric(key)) {
            return new BigDecimal(key).compareTo(new BigDecimal(other));
        }
        return key.compareTo(other);
    }
    private boolean isNumeric(String str) {
        String bigStr;
        try {
            bigStr = new BigDecimal(str).toString();
        } catch (Exception e) {
            return false;//异常 说明包含非数字。
        }
        return true;
    }

    /**
     * Get the smallest key that is larger than the given key (next key in ascending order),
     * or null if no such key exists.
     *
     * @param key the key
     * @return the result
     */
    public final String higherKey(String key) {
        return getMinMax(key, false, true);
    }

    /**
     * Get the smallest key that is larger than the given key, for the given
     * root page, or null if no such key exists.
     *
     * @param rootRef the root reference of the map
     * @param key     to start from
     * @return the result
     */
    public final String higherKey(RootReference rootRef, String key) {
        return getMinMax(rootRef, key, false, true);
    }

    /**
     * Get the smallest key that is larger or equal to this key.
     *
     * @param key the key
     * @return the result
     */
    public final String ceilingKey(String key) {
        return getMinMax(key, false, false);
    }

    /**
     * Get the largest key that is smaller or equal to this key.
     *
     * @param key the key
     * @return the result
     */
    public final String floorKey(String key) {
        return getMinMax(key, true, false);
    }

    /**
     * Get the largest key that is smaller than the given key, or null if no
     * such key exists.
     *
     * @param key the key
     * @return the result
     */
    public final String lowerKey(String key) {
        return getMinMax(key, true, true);
    }

    /**
     * Get the largest key that is smaller than the given key, for the given
     * root page, or null if no such key exists.
     *
     * @param rootRef the root page
     * @param key     the key
     * @return the result
     */
    public final String lowerKey(RootReference rootRef, String key) {
        return getMinMax(rootRef, key, true, true);
    }

    /**
     * Get the smallest or largest key using the given bounds.
     *
     * @param key       the key
     * @param min       whether to retrieve the smallest key
     * @param excluding if the given upper/lower bound is exclusive
     * @return the key, or null if no such key exists
     */
    private String getMinMax(String key, boolean min, boolean excluding) {
        return getMinMax(flushAndGetRoot(), key, min, excluding);
    }

    private String getMinMax(RootReference rootRef, String key, boolean min, boolean excluding) {
        return getMinMax(rootRef.root, key, min, excluding);
    }

    private String getMinMax(Page p, String key, boolean min, boolean excluding) {
        int x = p.binarySearch(key);
        if (p.isLeaf()) {
            if (x < 0) {
                x = -x - (min ? 2 : 1);
            } else if (excluding) {
                x += min ? -1 : 1;
            }
            if (x < 0 || x >= p.getKeyCount()) {
                return null;
            }
            return p.getKey(x);
        }
        if (x++ < 0) {
            x = -x;
        }
        while (true) {
            if (x < 0 || x >= getChildPageCount(p)) {
                return null;
            }
            String k = getMinMax(p.getChildPage(x), key, min, excluding);
            if (k != null) {
                return k;
            }
            x += min ? -1 : 1;
        }
    }


    /**
     * Get the value for the given key, or null if not found.
     *
     * @param key the key
     * @return the value, or null if not found
     * @throws ClassCastException if type of the specified key is not compatible with this map
     */
    @SuppressWarnings("unchecked")
    @Override
    public final String get(Object key) {
        return get(getRootPage(), (String) key);
    }

    /**
     * Get the value for the given key from a snapshot, or null if not found.
     *
     * @param p   the root of a snapshot
     * @param key the key
     * @return the value, or null if not found
     * @throws ClassCastException if type of the specified key is not compatible with this map
     */
    public String get(Page p, String key) {
        return Page.get(p, key);
    }

    @Override
    public final boolean containsKey(Object key) {
        return get(key) != null;
    }

    /**
     * Remove all entries.
     */
    @Override
    public void clear() {
        clearIt();
    }

    /**
     * Remove all entries and return the root reference.
     *
     * @return the new root reference
     */
    RootReference clearIt() {
        Page emptyRootPage = createEmptyLeaf();
        int attempt = 0;
        while (true) {
            RootReference rootReference = flushAndGetRoot();
            if (rootReference.getTotalCount() == 0) {
                return rootReference;
            }
            boolean locked = rootReference.isLockedByCurrentThread();
            if (!locked) {
                if (attempt++ == 0) {
                    beforeWrite();
                } else if (attempt > 3 || rootReference.isLocked()) {
                    rootReference = lockRoot(rootReference, attempt);
                    locked = true;
                }
            }
            Page rootPage = rootReference.root;
            long version = rootReference.version;
            try {
                if (!locked) {
                    rootReference = rootReference.updateRootPage(emptyRootPage, attempt);
                    if (rootReference == null) {
                        continue;
                    }
                }
                store.registerUnsavedMemory(rootPage.removeAllRecursive(version));
                rootPage = emptyRootPage;
                return rootReference;
            } finally {
                if (locked) {
                    unlockRoot(rootPage);
                }
            }
        }
    }

    /**
     * Close the map. Accessing the data is still possible (to allow concurrent
     * reads), but it is marked as closed.
     */
    final void close() {
        closed = true;
    }

    public final boolean isClosed() {
        return closed;
    }

    /**
     * Remove a key-value pair, if the key exists.
     *
     * @param key the key (may not be null)
     * @return the old value if the key existed, or null otherwise
     * @throws ClassCastException if type of the specified key is not compatible with this map
     */
    @Override
    @SuppressWarnings("unchecked")
    public String remove(Object key) {
        return operate((String) key, null, DecisionMaker.REMOVE);
    }

    /**
     * Add a key-value pair if it does not yet exist.
     *
     * @param key   the key (may not be null)
     * @param value the new value
     * @return the old value if the key existed, or null otherwise
     */
    @Override
    public final String putIfAbsent(String key, String value) {
        return operate(key, value, DecisionMaker.IF_ABSENT);
    }

    /**
     * Remove a key-value pair if the value matches the stored one.
     *
     * @param key   the key (may not be null)
     * @param value the expected value
     * @return true if the item was removed
     */
    @SuppressWarnings("unchecked")
    @Override
    public boolean remove(Object key, Object value) {
        EqualsDecisionMaker decisionMaker = new EqualsDecisionMaker((String) value);
        operate((String) key, null, decisionMaker);
        return decisionMaker.getDecision() != Decision.ABORT;
    }

    /**
     * Check whether the two values are equal.
     *
     * @param a        the first value
     * @param b        the second value
     * @return true if they are equal
     */
    static  boolean areValuesEqual(String a, String b) {
        return a == b
                || a != null && b != null && a.compareTo(b) == 0;
    }

    /**
     * Replace a value for an existing key, if the value matches.
     *
     * @param key      the key (may not be null)
     * @param oldValue the expected value
     * @param newValue the new value
     * @return true if the value was replaced
     */
    @Override
    public final boolean replace(String key, String oldValue, String newValue) {
        EqualsDecisionMaker decisionMaker = new EqualsDecisionMaker(oldValue);
        String result = operate(key, newValue, decisionMaker);
        boolean res = decisionMaker.getDecision() != Decision.ABORT;
        assert !res || areValuesEqual(oldValue, result) : oldValue + " != " + result;
        return res;
    }

    /**
     * Replace a value for an existing key.
     *
     * @param key   the key (may not be null)
     * @param value the new value
     * @return the old value, if the value was replaced, or null
     */
    @Override
    public final String replace(String key, String value) {
        return operate(key, value, DecisionMaker.IF_PRESENT);
    }

    /**
     * Compare two keys.
     *
     * @param a the first key
     * @param b the second key
     * @return -1 if the first key is smaller, 1 if bigger, 0 if equal
     */
    @SuppressWarnings("unused")
    final int compare(String a, String b) {
        return a.compareTo(b);
    }

    /**
     * Get the key type.
     *
     * @return the key type
     */
    public final String[] getKeyType() {
        return new String[0];
    }

    /**
     * Get the value type.
     *
     * @return the value type
     */
    public final String[] getValueType() {
        return new String[0];
    }

    /**
     * read String from ByteBuffer
     *
     * @param buff param
     * @return String
     */
    public String read(ByteBuffer buff) {
        return DataUtils.readString(buff);
    }

    /**
     * Read a list of objects.
     *
     * @param buff    the target buffer
     * @param storage the objects
     * @param len     the number of objects to read
     */
    public void read(ByteBuffer buff, Object storage, int len) {
        for (int i = 0; i < len; i++) {
            ((String[]) (storage))[i] = read(buff);
        }
    }

    public void write(WriteBuffer buff, String s) {
        int len = s.length();
        buff.putVarInt(len).putStringData(s, len);
    }

    public void write(WriteBuffer buff, Object storage, int len) {
        for (int i = 0; i < len; i++) {
            write(buff, ((String[]) (storage))[i]);
        }
    }

    boolean isSingleWriter() {
        return singleWriter;
    }

    /**
     * Read a page.
     *
     * @param pos the position of the page
     * @return the page
     */
    final Page readPage(long pos) {
        return store.readPage(this, pos);
    }

    /**
     * Set the position of the root page.
     *
     * @param rootPos the position, 0 for empty
     * @param version to set for this map
     */
    final void setRootPos(long rootPos, long version) {
        //根据page的地址rootPos去存储文件读入
        Page root = readOrCreateRootPage(rootPos);
        //将读出来的page传入方法setInitialRoot
        setInitialRoot(root, version);
        setWriteVersion(store.getCurrentVersion());
    }

    /**
     * 创建空root Page或读取root Page
     *
     * @param rootPos
     * @return
     */
    private Page readOrCreateRootPage(long rootPos) {
        Page root = rootPos == 0 ? createEmptyLeaf() : readPage(rootPos);
        return root;
    }

    /**
     * Iterate over a number of keys.
     *
     * @param from the first key to return
     * @return the iterator
     */
    public final Iterator<String> keyIterator(String from) {
        return cursor(from, null, false);
    }

    /**
     * Iterate over a number of keys in reverse order
     *
     * @param from the first key to return
     * @return the iterator
     */
    public final Iterator<String> keyIteratorReverse(String from) {
        return cursor(from, null, true);
    }

    final boolean rewritePage(long pagePos) {
        Page p = readPage(pagePos);
        if (p.getKeyCount() == 0) {
            return true;
        }
        assert p.isSaved();
        String key = p.getKey(0);
        if (!isClosed()) {
            RewriteDecisionMaker<String> decisionMaker = new RewriteDecisionMaker<>(p.getPos());
            String result = operate(key, null, decisionMaker);
            boolean res = decisionMaker.getDecision() != Decision.ABORT;
            assert !res || result != null;
            return res;
        }
        return false;
    }

    /**
     * Get a cursor to iterate over a number of keys and values in the latest version of this map.
     *
     * @param from the first key to return
     * @return the cursor
     */
    public final Cursor cursor(String from) {
        return cursor(from, null, false);
    }

    /**
     * Get a cursor to iterate over a number of keys and values in the latest version of this map.
     *
     * @param from    the first key to return
     * @param to      the last key to return
     * @param reverse if true, iterate in reverse (descending) order
     * @return the cursor
     */
    public final Cursor cursor(String from, String to, boolean reverse) {
        return cursor(flushAndGetRoot(), from, to, reverse);
    }

    /**
     * Get a cursor to iterate over a number of keys and values.
     *
     * @param rootReference of this map's version to iterate over
     * @param from          the first key to return
     * @param to            the last key to return
     * @param reverse       if true, iterate in reverse (descending) order
     * @return the cursor
     */
    public Cursor cursor(RootReference rootReference, String from, String to, boolean reverse) {
        return new Cursor(rootReference, from, to, reverse);
    }

    @Override
    public final Set<Entry<String, String>> entrySet() {
        final RootReference rootReference = flushAndGetRoot();
        return new AbstractSet<Entry<String, String>>() {

            @Override
            public Iterator<Entry<String, String>> iterator() {
                final Cursor cursor = cursor(rootReference, null, null, false);
                return new Iterator<Entry<String, String>>() {

                    @Override
                    public boolean hasNext() {
                        return cursor.hasNext();
                    }

                    @Override
                    public Entry<String, String> next() {
                        String k = cursor.next();
                        return new SimpleImmutableEntry<>(k, cursor.getValue());
                    }
                };

            }

            @Override
            public int size() {
                return MVMap.this.size();
            }

            @Override
            public boolean contains(Object o) {
                return MVMap.this.containsKey(o);
            }

        };

    }

    @Override
    public Set<String> keySet() {
        final RootReference rootReference = flushAndGetRoot();
        return new AbstractSet<String>() {

            @Override
            public Iterator<String> iterator() {
                return cursor(rootReference, null, null, false);
            }

            @Override
            public int size() {
                return MVMap.this.size();
            }

            @Override
            public boolean contains(Object o) {
                return MVMap.this.containsKey(o);
            }

        };
    }

    /**
     * Get the map name.
     *
     * @return the name
     */
    public final String getName() {
        return store.getMapName(id);
    }

    public final MVStore getStore() {
        return store;
    }

    protected final boolean isPersistent() {
        return store.getFileStore() != null && !isVolatile;
    }

    /**
     * Get the map id. Please note the map id may be different after compacting
     * a store.
     *
     * @return the map id
     */
    public final int getId() {
        return id;
    }

    /**
     * The current root page (may not be null).
     *
     * @return the root page
     */
    public final Page getRootPage() {
        return flushAndGetRoot().root;
    }

    public RootReference getRoot() {
        return root.get();
    }

    /**
     * Get the root reference, flushing any current append buffer.
     *
     * @return current root reference
     */
    public RootReference flushAndGetRoot() {
        RootReference rootReference = getRoot();
        if (singleWriter && rootReference.getAppendCounter() > 0) {
            return flushAppendBuffer(rootReference, true);
        }
        return rootReference;
    }

    /**
     * Set the initial root.
     *
     * @param rootPage root page
     * @param version  initial version
     */
    final void setInitialRoot(Page rootPage, long version) {
        root.set(new RootReference(rootPage, version));
    }

    /**
     * Compare and set the root reference.
     *
     * @param expectedRootReference the old (expected)
     * @param updatedRootReference  the new
     * @return whether updating worked
     */
    final boolean compareAndSetRoot(RootReference expectedRootReference,
                                    RootReference updatedRootReference) {
        return root.compareAndSet(expectedRootReference, updatedRootReference);
    }

    /**
     * Rollback to the given version.
     *
     * @param version the version
     */
    final void rollbackTo(long version) {
        // check if the map was removed and re-created later ?
        if (version > createVersion) {
            rollbackRoot(version);
        }
    }

    /**
     * Roll the root back to the specified version.
     *
     * @param version to rollback to
     * @return true if rollback was a success, false if there was not enough in-memory history
     */
    boolean rollbackRoot(long version) {
        RootReference rootReference = flushAndGetRoot();
        RootReference previous;
        while (rootReference.version >= version && (previous = rootReference.previous) != null) {
            if (root.compareAndSet(rootReference, previous)) {
                rootReference = previous;
                closed = false;
            }
        }
        setWriteVersion(version);
        return rootReference.version < version;
    }

    /**
     * Use the new root page from now on.
     *
     * @param <K>                   the key class
     * @param <V>                   the value class
     * @param expectedRootReference expected current root reference
     * @param newRootPage           the new root page
     * @param attemptUpdateCounter  how many attempt (including current)
     *                              were made to update root
     * @return new RootReference or null if update failed
     */
    protected static <K, V> boolean updateRoot(RootReference expectedRootReference, Page newRootPage,
                                               int attemptUpdateCounter) {
        return expectedRootReference.updateRootPage(newRootPage, attemptUpdateCounter) != null;
    }

    /**
     * Forget those old versions that are no longer needed.
     *
     * @param rootReference to inspect
     */
    private void removeUnusedOldVersions(RootReference rootReference) {
        rootReference.removeUnusedOldVersions(store.getOldestVersionToKeep());
    }

    public final boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Set the volatile flag of the map.
     *
     * @param isVolatile the volatile flag
     */
    public final void setVolatile(boolean isVolatile) {
        this.isVolatile = isVolatile;
    }

    /**
     * Whether this is volatile map, meaning that changes
     * are not persisted. By default (even if the store is not persisted),
     * maps are not volatile.
     *
     * @return whether this map is volatile
     */
    public final boolean isVolatile() {
        return isVolatile;
    }

    /**
     * This method is called before writing to the map. The default
     * implementation checks whether writing is allowed, and tries
     * to detect concurrent modification.
     *
     * @throws UnsupportedOperationException if the map is read-only,
     *                                       or if another thread is concurrently writing
     */
    protected final void beforeWrite() {
        assert !getRoot().isLockedByCurrentThread() : getRoot();
        if (closed) {
            int id = getId();
            String mapName = store.getMapName(id);
            throw DataUtils.newMVStoreException(
                    DataUtils.ERROR_CLOSED, "Map {0}({1}) is closed. {2}", mapName, id, store.getPanicException());
        }
        if (readOnly) {
            throw DataUtils.newUnsupportedOperationException(
                    "This map is read-only");
        }
        store.beforeWrite(this);
    }

    @Override
    public final int hashCode() {
        return id;
    }

    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    /**
     * Get the number of entries, as a integer. {@link Integer#MAX_VALUE} is
     * returned if there are more than this entries.
     *
     * @return the number of entries, as an integer
     * @see #sizeAsLong()
     */
    @Override
    public final int size() {
        long size = sizeAsLong();
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    /**
     * Get the number of entries, as a long.
     *
     * @return the number of entries
     */
    public final long sizeAsLong() {
        return getRoot().getTotalCount();
    }

    @Override
    public boolean isEmpty() {
        return sizeAsLong() == 0;
    }

    final long getCreateVersion() {
        return createVersion;
    }

    /**
     * Open an old version for the given map.
     * It will restore map at last known state of the version specified.
     * (at the point right before the commit() call, which advanced map to the next version)
     * Map is opened in read-only mode.
     *
     * @param version the version
     * @return the map
     */
    public final MVMap openVersion(long version) {
        if (readOnly) {
            throw DataUtils.newUnsupportedOperationException(
                    "This map is read-only; need to call " +
                            "the method on the writable map");
        }
        DataUtils.checkArgument(version >= createVersion,
                "Unknown version {0}; this map was created in version is {1}",
                version, createVersion);
        RootReference rootReference = flushAndGetRoot();
        removeUnusedOldVersions(rootReference);
        RootReference previous;
        while ((previous = rootReference.previous) != null && previous.version >= version) {
            rootReference = previous;
        }
        if (previous == null && version < store.getOldestVersionToKeep()) {
            throw DataUtils.newIllegalArgumentException("Unknown version {0}", version);
        }
        MVMap m = openReadOnly(rootReference.root, version);
        assert m.getVersion() <= version : m.getVersion() + " <= " + version;
        return m;
    }

    /**
     * Open a copy of the map in read-only mode.
     *
     * @param rootPos position of the root page
     * @param version to open
     * @return the opened map
     */
    final MVMap openReadOnly(long rootPos, long version) {
        Page root = readOrCreateRootPage(rootPos);
        return openReadOnly(root, version);
    }

    private MVMap openReadOnly(Page root, long version) {
        MVMap m = cloneIt();
        m.readOnly = true;
        m.setInitialRoot(root, version);
        return m;
    }

    /**
     * Get version of the map, which is the version of the store,
     * at the moment when map was modified last time.
     *
     * @return version
     */
    public final long getVersion() {
        return getRoot().getVersion();
    }

    /**
     * Does the root have changes since the specified version?
     *
     * @param version root version
     * @return true if has changes
     */
    final boolean hasChangesSince(long version) {
        return getRoot().hasChangesSince(version, isPersistent());
    }

    /**
     * Get the child page count for this page. This is to allow another map
     * implementation to override the default, in case the last child is not to
     * be used.
     *
     * @param p the page
     * @return the number of direct children
     */
    protected int getChildPageCount(Page p) {
        return p.getRawChildPageCount();
    }

    /**
     * Get the map type. When opening an existing map, the map type must match.
     *
     * @return the map type
     */
    public String getType() {
        return null;
    }

    /**
     * Get the map metadata as a string.
     *
     * @param name the map name (or null)
     * @return the string
     */
    protected String asString(String name) {
        StringBuilder buff = new StringBuilder();
        if (name != null) {
            DataUtils.appendMap(buff, "name", name);
        }
        if (createVersion != 0) {
            DataUtils.appendMap(buff, "createVersion", createVersion);
        }
        String type = getType();
        if (type != null) {
            DataUtils.appendMap(buff, "type", type);
        }
        return buff.toString();
    }

    final RootReference setWriteVersion(long writeVersion) {
        int attempt = 0;
        while (true) {
            //获取rootReference
            RootReference rootReference = flushAndGetRoot();
            if (rootReference.version >= writeVersion) {
                return rootReference;
            } else if (isClosed()) {
                // map was closed a while back and can not possibly be in use by now
                // it's time to remove it completely from the store (it was anonymous already)
                if (rootReference.getVersion() + 1 < store.getOldestVersionToKeep()) {
                    store.deregisterMapRoot(id);
                    return null;
                }
            }

            RootReference lockedRootReference = null;
            if (++attempt > 3 || rootReference.isLocked()) {
                lockedRootReference = lockRoot(rootReference, attempt);
                rootReference = flushAndGetRoot();
            }

            try {
                rootReference = rootReference.tryUnlockAndUpdateVersion(writeVersion, attempt);
                if (rootReference != null) {
                    lockedRootReference = null;
                    removeUnusedOldVersions(rootReference);
                    return rootReference;
                }
            } finally {
                if (lockedRootReference != null) {
                    unlockRoot();
                }
            }
        }
    }

    /**
     * Create empty leaf node page.
     *
     * 创建一个空page
     *
     * @return new page
     */
    protected Page createEmptyLeaf() {
        return Page.createEmptyLeaf(this);
    }

    /**
     * Create empty internal node page.
     *
     * @return new page
     */
    protected Page createEmptyNode() {
        return Page.createEmptyNode(this);
    }

    /**
     * Copy a map. All pages are copied.
     *
     * @param sourceMap the source map
     */
    final void copyFrom(MVMap sourceMap) {
        MVStore.TxCounter txCounter = store.registerVersionUsage();
        try {
            beforeWrite();
            copy(sourceMap.getRootPage(), null, 0);
        } finally {
            store.deregisterVersionUsage(txCounter);
        }
    }

    private void copy(Page source, Page parent, int index) {
        Page target = source.copy(this);
        if (parent == null) {
            setInitialRoot(target, INITIAL_VERSION);
        } else {
            parent.setChild(index, target);
        }
        if (!source.isLeaf()) {
            for (int i = 0; i < getChildPageCount(target); i++) {
                if (source.getChildPagePos(i) != 0) {
                    // position 0 means no child
                    // (for example the last entry of an r-tree node)
                    // (the MVMap is also used for r-trees for compacting)
                    copy(source.getChildPage(i), target, i);
                }
            }
            target.setComplete();
        }
        store.registerUnsavedMemory(target.getMemory());
        if (store.isSaveNeeded()) {
            store.commit();
        }
    }

    /**
     * If map was used in append mode, this method will ensure that append buffer
     * is flushed - emptied with all entries inserted into map as a new leaf.
     *
     * @param rootReference current RootReference
     * @param fullFlush     whether buffer should be completely flushed,
     *                      otherwise just a single empty slot is required
     * @return potentially updated RootReference
     */
    private RootReference flushAppendBuffer(RootReference rootReference, boolean fullFlush) {
        boolean preLocked = rootReference.isLockedByCurrentThread();
        boolean locked = preLocked;
        int keysPerPage = store.getKeysPerPage();
        try {
            IntValueHolder unsavedMemoryHolder = new IntValueHolder();
            int attempt = 0;
            int keyCount;
            int availabilityThreshold = fullFlush ? 0 : keysPerPage - 1;
            while ((keyCount = rootReference.getAppendCounter()) > availabilityThreshold) {
                if (!locked) {
                    // instead of just calling lockRoot() we loop here and check if someone else
                    // already flushed the buffer, then we don't need a lock
                    rootReference = tryLock(rootReference, ++attempt);
                    if (rootReference == null) {
                        rootReference = getRoot();
                        continue;
                    }
                    locked = true;
                }

                Page rootPage = rootReference.root;
                long version = rootReference.version;
                CursorPos pos = rootPage.getAppendCursorPos(null);
                assert pos != null;
                assert pos.index < 0 : pos.index;
                int index = -pos.index - 1;
                assert index == pos.page.getKeyCount() : index + " != " + pos.page.getKeyCount();
                Page p = pos.page;
                CursorPos tip = pos;
                pos = pos.parent;

                int remainingBuffer = 0;
                Page page = null;
                int available = keysPerPage - p.getKeyCount();
                if (available > 0) {
                    p = p.copy();
                    if (keyCount <= available) {
                        p.expand(keyCount, keysBuffer, valuesBuffer);
                    } else {
                        p.expand(available, keysBuffer, valuesBuffer);
                        keyCount -= available;
                        if (fullFlush) {
                            String[] keys = p.createKeyStorage(keyCount);
                            String[] values = p.createValueStorage(keyCount);
                            System.arraycopy(keysBuffer, available, keys, 0, keyCount);
                            System.arraycopy(valuesBuffer, available, values, 0, keyCount);
                            page = Page.createLeaf(this, keys, values, 0);
                        } else {
                            System.arraycopy(keysBuffer, available, keysBuffer, 0, keyCount);
                            System.arraycopy(valuesBuffer, available, valuesBuffer, 0, keyCount);
                            remainingBuffer = keyCount;
                        }
                    }
                } else {
                    tip = tip.parent;
                    page = Page.createLeaf(this,
                            Arrays.copyOf(keysBuffer, keyCount),
                            Arrays.copyOf(valuesBuffer, keyCount),
                            0);
                }

                unsavedMemoryHolder.value = 0;
                if (page != null) {
                    assert page.map == this;
                    assert page.getKeyCount() > 0;
                    String key = page.getKey(0);
                    unsavedMemoryHolder.value += page.getMemory();
                    while (true) {
                        if (pos == null) {
                            if (p.getKeyCount() == 0) {
                                p = page;
                            } else {
                                String[] keys = p.createKeyStorage(1);
                                keys[0] = key;
                                Page.PageReference[] children = Page.createRefStorage(2);
                                children[0] = new Page.PageReference<>(p);
                                children[1] = new Page.PageReference<>(page);
                                unsavedMemoryHolder.value += p.getMemory();
                                p = Page.createNode(this, keys, children, p.getTotalCount() + page.getTotalCount(), 0);
                            }
                            break;
                        }
                        Page c = p;
                        p = pos.page;
                        index = pos.index;
                        pos = pos.parent;
                        p = p.copy();
                        p.setChild(index, page);
                        p.insertNode(index, key, c);
                        keyCount = p.getKeyCount();
                        int at = keyCount - (p.isLeaf() ? 1 : 2);
                        if (keyCount <= keysPerPage &&
                                (p.getMemory() < store.getMaxPageSize() || at <= 0)) {
                            break;
                        }
                        key = p.getKey(at);
                        page = p.split(at);
                        unsavedMemoryHolder.value += p.getMemory() + page.getMemory();
                    }
                }
                p = replacePage(pos, p, unsavedMemoryHolder);
                rootReference = rootReference.updatePageAndLockedStatus(p, preLocked || isPersistent(),
                        remainingBuffer);
                if (rootReference != null) {
                    // should always be the case, except for spurious failure?
                    locked = preLocked || isPersistent();
                    if (isPersistent() && tip != null) {
                        store.registerUnsavedMemory(unsavedMemoryHolder.value + tip.processRemovalInfo(version));
                    }
                    assert rootReference.getAppendCounter() <= availabilityThreshold;
                    break;
                }
                rootReference = getRoot();
            }
        } finally {
            if (locked && !preLocked) {
                rootReference = unlockRoot();
            }
        }
        return rootReference;
    }

    private static Page replacePage(CursorPos path, Page replacement,
                                    IntValueHolder unsavedMemoryHolder) {
        int unsavedMemory = replacement.isSaved() ? 0 : replacement.getMemory();
        while (path != null) {
            Page parent = path.page;
            // condition below should always be true, but older versions (up to 1.4.197)
            // may create single-childed (with no keys) internal nodes, which we skip here
            if (parent.getKeyCount() > 0) {
                Page child = replacement;
                replacement = parent.copy();
                replacement.setChild(path.index, child);
                unsavedMemory += replacement.getMemory();
            }
            path = path.parent;
        }
        unsavedMemoryHolder.value += unsavedMemory;
        return replacement;
    }

    /**
     * Appends entry to this map. this method is NOT thread safe and can not be used
     * neither concurrently, nor in combination with any method that updates this map.
     * Non-updating method may be used concurrently, but latest appended values
     * are not guaranteed to be visible.
     *
     * @param key   should be higher in map's order than any existing key
     * @param value to be appended
     */
    public void append(String key, String value) {
        if (singleWriter) {
            beforeWrite();
            RootReference rootReference = lockRoot(getRoot(), 1);
            int appendCounter = rootReference.getAppendCounter();
            try {
                if (appendCounter >= keysPerPage) {
                    rootReference = flushAppendBuffer(rootReference, false);
                    appendCounter = rootReference.getAppendCounter();
                    assert appendCounter < keysPerPage;
                }
                keysBuffer[appendCounter] = key;
                valuesBuffer[appendCounter] = value;
                ++appendCounter;
            } finally {
                unlockRoot(appendCounter);
            }
        } else {
            put(key, value);
        }
    }

    /**
     * Removes last entry from this map. this method is NOT thread safe and can not be used
     * neither concurrently, nor in combination with any method that updates this map.
     * Non-updating method may be used concurrently, but latest removal may not be visible.
     */
    public void trimLast() {
        if (singleWriter) {
            RootReference rootReference = getRoot();
            int appendCounter = rootReference.getAppendCounter();
            boolean useRegularRemove = appendCounter == 0;
            if (!useRegularRemove) {
                rootReference = lockRoot(rootReference, 1);
                try {
                    appendCounter = rootReference.getAppendCounter();
                    useRegularRemove = appendCounter == 0;
                    if (!useRegularRemove) {
                        --appendCounter;
                    }
                } finally {
                    unlockRoot(appendCounter);
                }
            }
            if (useRegularRemove) {
                Page lastLeaf = rootReference.root.getAppendCursorPos(null).page;
                assert lastLeaf.isLeaf();
                assert lastLeaf.getKeyCount() > 0;
                Object key = lastLeaf.getKey(lastLeaf.getKeyCount() - 1);
                remove(key);
            }
        } else {
            remove(lastKey());
        }
    }

    @Override
    public final String toString() {
        return asString(null);
    }

    /**
     * A builder for maps.
     *
     * @param <M> the map type
     * @param <K> the key type
     * @param <V> the value type
     */
    public interface MapBuilder<M extends MVMap, K, V> {

        /**
         * Create a new map of the given type.
         *
         * @param store  which will own this map
         * @param config configuration
         * @return the map
         */
        M create(MVStore store, Map<String, Object> config);

    }

    /**
     * A builder for this class.
     *
     * @param <K> the key type
     * @param <V> the value type
     */
    public abstract static class BasicBuilder<M extends MVMap, K, V> implements MapBuilder<M, K, V> {


        /**
         * Create a new builder with the default key and value data types.
         */
        protected BasicBuilder() {
            // ignore
        }


        /**
         * Set the key data type.
         *
         * @return this
         */
        public BasicBuilder<M, K, V> keyType() {
            //setKeyType(keyType);
            return this;
        }

        /**
         * Set the value data type.
         *
         * @return this
         */
        public BasicBuilder<M, K, V> valueType() {
            // setValueType(valueType);
            return this;
        }

        @Override
        public M create(MVStore store, Map<String, Object> config) {
            config.put("store", store);
            return create(config);
        }

        /**
         * Create map from config.
         *
         * @param config config map
         * @return new map
         */
        protected abstract M create(Map<String, Object> config);

    }

    /**
     * A builder for this class.
     *
     * @param <K> the key type
     * @param <V> the value type
     */
    public static class Builder<K, V> extends BasicBuilder<MVMap, K, V> {
        private boolean singleWriter;

        public Builder() {
//            ParameterizedType parameterizedType = (ParameterizedType) this.getClass().getGenericSuperclass();
//            String type1 = ((Class)parameterizedType.getActualTypeArguments()[0]).getSimpleName();
//            String type2 = ((Class)parameterizedType.getActualTypeArguments()[1]).getSimpleName();
//            String type3 = ((Class)parameterizedType.getActualTypeArguments()[2]).getSimpleName();
//            System.out.println(type1 + " " + type2 + "  " + type3);
        }


        /**
         * Set up this Builder to produce MVMap, which can be used in append mode
         * by a single thread.
         *
         * @return this Builder for chained execution
         */
        public Builder<K, V> singleWriter() {
            singleWriter = true;
            return this;
        }

        @Override
        protected MVMap create(Map<String, Object> config) {
            config.put("singleWriter", singleWriter);
            Object type = config.get("type");
            if (type == null || type.equals("rtree")) {
                return new MVMap(config);
            }
            throw new IllegalArgumentException("Incompatible map type");
        }
    }

    /**
     * The decision on what to do on an update.
     */
    public enum Decision {ABORT, REMOVE, PUT, REPEAT}

    /**
     * Class DecisionMaker provides callback interface (and should become a such in Java 8)
     * for MVMap.operate method.
     * It provides control logic to make a decision about how to proceed with update
     * at the point in execution when proper place and possible existing value
     * for insert/update/delete key is found.
     * Revised value for insert/update is also provided based on original input value
     * and value currently existing in the map.
     *
     * @param <V> value type of the map
     */
    public abstract static class DecisionMaker<V> {
        /**
         * Decision maker for transaction rollback.
         */
        public static final DecisionMaker<Object> DEFAULT = new DecisionMaker<Object>() {
            @Override
            public Decision decide(Object existingValue, Object providedValue) {
                return providedValue == null ? Decision.REMOVE : Decision.PUT;
            }

            @Override
            public String toString() {
                return "default";
            }
        };

        /**
         * Decision maker for put().
         */
        public static final DecisionMaker<Object> PUT = new DecisionMaker<Object>() {
            @Override
            public Decision decide(Object existingValue, Object providedValue) {
                return Decision.PUT;
            }

            @Override
            public String toString() {
                return "put";
            }
        };

        /**
         * Decision maker for remove().
         */
        public static final DecisionMaker<Object> REMOVE = new DecisionMaker<Object>() {
            @Override
            public Decision decide(Object existingValue, Object providedValue) {
                return Decision.REMOVE;
            }

            @Override
            public String toString() {
                return "remove";
            }
        };

        /**
         * Decision maker for putIfAbsent() key/value.
         */
        static final DecisionMaker<Object> IF_ABSENT = new DecisionMaker<Object>() {
            @Override
            public Decision decide(Object existingValue, Object providedValue) {
                return existingValue == null ? Decision.PUT : Decision.ABORT;
            }

            @Override
            public String toString() {
                return "if_absent";
            }
        };

        /**
         * Decision maker for replace().
         */
        static final DecisionMaker<Object> IF_PRESENT = new DecisionMaker<Object>() {
            @Override
            public Decision decide(Object existingValue, Object providedValue) {
                return existingValue != null ? Decision.PUT : Decision.ABORT;
            }

            @Override
            public String toString() {
                return "if_present";
            }
        };

        /**
         * Makes a decision about how to proceed with the update.
         *
         * @param existingValue the old value
         * @param providedValue the new value
         * @param tip           the cursor position
         * @return the decision
         */
        public Decision decide(V existingValue, V providedValue, CursorPos tip) {
            return decide(existingValue, providedValue);
        }

        /**
         * Makes a decision about how to proceed with the update.
         *
         * @param existingValue value currently exists in the map
         * @param providedValue original input value
         * @return PUT if a new value need to replace existing one or
         * a new value to be inserted if there is none
         * REMOVE if existing value should be deleted
         * ABORT if update operation should be aborted or repeated later
         * REPEAT if update operation should be repeated immediately
         */
        public abstract Decision decide(V existingValue, V providedValue);

        /**
         * Provides revised value for insert/update based on original input value
         * and value currently existing in the map.
         * This method is only invoked after call to decide(), if it returns PUT.
         *
         * @param existingValue value currently exists in the map
         * @param providedValue original input value
         * @param <T>           value type
         * @return value to be used by insert/update
         */
        public <T extends V> T selectValue(T existingValue, T providedValue) {
            return providedValue;
        }

        /**
         * Resets internal state (if any) of a this DecisionMaker to it's initial state.
         * This method is invoked whenever concurrent update failure is encountered,
         * so we can re-start update process.
         */
        public void reset() {
        }
    }

    /**
     * Add, replace or remove a key-value pair.
     *
     * @param key           the key (may not be null)
     * @param value         new value, it may be null when removal is intended
     * @param decisionMaker command object to make choices during transaction.
     * @return previous value, if mapping for that key existed, or null otherwise
     */
    public String operate(String key, String value, DecisionMaker<? super String> decisionMaker) {
        IntValueHolder unsavedMemoryHolder = new IntValueHolder();
        int attempt = 0;
        while (true) {
            // 首先获取这个MVMap 的RootReference，通过RootReference能找到这个MVMap的rootPage
            RootReference rootReference = flushAndGetRoot();
            boolean locked = rootReference.isLockedByCurrentThread();
            if (!locked) {
                if (attempt++ == 0) {
                    //写入前调用此方法，检查是否可以写入
                    beforeWrite();
                }
                if (attempt > 3 || rootReference.isLocked()) {
                    rootReference = lockRoot(rootReference, attempt);
                    locked = true;
                }
            }
            //该MVMap的rootPage
            Page rootPage = rootReference.root;
            long version = rootReference.version;
            CursorPos tip;
            String result;
            unsavedMemoryHolder.value = 0;
            try {
                // 从rootPage中开始获取key对应的value，这里用了一个CursorPos类来封装
                CursorPos pos = CursorPos.traverseDown(rootPage, key);
                if (!locked && rootReference != getRoot()) {
                    continue;
                }
                Page p = pos.page;
                int index = pos.index;
                tip = pos;
                pos = pos.parent;
                result = index < 0 ? null : p.getValue(index);
                Decision decision = decisionMaker.decide(result, value, tip);

                switch (decision) {
                    case REPEAT:
                        decisionMaker.reset();
                        continue;
                    case ABORT:
                        if (!locked && rootReference != getRoot()) {
                            decisionMaker.reset();
                            continue;
                        }
                        return result;
                    case REMOVE: {
                        if (index < 0) {
                            if (!locked && rootReference != getRoot()) {
                                decisionMaker.reset();
                                continue;
                            }
                            return null;
                        }

                        if (p.getTotalCount() == 1 && pos != null) {
                            int keyCount;
                            do {
                                p = pos.page;
                                index = pos.index;
                                pos = pos.parent;
                                keyCount = p.getKeyCount();
                                // condition below should always be false, but older
                                // versions (up to 1.4.197) may create
                                // single-childed (with no keys) internal nodes,
                                // which we skip here
                            } while (keyCount == 0 && pos != null);

                            if (keyCount <= 1) {
                                if (keyCount == 1) {
                                    assert index <= 1;
                                    p = p.getChildPage(1 - index);
                                } else {
                                    // if root happens to be such single-childed
                                    // (with no keys) internal node, then just
                                    // replace it with empty leaf
                                    p = Page.createEmptyLeaf(this);
                                }
                                break;
                            }
                        }
                        p = p.copy();
                        p.remove(index);
                        break;
                    }
                    case PUT: {
                        value = decisionMaker.selectValue(result, value);
                        // 最底层的叶子节点复制一份。这里是浅拷贝
                        p = p.copy();
                        if (index < 0) {
                            // 底层叶子节点page插入key，value。位置是-index-1
                            p.insertLeaf(-index - 1, key, value);
                            int keyCount;
                            //B+树的满节点操作。如果超过阈值，将会分裂
                            while ((keyCount = p.getKeyCount()) > store.getKeysPerPage()
                                    || p.getMemory() > store.getMaxPageSize()
                                    && keyCount > (p.isLeaf() ? 1 : 2)) {
                                long totalCount = p.getTotalCount();
                                // keyCount的一半，中间位置
                                int at = keyCount >> 1;
                                String k = p.getKey(at);
                                // page的分裂操作。分leaf和nonleaf
                                Page split = p.split(at);
                                unsavedMemoryHolder.value += p.getMemory() + split.getMemory();
                                //pos == null表示是根节点
                                if (pos == null) {
                                    //创建长度是1的keys数组
                                    String[] keys = p.createKeyStorage(1);
                                    keys[0] = k;
                                    Page.PageReference[] children = Page.createRefStorage(2);
                                    children[0] = new Page.PageReference<>(p);
                                    children[1] = new Page.PageReference<>(split);
                                    p = Page.createNode(this, keys, children, totalCount, 0);
                                    break;
                                }
                                //p是子节点
                                Page c = p;
                                // pos是父节点的CursorPos
                                p = pos.page;
                                index = pos.index;
                                pos = pos.parent;
                                // 父page 浅拷贝
                                p = p.copy();
                                // 父page 将index 指向新分裂出来的page split
                                p.setChild(index, split);
                                // 将子节点的key 放到父节点上。并且在父节点的children中的index指向子节点page
                                p.insertNode(index, k, c);
                            }
                        } else {
                            p.setValue(index, value);
                        }
                        break;
                    }
                }
                rootPage = replacePage(pos, p, unsavedMemoryHolder);
                if (!locked) {
                    rootReference = rootReference.updateRootPage(rootPage, attempt);
                    if (rootReference == null) {
                        decisionMaker.reset();
                        continue;
                    }
                }
                store.registerUnsavedMemory(unsavedMemoryHolder.value + tip.processRemovalInfo(version));
                return result;
            } finally {
                if (locked) {
                    unlockRoot(rootPage);
                }
            }
        }
    }

    private RootReference lockRoot(RootReference rootReference, int attempt) {
        //不断尝试加锁，直到加锁成功为止
        while (true) {
            //尝试加锁，如果加锁成功会返回一个不为null的对象，否则返回null
            RootReference lockedRootReference = tryLock(rootReference, attempt++);
            if (lockedRootReference != null) {
                return lockedRootReference;
            }
            //有可能其他线程在加锁中间会更新根页面，所以每次加锁失败重新获取新的RootReference对象
            rootReference = getRoot();
        }
    }

    /**
     * Try to lock the root.
     * 尝试对根页面加锁
     *
     * @param rootReference the old root reference
     * @param attempt       the number of attempts so far
     * @return the new root reference
     */
    protected RootReference tryLock(RootReference rootReference, int attempt) {
        RootReference lockedRootReference = rootReference.tryLock(attempt);
        if (lockedRootReference != null) {
            //加锁成功，则将最新的RootReference对象返回
            return lockedRootReference;
        }
        assert !rootReference.isLockedByCurrentThread() : rootReference;
        RootReference oldRootReference = rootReference.previous;
        int contention = 1;
        if (oldRootReference != null) {
            long updateAttemptCounter = rootReference.updateAttemptCounter -
                    oldRootReference.updateAttemptCounter;
            assert updateAttemptCounter >= 0 : updateAttemptCounter;
            long updateCounter = rootReference.updateCounter - oldRootReference.updateCounter;
            assert updateCounter >= 0 : updateCounter;
            assert updateAttemptCounter >= updateCounter : updateAttemptCounter + " >= " + updateCounter;
            contention += (int) ((updateAttemptCounter + 1) / (updateCounter + 1));
        }

        /**
         * 当满足一定的条件后，将线程休眠或者使线程等待
         * 防止线程不断的尝试加锁，给系统造成负担
         */
        if (attempt > 4) {
            if (attempt <= 12) {
                Thread.yield();
            } else if (attempt <= 70 - 2 * contention) {
                try {
                    Thread.sleep(contention);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            } else {
                synchronized (lock) {
                    notificationRequested = true;
                    try {
                        lock.wait(5);
                    } catch (InterruptedException ignore) {
                    }
                }
            }
        }
        return null;
    }

    /**
     * Unlock the root page, the new root being null.
     *
     * @return the new root reference (never null)
     */
    private RootReference unlockRoot() {
        return unlockRoot(null, -1);
    }

    /**
     * Unlock the root page.
     *
     * @param newRootPage the new root
     * @return the new root reference (never null)
     */
    protected RootReference unlockRoot(Page newRootPage) {
        return unlockRoot(newRootPage, -1);
    }

    private void unlockRoot(int appendCounter) {
        unlockRoot(null, appendCounter);
    }

    private RootReference unlockRoot(Page newRootPage, int appendCounter) {
        RootReference updatedRootReference;
        do {
            RootReference rootReference = getRoot();
            assert rootReference.isLockedByCurrentThread();
            updatedRootReference = rootReference.updatePageAndLockedStatus(
                    newRootPage == null ? rootReference.root : newRootPage,
                    false,
                    appendCounter == -1 ? rootReference.getAppendCounter() : appendCounter
            );
        } while (updatedRootReference == null);

        notifyWaiters();
        return updatedRootReference;
    }

    private void notifyWaiters() {
        if (notificationRequested) {
            synchronized (lock) {
                notificationRequested = false;
                lock.notify();
            }
        }
    }

    private static final class EqualsDecisionMaker extends DecisionMaker<String> {
        private final String expectedValue;
        private Decision decision;

        EqualsDecisionMaker(String expectedValue) {
            this.expectedValue = expectedValue;
        }

        @Override
        public Decision decide(String existingValue, String providedValue) {
            assert decision == null;
            decision = !areValuesEqual(expectedValue, existingValue) ? Decision.ABORT :
                    providedValue == null ? Decision.REMOVE : Decision.PUT;
            return decision;
        }

        @Override
        public void reset() {
            decision = null;
        }

        Decision getDecision() {
            return decision;
        }

        @Override
        public String toString() {
            return "equals_to " + expectedValue;
        }
    }

    private static final class RewriteDecisionMaker<V> extends DecisionMaker<V> {
        private final long pagePos;
        private Decision decision;

        RewriteDecisionMaker(long pagePos) {
            this.pagePos = pagePos;
        }

        @Override
        public Decision decide(V existingValue, V providedValue, CursorPos tip) {
            assert decision == null;
            decision = Decision.ABORT;
            if (!DataUtils.isLeafPosition(pagePos)) {
                while ((tip = tip.parent) != null) {
                    if (tip.page.getPos() == pagePos) {
                        decision = decide(existingValue, providedValue);
                        break;
                    }
                }
            } else if (tip.page.getPos() == pagePos) {
                decision = decide(existingValue, providedValue);
            }
            return decision;
        }

        @Override
        public Decision decide(V existingValue, V providedValue) {
            decision = existingValue == null ? Decision.ABORT : Decision.PUT;
            return decision;
        }

        @Override
        public <T extends V> T selectValue(T existingValue, T providedValue) {
            return existingValue;
        }

        @Override
        public void reset() {
            decision = null;
        }

        Decision getDecision() {
            return decision;
        }

        @Override
        public String toString() {
            return "rewrite";
        }
    }

    private static final class IntValueHolder {
        int value;
        IntValueHolder() {
        }
    }
}
