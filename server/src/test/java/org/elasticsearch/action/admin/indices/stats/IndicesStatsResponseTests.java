/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.indices.stats;

import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardPath;
import org.elasticsearch.test.ESTestCase;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.object.HasToString.hasToString;

public class IndicesStatsResponseTests extends ESTestCase {

    public void testInvalidLevel() {
        final IndicesStatsResponse response = new IndicesStatsResponse(null, 0, 0, 0, null);
        final String level = randomAlphaOfLength(16);
        final ToXContent.Params params = new ToXContent.MapParams(Collections.singletonMap("level", level));
        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
            () -> response.toXContent(JsonXContent.contentBuilder(), params));
        assertThat(
            e,
            hasToString(containsString("level parameter must be one of [cluster] or [indices] or [shards] but was [" + level + "]")));
    }

    public void testGetIndices() {
        List<ShardStats> shards = new ArrayList<>();
        int noOfIndexes = randomIntBetween(2, 5);
        List<String> expectedIndexes = new ArrayList<>();
        Map<String, AtomicLong> expectedIndexToPrimaryShardsCount = new HashMap<>();
        Map<String, AtomicLong> expectedIndexToTotalShardsCount = new HashMap<>();

        for (int indCnt = 0; indCnt < noOfIndexes; indCnt++) {
            Index index = createIndex(randomAlphaOfLength(9));
            expectedIndexes.add(index.getName());
            int numShards = randomIntBetween(1, 5);
            for (int shardId = 0; shardId < numShards; shardId++) {
                ShardId shId = new ShardId(index, shardId);
                Path path = createTempDir().resolve("indices").resolve(index.getUUID()).resolve(String.valueOf(shardId));
                ShardPath shardPath = new ShardPath(false, path, path, shId);
                ShardRouting routing = createShardRouting(index, shId, (shardId == 0));
                shards.add(new ShardStats(routing, shardPath, null, null, null, null));
                AtomicLong primaryShardsCounter = expectedIndexToPrimaryShardsCount.computeIfAbsent(index.getName(),
                        k -> new AtomicLong(0L));
                if (routing.primary()) {
                    primaryShardsCounter.incrementAndGet();
                }
                AtomicLong shardsCounter = expectedIndexToTotalShardsCount.computeIfAbsent(index.getName(), k -> new AtomicLong(0L));
                shardsCounter.incrementAndGet();
            }
        }
        final IndicesStatsResponse indicesStatsResponse = new IndicesStatsResponse(shards.toArray(new ShardStats[shards.size()]), 0, 0, 0,
                null);
        Map<String, IndexStats> indexStats = indicesStatsResponse.getIndices();

        assertThat(indexStats.size(), is(noOfIndexes));
        assertThat(indexStats.keySet(), containsInAnyOrder(expectedIndexes.toArray(new String[0])));

        for (String index : indexStats.keySet()) {
            IndexStats stat = indexStats.get(index);
            ShardStats[] shardStats = stat.getShards();
            long primaryCount = 0L;
            long totalCount = shardStats.length;
            for (ShardStats shardStat : shardStats) {
                if (shardStat.getShardRouting().primary()) {
                    primaryCount++;
                }
            }
            assertThat(primaryCount, is(expectedIndexToPrimaryShardsCount.get(index).get()));
            assertThat(totalCount, is(expectedIndexToTotalShardsCount.get(index).get()));
        }
    }

    private ShardRouting createShardRouting(Index index, ShardId shardId, boolean isPrimary) {
        return TestShardRouting.newShardRouting(shardId, randomAlphaOfLength(4), isPrimary, ShardRoutingState.STARTED);
    }

    private Index createIndex(String indexName) {
        return new Index(indexName, UUIDs.base64UUID());
    }
}
