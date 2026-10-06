package com.tracel.plugin.adapter.rollback.structure.block;

/**
 * Open-addressed map from a packed chunk key to a non-null value.
 * <p>
 * A `null` slot is empty.
 */
final class LongMap<T> {
    private long[] keys = new long[16];
    private Object[] values = new Object[16];
    private int mask = 15;
    private int size;

    private static int mix(long key) {
        long hashed = key * 0x9E3779B97F4A7C15L;
        return (int) (hashed >>> 32);
    }

    @SuppressWarnings("unchecked")
    T get(long key) {
        int slot = mix(key) & mask;
        for (; ; ) {
            Object value = values[slot];
            if (value == null) return null;
            if (keys[slot] == key) return (T) value;
            slot = (slot + 1) & mask;
        }
    }

    void put(long key, T value) {
        if (size * 2 >= keys.length) rehash();
        insert(key, value);
    }

    private void insert(long key, T value) {
        int slot = mix(key) & mask;
        for (; ; ) {
            if (values[slot] == null) {
                keys[slot] = key;
                values[slot] = value;
                size++;
                return;
            }
            if (keys[slot] == key) {
                values[slot] = value;
                return;
            }
            slot = (slot + 1) & mask;
        }
    }

    @SuppressWarnings("unchecked")
    private void rehash() {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        int next = keys.length << 1;
        keys = new long[next];
        values = new Object[next];
        mask = next - 1;
        size = 0;
        for (int i = 0; i < oldValues.length; i++) {
            if (oldValues[i] != null) insert(oldKeys[i], (T) oldValues[i]);
        }
    }
}
