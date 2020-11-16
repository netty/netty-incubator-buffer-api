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

import jdk.incubator.foreign.MemorySegment;

import java.lang.ref.Cleaner;

public interface MemoryManager {
    static MemoryManager getHeapMemoryManager() {
        return new HeapMemorySegmentManager();
    }

    static MemoryManager getNativeMemoryManager() {
        return new NativeMemorySegmentManager();
    }

    boolean isNative();
    Buf allocateConfined(AllocatorControl alloc, long size, Drop<Buf> drop, Cleaner cleaner);
    Buf allocateShared(AllocatorControl allo, long size, Drop<Buf> drop, Cleaner cleaner);
    Drop<Buf> drop();
    Object unwrapRecoverableMemory(Buf buf);
    Buf recoverMemory(Object recoverableMemory, Drop<Buf> drop);

    abstract class MemorySegmentManager implements MemoryManager {
        @Override
        public abstract boolean isNative();

        @Override
        public Buf allocateConfined(AllocatorControl alloc, long size, Drop<Buf> drop, Cleaner cleaner) {
            var segment = createSegment(size);
            if (cleaner != null) {
                segment = segment.registerCleaner(cleaner);
            }
            return new MemSegBuf(segment, convert(drop), alloc);
        }

        @Override
        public Buf allocateShared(AllocatorControl alloc, long size, Drop<Buf> drop, Cleaner cleaner) {
            var segment = createSegment(size).share();
            if (cleaner != null) {
                segment = segment.registerCleaner(cleaner);
            }
            return new MemSegBuf(segment, convert(drop), alloc);
        }

        protected abstract MemorySegment createSegment(long size);

        @Override
        public Drop<Buf> drop() {
            return convert(MemSegBuf.SEGMENT_CLOSE);
        }

        @Override
        public Object unwrapRecoverableMemory(Buf buf) {
            var b = (MemSegBuf) buf;
            return b.recoverableMemory();
        }

        @Override
        public Buf recoverMemory(Object recoverableMemory, Drop<Buf> drop) {
            var recovery = (MemSegBuf.RecoverableMemory) recoverableMemory;
            return recovery.recover(convert(drop));
        }

        @SuppressWarnings("unchecked")
        private static <T,R> Drop<R> convert(Drop<T> drop) {
            return (Drop<R>) drop;
        }
    }

    class HeapMemorySegmentManager extends MemorySegmentManager {
        @Override
        public boolean isNative() {
            return false;
        }

        @Override
        protected MemorySegment createSegment(long size) {
            return MemorySegment.ofArray(new byte[Math.toIntExact(size)]);
        }
    }

    class NativeMemorySegmentManager extends MemorySegmentManager {
        @Override
        public boolean isNative() {
            return true;
        }

        @Override
        protected MemorySegment createSegment(long size) {
            var segment = MemorySegment.allocateNative(size);
//                                       .withCleanupAction(Statics.getCleanupAction(size));
            Statics.MEM_USAGE_NATIVE.add(size);
            return segment;
        }
    }
}
