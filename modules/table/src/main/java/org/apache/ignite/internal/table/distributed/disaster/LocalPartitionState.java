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

package org.apache.ignite.internal.table.distributed.disaster;

/**
 * Local partition state.
 */
public class LocalPartitionState {
    public final String tableName;

    public final String zoneName;

    public final int partitionId;

    public final LocalPartitionStateEnum state;

    LocalPartitionState(String tableName, String zoneName, int partitionId, LocalPartitionStateEnum state) {
        this.tableName = tableName;
        this.zoneName = zoneName;
        this.partitionId = partitionId;
        this.state = state;
    }
}
