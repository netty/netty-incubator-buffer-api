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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class NativeMemorySegmentManager extends AbstractMemorySegmentManager {
    public static final LongAdder MEM_USAGE_NATIVE = new LongAdder();
    private static final ConcurrentHashMap<Long, Runnable> CLEANUP_ACTIONS = new ConcurrentHashMap<>();

    static Runnable getCleanupAction(long size) {
        return CLEANUP_ACTIONS.computeIfAbsent(size, s -> () -> MEM_USAGE_NATIVE.add(-s));
    }

    @Override
    public boolean isNative() {
        return true;
    }

    @Override
    protected MemorySegment createSegment(long size) {
        var segment = MemorySegment.allocateNative(size);
//                                       .withCleanupAction(Statics.getCleanupAction(size));
        MEM_USAGE_NATIVE.add(size);
        return segment;
    }
}
