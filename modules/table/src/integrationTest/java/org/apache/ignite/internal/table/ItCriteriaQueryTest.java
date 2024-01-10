/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.table;

import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static org.apache.ignite.internal.lang.IgniteStringFormatter.format;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.await;
import static org.apache.ignite.internal.testframework.matchers.TupleMatcher.tupleValue;
import static org.apache.ignite.lang.util.IgniteNameUtils.quote;
import static org.apache.ignite.table.criteria.Criteria.columnValue;
import static org.apache.ignite.table.criteria.Criteria.equalTo;
import static org.apache.ignite.table.criteria.Criteria.greaterThan;
import static org.apache.ignite.table.criteria.Criteria.greaterThanOrEqualTo;
import static org.apache.ignite.table.criteria.Criteria.in;
import static org.apache.ignite.table.criteria.Criteria.lessThan;
import static org.apache.ignite.table.criteria.Criteria.lessThanOrEqualTo;
import static org.apache.ignite.table.criteria.Criteria.notEqualTo;
import static org.apache.ignite.table.criteria.Criteria.notIn;
import static org.apache.ignite.table.criteria.Criteria.notNullValue;
import static org.apache.ignite.table.criteria.Criteria.nullValue;
import static org.apache.ignite.table.criteria.CriteriaQueryOptions.builder;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.internal.ClusterPerClassIntegrationTest;
import org.apache.ignite.internal.testframework.IgniteTestUtils;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.lang.AsyncCursor;
import org.apache.ignite.lang.Cursor;
import org.apache.ignite.lang.IgniteException;
import org.apache.ignite.table.RecordView;
import org.apache.ignite.table.Table;
import org.apache.ignite.table.Tuple;
import org.apache.ignite.table.criteria.CriteriaQuerySource;
import org.apache.ignite.table.mapper.Mapper;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for the criteria query API.
 */
public class ItCriteriaQueryTest extends ClusterPerClassIntegrationTest {
    /** Table name. */
    private static final String TABLE_NAME = "tbl";

    /** Table with quoted name. */
    private static final String QUOTED_TABLE_NAME = quote("TaBleName");

    private static IgniteClient CLIENT;

    /** {@inheritDoc} */
    @Override
    protected int initialNodes() {
        return 1;
    }

    /** {@inheritDoc} */
    @BeforeAll
    @Override
    protected void beforeAll(TestInfo testInfo) {
        super.beforeAll(testInfo);

        CLIENT = IgniteClient.builder()
                .addresses("127.0.0.1:" + CLUSTER.aliveNode().clientAddress().port()).build();

        sql(format("CREATE TABLE {} (id INT PRIMARY KEY, name VARCHAR, salary DOUBLE, hash VARBINARY)", TABLE_NAME));

        insertData(
                TABLE_NAME,
                List.of("ID", "name", "salary", "hash"),
                new Object[]{0, null, 0.0d, "hash0".getBytes()},
                new Object[]{1, "name1", 10.0d, "hash1".getBytes()},
                new Object[]{2, "name2", 20.0d, "hash2".getBytes()}
        );

        sql(format("CREATE TABLE {} (id INT PRIMARY KEY, \"colUmn\" VARCHAR)", QUOTED_TABLE_NAME));

        insertData(
                QUOTED_TABLE_NAME,
                List.of("id", quote("colUmn")),
                new Object[]{0, "name0"},
                new Object[]{1, "name1"},
                new Object[]{2, "name2"}
        );
    }

    @AfterAll
    void stopClient() throws Exception {
        IgniteUtils.closeAll(CLIENT);
    }

    private static Stream<Arguments> testRecordViewQuery() {
        Table table = CLUSTER.aliveNode().tables().table(TABLE_NAME);
        Table clientTable = CLIENT.tables().table(TABLE_NAME);

        return Stream.of(
                Arguments.of(table.recordView(), identity()),
                Arguments.of(clientTable.recordView(), identity()),
                Arguments.of(clientTable.recordView(TestObject.class),
                        (Function<TestObject, Tuple>) (obj) -> Tuple.create().set("id", obj.id).set("name", obj.name)
                                .set("salary", obj.salary).set("hash", obj.hash))
        );
    }

    @ParameterizedTest
    @MethodSource
    public <T> void testRecordViewQuery(CriteriaQuerySource<T> view, Function<T, Tuple> mapper) {
        IgniteTestUtils.assertThrows(
                IgniteException.class,
                () -> view.query(null, columnValue("id", equalTo("2"))),
                "Dynamic parameter requires adding explicit type cast"
        );

        Matcher<Tuple> person0 = allOf(tupleValue("id", is(0)), tupleValue("name", Matchers.nullValue()), tupleValue("salary", is(0.0d)),
                tupleValue("hash", is("hash0".getBytes())));
        Matcher<Tuple> person1 = allOf(tupleValue("id", is(1)), tupleValue("name", is("name1")), tupleValue("salary", is(10.0d)),
                tupleValue("hash", is("hash1".getBytes())));
        Matcher<Tuple> person2 = allOf(tupleValue("id", is(2)), tupleValue("name", is("name2")), tupleValue("salary", is(20.0d)),
                tupleValue("hash", is("hash2".getBytes())));

        try (Cursor<T> cur = view.query(null, null)) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person0, person1, person2));
        }

        try (Cursor<T> cur = view.query(null, columnValue("id", equalTo(2)))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person2));
        }

        try (Cursor<T> cur = view.query(null, columnValue("hash", equalTo("hash2".getBytes())))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person2));
        }

        try (Cursor<T> cur = view.query(null, columnValue("id", notEqualTo(2)))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person0, person1));
        }

        try (Cursor<T> cur = view.query(null, columnValue("hash", notEqualTo("hash2".getBytes())))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person0, person1));
        }

        try (Cursor<T> cur = view.query(null, columnValue("id", greaterThan(1)))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person2));
        }

        try (Cursor<T> cur = view.query(null, columnValue("id", greaterThanOrEqualTo(1)))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person1, person2));
        }

        try (Cursor<T> cur = view.query(null, columnValue("id", lessThan(1)))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person0));
        }

        try (Cursor<T> cur = view.query(null, columnValue("id", lessThanOrEqualTo(1)))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person0, person1));
        }

        try (Cursor<T> cur = view.query(null, columnValue("name", nullValue()))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person0));
        }

        try (Cursor<T> cur = view.query(null, columnValue("name", notNullValue()))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person1, person2));
        }

        try (Cursor<T> cur = view.query(null, columnValue("id", in(1, 2)))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person1, person2));
        }

        try (Cursor<T> cur = view.query(null, columnValue("id", notIn(1, 2)))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person0));
        }

        try (Cursor<T> cur = view.query(null, columnValue("hash", in("hash1".getBytes(), "hash2".getBytes())))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person1, person2));
        }

        try (Cursor<T> cur = view.query(null, columnValue("hash", in((byte[]) null)))) {
            assertThat(mapToTupleList(cur, mapper), empty());
        }

        try (Cursor<T> cur = view.query(null, columnValue("hash", notIn("hash1".getBytes(), "hash2".getBytes())))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person0));
        }

        try (Cursor<T> cur = view.query(null, columnValue("hash", notIn((byte[]) null)))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(person0, person1, person2));
        }
    }

    @Test
    public void testOptions() {
        RecordView<TestObject> view = CLIENT.tables().table(TABLE_NAME).recordView(TestObject.class);

        AsyncCursor<TestObject> ars = await(view.queryAsync(null, null, builder().pageSize(2).build()));

        assertNotNull(ars);
        assertEquals(2, ars.currentPageSize());
        await(ars.closeAsync());
    }

    private static Stream<Arguments> testRecordViewWithQuotes() {
        Table table = CLUSTER.aliveNode().tables().table(QUOTED_TABLE_NAME);
        Table clientTable = CLIENT.tables().table(QUOTED_TABLE_NAME);

        Mapper<QuotedObject> pojoMapper = Mapper.builder(QuotedObject.class)
                .map("colUmn", quote("colUmn"))
                .automap()
                .build();

        return Stream.of(
                Arguments.of(table.recordView(), identity()),
                Arguments.of(clientTable.recordView(), identity()),
                Arguments.of(clientTable.recordView(pojoMapper),
                        (Function<QuotedObject, Tuple>) (obj) -> Tuple.create(Map.of("id", obj.id, quote("colUmn"), obj.colUmn)))
        );
    }

    @ParameterizedTest
    @MethodSource
    public <T> void testRecordViewWithQuotes(CriteriaQuerySource<T> view, Function<T, Tuple> mapper) {
        try (Cursor<T> cur = view.query(null, columnValue(quote("colUmn"), equalTo("name1")))) {
            assertThat(mapToTupleList(cur, mapper), containsInAnyOrder(
                    allOf(tupleValue("id", is(1)), tupleValue(quote("colUmn"), is("name1")))
            ));
        }
    }

    private static <T> List<Tuple> mapToTupleList(Cursor<T> cur, Function<T, Tuple> mapper) {
        return StreamSupport.stream(spliteratorUnknownSize(cur, Spliterator.ORDERED), false)
                .map(mapper)
                .collect(toList());
    }

    static class QuotedObject {
        int id;
        String colUmn;
    }

    static class TestObject {
        int id;

        String name;

        double salary;

        byte[] hash;
    }
}
