/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer.b2;

import io.netty.util.internal.PlatformDependent;
import jdk.incubator.foreign.MemorySegment;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.LongAdder;

interface Statics {
    @SuppressWarnings("InstantiatingAThreadWithDefaultRunMethod")
    Thread TRANSFER_OWNER = new Thread("ByteBuf Transfer Owner");
    long SCOPE = fieldOffset("jdk.internal.foreign.AbstractMemorySegmentImpl", "scope");
    long OWNER = fieldOffset("jdk.internal.foreign.MemoryScope", "owner");
    LongAdder MEM_USAGE_NATIVE = new LongAdder();

    static VarHandle findVarHandle(Lookup lookup, Class<?> recv, String name, Class<?> type) {
        try {
            return lookup.findVarHandle(recv, name, type);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static long fieldOffset(String className, String fieldName) {
        try {
            Class<?> cls = Class.forName(className);
            Field field = cls.getDeclaredField(fieldName);
            return PlatformDependent.objectFieldOffset(field);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static void overwriteMemorySegmentOwner(MemorySegment segment, Thread newOwner) {
        Object scope = PlatformDependent.getObject(segment, SCOPE);
        PlatformDependent.putObject(scope, OWNER, newOwner);
        VarHandle.fullFence(); // Attempt to force visibility of overwritten final fields.
    }
}
