/*
 * Copyright 2021 The Netty Project
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

import io.netty.buffer.api.AllocationType;
import io.netty.buffer.api.AllocatorControl;
import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.Drop;
import io.netty.buffer.api.MemoryManager;
import io.netty.buffer.api.StandardAllocationTypes;
import io.netty.buffer.api.internal.Statics;
import io.netty.buffer.api.internal.WrappingAllocation;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;

import java.lang.ref.Cleaner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static jdk.incubator.foreign.ResourceScope.newSharedScope;

public class SegmentMemoryManager implements MemoryManager {
    private static final ConcurrentHashMap<Long, Runnable> CLEANUP_ACTIONS = new ConcurrentHashMap<>();
    private static final Function<Long, Runnable> CLEANUP_ACTION_MAKER = s -> new ReduceNativeMemoryUsage(s);

    static Runnable getCleanupAction(long size) {
        return CLEANUP_ACTIONS.computeIfAbsent(size, CLEANUP_ACTION_MAKER);
    }

    private static MemorySegment createHeapSegment(long size) {
        return MemorySegment.ofArray(new byte[Math.toIntExact(size)]);
    }

    private static MemorySegment createNativeSegment(long size, Cleaner cleaner) {
        final ResourceScope scope = cleaner == null ? newSharedScope() : newSharedScope(cleaner);
        scope.addCloseAction(getCleanupAction(size));
        final MemorySegment segment;
        if (size == 0) {
            segment = MemorySegment.allocateNative(1, scope).asSlice(0, 0);
        } else {
            segment = MemorySegment.allocateNative(size, scope);
        }
        Statics.MEM_USAGE_NATIVE.add(size);
        return segment;
    }

    @Override
    public Buffer allocateShared(AllocatorControl allocatorControl, long size, Drop<Buffer> drop,
                                 AllocationType type) {
        if (type instanceof StandardAllocationTypes stype) {
            var segment = switch (stype) {
                case ON_HEAP -> createHeapSegment(size);
                case OFF_HEAP -> createNativeSegment(size, null);
            };
            return createBuffer(segment, drop, allocatorControl);
        }
        if (type instanceof WrappingAllocation allocation) {
            var seg = MemorySegment.ofArray(allocation.getArray());
            return createBuffer(seg, drop, allocatorControl);
        }
        throw new IllegalArgumentException("Unknown allocation type: " + type);
    }

    @Override
    public Buffer allocateConstChild(Buffer readOnlyConstParent) {
        assert readOnlyConstParent.readOnly();
        MemSegBuffer buf = (MemSegBuffer) readOnlyConstParent;
        return buf.newConstChild();
    }

    @Override
    public Drop<Buffer> drop() {
        return Statics.convert(MemSegBuffer.SEGMENT_CLOSE);
    }

    @Override
    public Object unwrapRecoverableMemory(Buffer buf) {
        var b = (MemSegBuffer) buf;
        return b.recoverableMemory();
    }

    @Override
    public Buffer recoverMemory(AllocatorControl allocatorControl, Object recoverableMemory, Drop<Buffer> drop) {
        var segment = (MemorySegment) recoverableMemory;
        return createBuffer(segment, drop, allocatorControl);
    }

    private static MemSegBuffer createBuffer(MemorySegment segment, Drop<Buffer> drop, AllocatorControl control) {
        Drop<MemSegBuffer> concreteDrop = Statics.convert(drop);
        MemSegBuffer buffer = new MemSegBuffer(segment, segment, control, concreteDrop);
        concreteDrop.attach(buffer);
        return buffer;
    }

    @Override
    public Object sliceMemory(Object memory, int offset, int length) {
        var segment = (MemorySegment) memory;
        return segment.asSlice(offset, length);
    }

    @Override
    public String implementationName() {
        return "MemorySegment";
    }
}
