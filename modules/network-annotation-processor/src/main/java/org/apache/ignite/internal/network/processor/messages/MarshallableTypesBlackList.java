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

package org.apache.ignite.internal.network.processor.messages;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleTypeVisitor9;
import org.apache.ignite.internal.network.processor.TypeUtils;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.network.NetworkMessage;
import org.apache.ignite.network.annotations.Marshallable;

/**
 * Class for checking that a given message field can be annotated as {@link Marshallable}. A message field can be annotated as
 * {@code Marshallable} only if it is not supported by the Direct Marshaller natively.
 */
public class MarshallableTypesBlackList {
    /** Types supported by the Direct Marshaller. */
    public static final List<Class<?>> NATIVE_TYPES = List.of(
            // Primitive type wrappers
            Boolean.class,
            Byte.class,
            Character.class,
            Short.class,
            Integer.class,
            Long.class,
            Double.class,
            Float.class,
            Void.class,

            // Other types
            String.class,
            UUID.class,
            IgniteUuid.class,
            BitSet.class,
            ByteBuffer.class
    );

    private static final List<Class<?>> COLLECTION_TYPES = List.of(
            Collection.class,
            List.class,
            Set.class,
            Map.class
    );

    private final TypeVisitor typeVisitor;

    MarshallableTypesBlackList(TypeUtils typeUtils) {
        this.typeVisitor = new TypeVisitor(typeUtils);
    }

    boolean canBeMarshallable(TypeMirror type) {
        return typeVisitor.visit(type);
    }

    private static class TypeVisitor extends SimpleTypeVisitor9<Boolean, Void> {
        private final TypeUtils typeUtils;

        TypeVisitor(TypeUtils typeUtils) {
            super(false);

            this.typeUtils = typeUtils;
        }

        @Override
        public Boolean visitArray(ArrayType t, Void unused) {
            return visit(t.getComponentType());
        }

        @Override
        public Boolean visitDeclared(DeclaredType t, Void unused) {
            // Check that nested collection types are also not supported by the Direct Marshaller.
            if (isSameType(COLLECTION_TYPES, t)) {
                return t.getTypeArguments().stream().anyMatch(this::visit);
            }

            return !isSameType(NATIVE_TYPES, t) && !typeUtils.isSubType(t, NetworkMessage.class);
        }

        private boolean isSameType(List<Class<?>> types, DeclaredType type) {
            return types.stream().anyMatch(cls -> typeUtils.isSameType(type, cls));
        }
    }
}