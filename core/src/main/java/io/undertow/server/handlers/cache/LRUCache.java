/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.server.handlers.cache;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import io.undertow.util.SecureHashMap;

/**
 * A non-blocking cache where entries are indexed by a key.
 * <p/>
 * <p>To reduce contention, entry allocation and eviction execute in a sampling
 * fashion (entry hits modulo N). Eviction follows an LRU approach (oldest sampled
 * entries are removed first) when the cache is out of capacity.</p>
 * <p/>
 *
 * @author Jason T. Greene
 * @author Stuart Douglas
 */
public class LRUCache<K, V> {
    private static final int SAMPLE_INTERVAL = 5;

    /**
     * Max active entries that are present in the cache.
     */
    private final int maxEntries;

    private final SecureHashMap<K, CacheEntry<K, V>> cache;
    private final ConcurrentDirectDeque<CacheEntry<K, V>> accessQueue;

    public LRUCache(int maxEntries) {
        this.cache = new SecureHashMap<K, CacheEntry<K, V>>(16);
        this.accessQueue = ConcurrentDirectDeque.newInstance();
        this.maxEntries = maxEntries;
    }

    public void add(K key, V newValue) {
        CacheEntry<K, V> value = cache.get(key);
        if (value == null) {
            value = new CacheEntry<>(key, newValue, this);
            CacheEntry result = cache.putIfAbsent(key, value);
            if (result != null) {
                value = result;
                value.setValue(newValue);
            }
            bumpAccess(value);
            if (cache.size() > maxEntries) {
                //remove the oldest
                CacheEntry<K, V> oldest = accessQueue.poll();
                if (oldest != value) {
                    this.remove(oldest.key());
                }
            }
        }
    }

    public V get(K key) {
        CacheEntry<K, V> cacheEntry = cache.get(key);
        if (cacheEntry == null) {
            return null;
        }

        if (cacheEntry.hit() % SAMPLE_INTERVAL == 0) {
            bumpAccess(cacheEntry);
        }

        return cacheEntry.getValue();
    }

    private void bumpAccess(CacheEntry<K, V> cacheEntry) {
        Object prevToken = cacheEntry.claimToken();
        if (prevToken != Boolean.FALSE) {
            if (prevToken != null) {
                accessQueue.removeToken(prevToken);
            }

            Object token = null;
            try {
                token = accessQueue.offerLastAndReturnToken(cacheEntry);
            } catch (Throwable t) {
                // In case of disaster (OOME), we need to release the claim, so leave it aas null
            }

            if (!cacheEntry.setToken(token) && token != null) { // Always set if null
                accessQueue.removeToken(token);
            }
        }
    }

    public void remove(K key) {
        CacheEntry<K, V> remove = cache.remove(key);
        if (remove != null) {
            Object old = remove.clearToken();
            if (old != null) {
                accessQueue.removeToken(old);
            }
        }
    }

    public static final class CacheEntry<K, V> {

        private static final Object CLAIM_TOKEN = new Object();

        private static final AtomicIntegerFieldUpdater<CacheEntry> hitsUpdater = AtomicIntegerFieldUpdater.newUpdater(CacheEntry.class, "hits");

        private static final AtomicReferenceFieldUpdater<CacheEntry, Object> tokenUpdator = AtomicReferenceFieldUpdater.newUpdater(CacheEntry.class, Object.class, "accessToken");

        private final K key;
        private volatile V value;
        private final LRUCache<K, V> cache;
        private volatile int hits = 1;
        private volatile Object accessToken;

        private CacheEntry(K key, V value, LRUCache cache) {
            this.key = key;
            this.value = value;
            this.cache = cache;
        }

        public void setValue(final V value) {
            this.value = value;
        }

        public V getValue() {
            return value;
        }

        public int hit() {
            for (; ; ) {
                int i = hits;

                if (hitsUpdater.weakCompareAndSet(this, i, ++i)) {
                    return i;
                }

            }
        }

        public K key() {
            return key;
        }

        Object claimToken() {
            for (; ; ) {
                Object current = this.accessToken;
                if (current == CLAIM_TOKEN) {
                    return Boolean.FALSE;
                }

                if (tokenUpdator.compareAndSet(this, current, CLAIM_TOKEN)) {
                    return current;
                }
            }
        }

        boolean setToken(Object token) {
            return tokenUpdator.compareAndSet(this, CLAIM_TOKEN, token);
        }

        Object clearToken() {
            Object old = tokenUpdator.getAndSet(this, null);
            return old == CLAIM_TOKEN ? null : old;
        }

    }
}