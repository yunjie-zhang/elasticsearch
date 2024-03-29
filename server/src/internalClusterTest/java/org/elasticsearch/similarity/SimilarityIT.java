/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.similarity;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.matchQuery;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class SimilarityIT extends ESIntegTestCase {
    public void testCustomBM25Similarity() throws Exception {
        try {
            client().admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }

        client().admin().indices().prepareCreate("test")
                .setMapping(jsonBuilder().startObject()
                        .startObject("_doc")
                            .startObject("properties")
                                .startObject("field1")
                                    .field("similarity", "custom")
                                    .field("type", "text")
                                .endObject()
                                .startObject("field2")
                                    .field("similarity", "boolean")
                                    .field("type", "text")
                                .endObject()
                            .endObject()
                        .endObject()
                    .endObject())
                .setSettings(Settings.builder()
                        .put("index.number_of_shards", 1)
                        .put("index.number_of_replicas", 0)
                        .put("similarity.custom.type", "BM25")
                        .put("similarity.custom.k1", 2.0f)
                        .put("similarity.custom.b", 0.5f)
                ).execute().actionGet();

        client().prepareIndex("test").setId("1").setSource("field1", "the quick brown fox jumped over the lazy dog",
                                                            "field2", "the quick brown fox jumped over the lazy dog")
                .setRefreshPolicy(IMMEDIATE).execute().actionGet();

        SearchResponse bm25SearchResponse = client().prepareSearch().setQuery(matchQuery("field1", "quick brown fox"))
            .execute().actionGet();
        assertThat(bm25SearchResponse.getHits().getTotalHits().value, equalTo(1L));
        float bm25Score = bm25SearchResponse.getHits().getHits()[0].getScore();

        SearchResponse booleanSearchResponse = client().prepareSearch().setQuery(matchQuery("field2", "quick brown fox"))
            .execute().actionGet();
        assertThat(booleanSearchResponse.getHits().getTotalHits().value, equalTo(1L));
        float defaultScore = booleanSearchResponse.getHits().getHits()[0].getScore();

        assertThat(bm25Score, not(equalTo(defaultScore)));
    }
}
