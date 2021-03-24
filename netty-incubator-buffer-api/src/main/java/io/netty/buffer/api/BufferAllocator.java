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
package io.netty.buffer.api;

import io.netty.buffer.api.internal.Statics;

import java.nio.ByteOrder;

/**
 * Interface for {@link Buffer} allocators.
 */
public interface BufferAllocator extends AutoCloseable {
    /**
     * Check that the given {@code size} argument is a valid buffer size, or throw an {@link IllegalArgumentException}.
     *
     * @param size The size to check.
     * @throws IllegalArgumentException if the size is not possitive, or if the size is too big (over ~2 GB) for a
     * buffer to accomodate.
     */
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
     * Allocate a {@link Buffer} of the given size in bytes. This method may throw an {@link OutOfMemoryError} if there
     * is not enough free memory available to allocate a {@link Buffer} of the requested size.
     * <p>
     * The buffer will use the current platform native byte order by default, for accessor methods that don't have an
     * explicit byte order.
     *
     * @param size The size of {@link Buffer} to allocate.
     * @return The newly allocated {@link Buffer}.
     */
    Buffer allocate(int size);

    /**
     * Allocate a {@link Buffer} of the given size in bytes. This method may throw an {@link OutOfMemoryError} if there
     * is not enough free memory available to allocate a {@link Buffer} of the requested size.
     * <p>
     * The buffer will use the given byte order by default.
     *
     * @param size The size of {@link Buffer} to allocate.
     * @param order The default byte order used by the accessor methods that don't have an explicit byte order.
     * @return The newly allocated {@link Buffer}.
     */
    default Buffer allocate(int size, ByteOrder order) {
        return allocate(size).order(order);
    }

    /**
     * Close this allocator, freeing all of its internal resources. It is not specified if the allocator can still be
     * used after this method has been called on it.
     */
    @Override
    default void close() {
    }

    static BufferAllocator heap() {
        return new ManagedBufferAllocator(MemoryManagers.getManagers().getHeapMemoryManager(), Statics.CLEANER);
    }

    static BufferAllocator direct() {
        return new ManagedBufferAllocator(MemoryManagers.getManagers().getNativeMemoryManager(), Statics.CLEANER);
    }

    static BufferAllocator pooledHeap() {
        return new SizeClassedMemoryPool(MemoryManagers.getManagers().getHeapMemoryManager());
    }

    static BufferAllocator pooledDirect() {
        return new SizeClassedMemoryPool(MemoryManagers.getManagers().getNativeMemoryManager());
    }
}
