/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.mergetree.compact;

import org.apache.paimon.KeyValue;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.types.RowKind;
import org.apache.paimon.utils.Preconditions;

import java.util.Comparator;

/**
 * Wrapper for {@link MergeFunction}s to produce changelog during a full compaction.
 *
 * <p>This wrapper can only be used in {@link SortMergeReader} because
 *
 * <ul>
 *   <li>This wrapper does not copy {@link KeyValue}s. As {@link KeyValue}s are reused by readers
 *       this may cause issues in other readers.
 *   <li>{@link KeyValue}s with the same key come from different inner readers in {@link
 *       SortMergeReader}, so there is no issue related to object reuse.
 * </ul>
 */
public class FullChangelogMergeFunctionWrapper implements MergeFunctionWrapper<ChangelogResult> {

    private final MergeFunction<KeyValue> mergeFunction;
    private final int maxLevel;
    private final Comparator<InternalRow> valueComparator;
    private final boolean changelogRowDeduplicate;

    // only full compaction will write files into maxLevel, see UniversalCompaction class
    private KeyValue topLevelKv;
    private KeyValue initialKv;
    private boolean isInitialized;

    private final ChangelogResult reusedResult = new ChangelogResult();
    private final KeyValue reusedBefore = new KeyValue();
    private final KeyValue reusedAfter = new KeyValue();

    public FullChangelogMergeFunctionWrapper(
            MergeFunction<KeyValue> mergeFunction,
            int maxLevel,
            Comparator<InternalRow> valueComparator,
            boolean changelogRowDeduplicate) {
        Preconditions.checkArgument(
                !(mergeFunction instanceof ValueCountMergeFunction),
                "Value count merge function does not need to produce changelog from full compaction. "
                        + "Please set changelog producer to 'input'.");
        this.mergeFunction = mergeFunction;
        this.maxLevel = maxLevel;
        this.valueComparator = valueComparator;
        this.changelogRowDeduplicate = changelogRowDeduplicate;
    }

    @Override
    public void reset() {
        mergeFunction.reset();

        topLevelKv = null;
        initialKv = null;
        isInitialized = false;
    }

    @Override
    public void add(KeyValue kv) {
        if (maxLevel == kv.level()) {
            Preconditions.checkState(
                    topLevelKv == null, "Top level key-value already exists! This is unexpected.");
            topLevelKv = kv;
        }

        if (initialKv == null) {
            initialKv = kv;
        } else {
            if (!isInitialized) {
                merge(initialKv);
                isInitialized = true;
            }
            merge(kv);
        }
    }

    private void merge(KeyValue kv) {
        mergeFunction.add(kv);
    }

    @Override
    public ChangelogResult getResult() {
        reusedResult.reset();
        if (isInitialized) {
            KeyValue merged = mergeFunction.getResult();
            if (topLevelKv == null) {
                if (merged != null && isAdd(merged)) {
                    reusedResult.addChangelog(replace(reusedAfter, RowKind.INSERT, merged));
                }
            } else {
                if (merged == null || !isAdd(merged)) {
                    reusedResult.addChangelog(replace(reusedBefore, RowKind.DELETE, topLevelKv));
                } else if (!changelogRowDeduplicate
                        || valueComparator.compare(topLevelKv.value(), merged.value()) != 0) {
                    reusedResult
                            .addChangelog(replace(reusedBefore, RowKind.UPDATE_BEFORE, topLevelKv))
                            .addChangelog(replace(reusedAfter, RowKind.UPDATE_AFTER, merged));
                }
            }
            return reusedResult.setResultIfNotRetract(merged);
        } else {
            if (topLevelKv == null && isAdd(initialKv)) {
                reusedResult.addChangelog(replace(reusedAfter, RowKind.INSERT, initialKv));
            }
            // either topLevelKv is not null, but there is only one kv,
            // so topLevelKv must be the only kv, which means there is no change
            //
            // or initialKv is not an ADD kv, so no new key is added
            return reusedResult.setResultIfNotRetract(initialKv);
        }
    }

    private KeyValue replace(KeyValue reused, RowKind valueKind, KeyValue from) {
        return reused.replace(from.key(), from.sequenceNumber(), valueKind, from.value());
    }

    private boolean isAdd(KeyValue kv) {
        return kv.valueKind() == RowKind.INSERT || kv.valueKind() == RowKind.UPDATE_AFTER;
    }
}
