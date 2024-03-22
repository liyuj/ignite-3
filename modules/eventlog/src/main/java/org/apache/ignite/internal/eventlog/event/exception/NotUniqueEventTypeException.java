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

import static org.apache.ignite.lang.ErrorGroups.Common.INTERNAL_ERR;

import org.apache.ignite.internal.lang.IgniteInternalException;

/** Thrown when an event type is not unique. */
public class NotUniqueEventTypeException extends IgniteInternalException {

    private static final long serialVersionUID = 6299123813806042917L;

    /**
     * Constructor.
     *
     * @param type The type of the event.
     */
    public NotUniqueEventTypeException(String type) {
        super(
                INTERNAL_ERR,
                String.format("Event type `%s` is already registered. Please, use another name.", type)
        );
    }
}
