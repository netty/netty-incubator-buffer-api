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

import java.nio.ByteOrder;

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
     * <p>
     * The buffer will use the current platform native byte order by default, for accessor methods that don't have an
     * explicit byte order.
     *
     * @param size The size of {@link Buf} to allocate.
     * @return The newly allocated {@link Buf}.
     */
    Buf allocate(long size);

    /**
     * Allocate a {@link Buf} of the given size in bytes. This method may throw an {@link OutOfMemoryError} if there is
     * not enough free memory available to allocate a {@link Buf} of the requested size.
     * <p>
     * The buffer will use the given byte order by default.
     *
     * @param size The size of {@link Buf} to allocate.
     * @param order The default byte order used by the accessor methods that don't have an explicit byte order.
     * @return The newly allocated {@link Buf}.
     */
    default Buf allocate(long size, ByteOrder order) {
        return allocate(size).order(order);
    }

    /**
     * Close this allocator, freeing all of its internal resources. It is not specified if the allocator can still be
     * used after this method has been called on it.
     */
    @Override
    default void close() {
    }

    static Allocator heap() {
        var man = MemoryManager.getHeapMemoryManager();
        return new Allocator() {
            @Override
            public Buf allocate(long size) {
                checkSize(size);
                return man.allocateConfined(size, man.drop(), null);
            }
        };
    }

    static Allocator direct() {
        var man = MemoryManager.getNativeMemoryManager();
        return new Allocator() {
            @Override
            public Buf allocate(long size) {
                checkSize(size);
                return man.allocateConfined(size, man.drop(), null);
            }
        };
    }

    static Allocator directWithCleaner() {
        var man = MemoryManager.getNativeMemoryManager();
        return new Allocator() {
            @Override
            public Buf allocate(long size) {
                checkSize(size);
                return man.allocateConfined(size, man.drop(), Statics.CLEANER);
            }
        };
    }

    static Allocator pooledHeap() {
        return new SizeClassedMemoryPool(MemoryManager.getHeapMemoryManager());
    }

    static Allocator pooledDirect() {
        return new SizeClassedMemoryPool(MemoryManager.getNativeMemoryManager());
    }

    static Allocator pooledDirectWithCleaner() {
        return new SizeClassedMemoryPool(MemoryManager.getNativeMemoryManager()) {
            @Override
            protected Drop<Buf> getDrop() {
                return new NativeMemoryCleanerDrop(this, getMemoryManager(), super.getDrop());
            }
        };
    }
}
