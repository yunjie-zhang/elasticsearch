/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.builder;

import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.test.AbstractSerializingTestCase;

import java.io.IOException;

public class PointInTimeBuilderTests extends AbstractSerializingTestCase<PointInTimeBuilder> {
    @Override
    protected PointInTimeBuilder doParseInstance(XContentParser parser) throws IOException {
        return PointInTimeBuilder.fromXContent(parser);
    }

    @Override
    protected Writeable.Reader<PointInTimeBuilder> instanceReader() {
        return PointInTimeBuilder::new;
    }

    @Override
    protected PointInTimeBuilder createTestInstance() {
        final PointInTimeBuilder pointInTime = new PointInTimeBuilder(randomAlphaOfLength(20));
        if (randomBoolean()) {
            pointInTime.setKeepAlive(randomTimeValue());
        }
        return pointInTime;
    }
}
