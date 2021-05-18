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
package io.netty.buffer.api.memseg;

import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;

import java.lang.ref.Cleaner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

import static jdk.incubator.foreign.ResourceScope.newSharedScope;

public class NativeMemorySegmentManager extends AbstractMemorySegmentManager {
    public static final LongAdder MEM_USAGE_NATIVE = new LongAdder();
    private static final ConcurrentHashMap<Long, Runnable> CLEANUP_ACTIONS = new ConcurrentHashMap<>();
    private static final Function<Long, Runnable> CLEANUP_ACTION_MAKER = s -> new ReduceNativeMemoryUsage(s);

    static Runnable getCleanupAction(long size) {
        return CLEANUP_ACTIONS.computeIfAbsent(size, CLEANUP_ACTION_MAKER);
    }

    private static final class ReduceNativeMemoryUsage implements Runnable {
        private final long size;

        private ReduceNativeMemoryUsage(long size) {
            this.size = size;
        }

        @Override
        public void run() {
            MEM_USAGE_NATIVE.add(-size);
        }

        @Override
        public String toString() {
            return "ReduceNativeMemoryUsage(by " + size + " bytes)";
        }
    }

    @Override
    public boolean isNative() {
        return true;
    }

    @Override
    protected MemorySegment createSegment(long size, Cleaner cleaner) {
        final ResourceScope scope = cleaner == null ? newSharedScope() : newSharedScope(cleaner);
        scope.addCloseAction(getCleanupAction(size));
        var segment = MemorySegment.allocateNative(size, scope);
        MEM_USAGE_NATIVE.add(size);
        return segment;
    }
}
