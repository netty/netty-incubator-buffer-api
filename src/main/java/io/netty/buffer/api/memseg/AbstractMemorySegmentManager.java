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
import io.netty.buffer.api.memseg.MemSegBuffer.RecoverableMemory;
import jdk.incubator.foreign.MemorySegment;

import java.lang.ref.Cleaner;

public abstract class AbstractMemorySegmentManager implements MemoryManager {
    @Override
    public abstract boolean isNative();

    @Override
    public Buffer allocateConfined(AllocatorControl alloc, long size, Drop<Buffer> drop, Cleaner cleaner) {
        var segment = createSegment(size);
        if (cleaner != null) {
            segment = segment.registerCleaner(cleaner);
        }
        return new MemSegBuffer(segment, convert(drop), alloc);
    }

    @Override
    public Buffer allocateShared(AllocatorControl alloc, long size, Drop<Buffer> drop, Cleaner cleaner) {
        var segment = createSegment(size).share();
        if (cleaner != null) {
            segment = segment.registerCleaner(cleaner);
        }
        return new MemSegBuffer(segment, convert(drop), alloc);
    }

    protected abstract MemorySegment createSegment(long size);

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
    public Buffer recoverMemory(Object recoverableMemory, Drop<Buffer> drop) {
        var recovery = (RecoverableMemory) recoverableMemory;
        return recovery.recover(convert(drop));
    }

    @SuppressWarnings("unchecked")
    private static <T, R> Drop<R> convert(Drop<T> drop) {
        return (Drop<R>) drop;
    }
}
