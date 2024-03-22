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

package org.apache.ignite.internal.eventlog.event.exception;

import static org.apache.ignite.lang.ErrorGroups.Common.ILLEGAL_ARGUMENT_ERR;

import org.apache.ignite.internal.lang.IgniteInternalException;

/** Thrown when the projectVersion field is not a semver. */
public class InvalidProductVersionException extends IgniteInternalException {
    private static final long serialVersionUID = 3974826166860537715L;

    private static final String MSG_FORMAT = "Invalid productVersion `%s` during event creation. "
            + "The version should be in semver format. "
            + "Got: `%s`. Valid example: `3.0.0`";

    /**
     * Constructor.
     *
     * @param invalidProductVersion The productVersion.
     */
    public InvalidProductVersionException(String invalidProductVersion) {
        super(ILLEGAL_ARGUMENT_ERR, String.format(MSG_FORMAT, invalidProductVersion, invalidProductVersion));
    }
}
