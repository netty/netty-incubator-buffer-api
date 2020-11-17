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
    Buf allocate(int size);

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
    default Buf allocate(int size, ByteOrder order) {
        return allocate(size).order(order);
    }

    /**
     * Compose the given sequence of buffers and present them as a single buffer.
     * <p>
     * <strong>Note:</strong> The composite buffer increments the reference count on all the constituent buffers,
     * and holds a reference to them until the composite buffer is deallocated.
     * This means the constituent buffers must still have their outside-reference count decremented as normal.
     * If the buffers are allocated for the purpose of participating in the composite buffer,
     * then they should be closed as soon as the composite buffer has been created, like in this example:
     * <pre>{@code
     *     try (Buf a = allocator.allocate(size);
     *          Buf b = allocator.allocate(size)) {
     *         return Buf.compose(a, b); // Reference counts for 'a' and 'b' incremented here.
     *     } // Reference count for 'a' and 'b' decremented here; composite buffer now holds the last references.
     * }</pre>
     * <p>
     * {@linkplain Buf#send() Sending} a composite buffer implies sending all of its constituent buffers.
     * For sending to be possible, both the composite buffer itself, and all of its constituent buffers, must be in an
     * {@linkplain Rc#isOwned() owned state}.
     * This means that the composite buffer must be the only reference to the constituent buffers.
     * <p>
     * All of the constituent buffers must have the same {@linkplain Buf#order() byte order}.
     * An exception will be thrown if you attempt to compose buffers that have different byte orders,
     * and changing the byte order of the constituent buffers so they become inconsistent after construction,
     * will result in unspecified behaviour.
     * <p>
     * The read and write offsets of the constituent buffers must be arranged such that there are no "gaps" when viewed
     * as a single connected chunk of memory.
     * Specifically, there can be at most one buffer whose write offset is neither zero nor at capacity,
     * and all buffers prior to it must have their write offsets at capacity, and all buffers after it must have a write
     * offset of zero.
     * Likewise, there can be at most one buffer whose read offset is neither zero nor at capacity,
     * and all buffers prior to it must have their read offsets at capacity, and all buffers after it must have a read
     * offset of zero.
     * Furthermore, the sum of the read offsets must be less than or equal to the sum of the write offsets.
     * <p>
     * Reads and writes to the composite buffer that modifies the read or write offsets, will also modify the relevant
     * offsets in the constituent buffers.
     * <p>
     * It is not a requirement that the buffers have the same size.
     * <p>
     * It is not a requirement that the buffers are allocated by this allocator, but if {@link Buf#ensureWritable(int)}
     * is called on the composed buffer, and the composed buffer needs to be expanded, then this allocator instance
     * will be used for allocation the extra memory.
     *
     * @param bufs The buffers to compose into a single buffer view.
     * @return A buffer composed of, and backed by, the given buffers.
     * @throws IllegalArgumentException if the given buffers have an inconsistent {@linkplain Buf#order() byte order}.
     */
    default Buf compose(Buf... bufs) {
        return new CompositeBuf(this, bufs);
    }

    /**
     * Close this allocator, freeing all of its internal resources. It is not specified if the allocator can still be
     * used after this method has been called on it.
     */
    @Override
    default void close() {
    }

    static Allocator heap() {
        return new ManagedAllocator(MemoryManager.getHeapMemoryManager(), null);
    }

    static Allocator direct() {
        return new ManagedAllocator(MemoryManager.getNativeMemoryManager(), null);
    }

    static Allocator directWithCleaner() {
        return new ManagedAllocator(MemoryManager.getNativeMemoryManager(), Statics.CLEANER);
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
