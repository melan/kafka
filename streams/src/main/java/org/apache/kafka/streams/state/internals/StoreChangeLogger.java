/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.state.internals;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.internals.ProcessorStateManager;
import org.apache.kafka.streams.processor.internals.RecordCollector;
import org.apache.kafka.streams.state.Serdes;

import java.util.HashSet;
import java.util.Set;

public class StoreChangeLogger<K, V> {

    public interface ValueGetter<K, V> {
        V get(K key);
    }

    // TODO: these values should be configurable
    protected static final int DEFAULT_WRITE_BATCH_SIZE = 100;

    protected final Serdes<K, V> serialization;

    private final String topic;
    private final int partition;
    private final ProcessorContext context;
    private final int maxDirty;
    private final int maxRemoved;

    protected Set<K> dirty;
    protected Set<K> removed;

    public StoreChangeLogger(String storeName, ProcessorContext context, Serdes<K, V> serialization) {
        this(storeName, context, serialization, DEFAULT_WRITE_BATCH_SIZE, DEFAULT_WRITE_BATCH_SIZE);
    }

    public StoreChangeLogger(String storeName, ProcessorContext context, Serdes<K, V> serialization, int maxDirty, int maxRemoved) {
        this(storeName, context, context.taskId().partition, serialization, maxDirty, maxRemoved);
        init();
    }

    protected StoreChangeLogger(String storeName, ProcessorContext context, int partition, Serdes<K, V> serialization, int maxDirty, int maxRemoved) {
        this.topic = ProcessorStateManager.storeChangelogTopic(context.jobId(), storeName);
        this.context = context;
        this.partition = partition;
        this.serialization = serialization;
        this.maxDirty = maxDirty;
        this.maxRemoved = maxRemoved;
    }

    public void init() {
        this.dirty = new HashSet<>();
        this.removed = new HashSet<>();
    }

    public void add(K key) {
        this.dirty.add(key);
        this.removed.remove(key);
    }

    public void delete(K key) {
        this.dirty.remove(key);
        this.removed.add(key);
    }

    public void maybeLogChange(ValueGetter<K, V> getter) {
        if (this.dirty.size() > this.maxDirty || this.removed.size() > this.maxRemoved)
            logChange(getter);
    }

    public void logChange(ValueGetter<K, V> getter) {
        RecordCollector collector = ((RecordCollector.Supplier) context).recordCollector();
        if (collector != null) {
            Serializer<K> keySerializer = serialization.keySerializer();
            Serializer<V> valueSerializer = serialization.valueSerializer();

            for (K k : this.removed) {
                collector.send(new ProducerRecord<>(this.topic, this.partition, k, (V) null), keySerializer, valueSerializer);
            }
            for (K k : this.dirty) {
                V v = getter.get(k);
                collector.send(new ProducerRecord<>(this.topic, this.partition, k, v), keySerializer, valueSerializer);
            }
            this.removed.clear();
            this.dirty.clear();
        }
    }

    public void clear() {
        this.removed.clear();
        this.dirty.clear();
    }

    // this is for test only
    public int numDirty() {
        return this.dirty.size();
    }

    // this is for test only
    public int numRemoved() {
        return this.removed.size();
    }
}
