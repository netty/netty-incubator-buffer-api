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

import jdk.incubator.foreign.MemorySegment;

import static io.netty.buffer.b2.BBuf.*;

/**
 * Interface for {@link Buf} allocators.
 */
public interface Allocator extends AutoCloseable {
    static void checkSize(long size) {
        if (size < 1) {
            throw new IllegalArgumentException("Buffer size must be positive, but was " + size + '.');
        }
        // We use max array size because on-heap buffers will be backed by byte-arrays.
        int maxArraySize = Integer.MAX_VALUE - 8;
        if (size > maxArraySize) {
            throw new IllegalArgumentException(
                    "Buffer size cannot be greater than " + maxArraySize + ", but was " + size + '.');
        }
    }

    /**
     * Allocate a {@link Buf} of the given size in bytes. This method may throw an {@link OutOfMemoryError} if there is
     * not enough free memory available to allocate a {@link Buf} of the requested size.
     *
     * @param size The size of {@link Buf} to allocate.
     * @return The newly allocated {@link Buf}.
     */
    Buf allocate(long size);

    /**
     * Close this allocator, freeing all of its internal resources. It is not specified if the allocator can still be
     * used after this method has been called on it.
     */
    @Override
    default void close() {
    }

    static Allocator heap() {
        return new Allocator() {
            @Override
            public BBuf allocate(long size) {
                checkSize(size);
                var segment = MemorySegment.ofArray(new byte[Math.toIntExact(size)]);
                return new BBuf(segment, SEGMENT_CLOSE);
            }
        };
    }

    static Allocator direct() {
        return new Allocator() {
            @Override
            public Buf allocate(long size) {
                checkSize(size);
                var segment = MemorySegment.allocateNative(size);
                Statics.MEM_USAGE_NATIVE.add(size);
                return new BBuf(segment, SEGMENT_CLOSE_NATIVE);
            }
        };
    }

    static Allocator pooledHeap() {
        return new SizeClassedMemoryPool(false) {
            @Override
            protected MemorySegment createMemorySegment(long size) {
                checkSize(size);
                return MemorySegment.ofArray(new byte[Math.toIntExact(size)]);
            }
        };
    }

    static Allocator pooledDirect() {
        return new SizeClassedMemoryPool(true) {
            @Override
            protected MemorySegment createMemorySegment(long size) {
                checkSize(size);
                return MemorySegment.allocateNative(size);
            }
        };
    }

    static Allocator pooledDirectWithCleaner() {
        return new SizeClassedMemoryPool(true) {
            @Override
            protected MemorySegment createMemorySegment(long size) {
                checkSize(size);
                return MemorySegment.allocateNative(size);
            }

            @Override
            protected BBuf createBBuf(MemorySegment segment) {
                var drop = new NativeMemoryCleanerDrop();
                var buf = new BBuf(segment, drop);
                drop.accept(buf);
                return buf;
            }
        };
    }
}
