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

package org.apache.ignite.internal.lang;

import static org.apache.ignite.lang.ErrorGroups.Replicator.REPLICATION_SAFE_TIME_REORDERING_ERR;

/**
 * This exception is used to indicate a detection of a safe time reordering.
 */
public class SafeTimeReorderException extends IgniteInternalException {
    /** maxObservableSafeTime at the moment of violation. */
    private final long maxObservableSafeTimeViolatedValue;

    /**
     * The constructor.
     *
     * @param maxObservableSafeTimeViolatedValue maxObservableSafeTime at the moment of violation
     */
    public SafeTimeReorderException(long maxObservableSafeTimeViolatedValue) {
        super(REPLICATION_SAFE_TIME_REORDERING_ERR, "Replication safe time reordering detected.");
        this.maxObservableSafeTimeViolatedValue = maxObservableSafeTimeViolatedValue;
    }

    /**
     * Returns {@code maxObservableSafeTime} at the moment of violation.
     *
     * @return maxObservableSafeTime at the moment of violation.
     */
    public long maxObservableSafeTimeViolatedValue() {
        return maxObservableSafeTimeViolatedValue;
    }
}

