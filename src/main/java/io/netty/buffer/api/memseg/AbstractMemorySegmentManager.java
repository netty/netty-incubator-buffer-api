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

import io.netty.buffer.api.AllocatorControl;
import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.Drop;
import io.netty.buffer.api.MemoryManager;
import io.netty.buffer.api.internal.ArcDrop;
import jdk.incubator.foreign.MemorySegment;

import java.lang.ref.Cleaner;

import static io.netty.buffer.api.internal.Statics.convert;

public abstract class AbstractMemorySegmentManager implements MemoryManager {
    @Override
    public Buffer allocateShared(AllocatorControl allocatorControl, long size, Drop<Buffer> drop, Cleaner cleaner) {
        var segment = createSegment(size, cleaner);
        return new MemSegBuffer(segment, segment, convert(drop), allocatorControl);
    }

    @Override
    public Buffer allocateCopyOnWritable(Buffer ownedReadOnlyBuffer) {
        assert ownedReadOnlyBuffer.isOwned() && ownedReadOnlyBuffer.readOnly();
        MemSegBuffer buf = (MemSegBuffer) ownedReadOnlyBuffer;
        return new MemSegBuffer(buf);
    }

    protected abstract MemorySegment createSegment(long size, Cleaner cleaner);

    @Override
    public Drop<Buffer> drop() {
        return convert(MemSegBuffer.SEGMENT_CLOSE);
    }

    @Override
    public Object unwrapRecoverableMemory(Buffer buf) {
        var b = (MemSegBuffer) buf;
        return b.recoverableMemory();
    }

    @Override
    public int capacityOfRecoverableMemory(Object memory) {
        return (int) ((MemorySegment) memory).byteSize();
    }

    @Override
    public Buffer recoverMemory(AllocatorControl allocatorControl, Object recoverableMemory, Drop<Buffer> drop) {
        var segment = (MemorySegment) recoverableMemory;
        return new MemSegBuffer(segment, segment, convert(ArcDrop.acquire(drop)), allocatorControl);
    }
}
