/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer.b2;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

interface Statics {
    Cleaner CLEANER = Cleaner.create();
    LongAdder MEM_USAGE_NATIVE = new LongAdder();
    ConcurrentHashMap<Long, Runnable> CLEANUP_ACTIONS = new ConcurrentHashMap<>();
    Drop<Buf> NO_OP_DROP = buf -> {
    };

    static VarHandle findVarHandle(Lookup lookup, Class<?> recv, String name, Class<?> type) {
        try {
            return lookup.findVarHandle(recv, name, type);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static Runnable getCleanupAction(long size) {
        return CLEANUP_ACTIONS.computeIfAbsent(size, s -> () -> MEM_USAGE_NATIVE.add(-s));
    }
}
