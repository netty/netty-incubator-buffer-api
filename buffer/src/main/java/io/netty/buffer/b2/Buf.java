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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A reference counted buffer API with separate reader and writer indexes.
 */
public interface Buf extends Rc<Buf>, BufAccessors {
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
     * {@linkplain #send() Sending} a composite buffer implies sending all of its constituent buffers.
     * <p>
     * All of the constituent buffers must have the same {@linkplain #order() byte order}.
     * An exception will be thrown if you attempt to compose buffers that have different byte orders,
     * and changing the byte order of the constituent buffers so they become inconsistent after construction,
     * will result in unspecified behaviour.
     * <p>
     * The read and write offsets of the constituent buffers must be arranged such that there are no "gaps" when viewed
     * as a single connected chunk of memory.
     * Specifically, there can be at most one buffer whose write offset is neither zero nor at capacity,
     * and all buffers prior to it must have their write indexes at capacity, and all buffers after it must have a write
     * offset of zero.
     * Likewise, there can be at most one buffer whose read offset is neither zero nor at capacity,
     * and all buffers prior to it must have their read indexes at capacity, and all buffers after it must have a read
     * offset of zero.
     * Furthermore, the sum of the read offsets must be less than or equal to the sum of the write offsets.
     * <p>
     * Reads and writes to the composite buffer that modifies the read or write offsets, will also modify the relevant
     * offsets in the constituent buffers.
     * <p>
     * It is not a requirement that the buffers have the same size.
     *
     * @param bufs The buffers to compose into a single buffer view.
     * @return A buffer composed of, and backed by, the given buffers.
     * @throws IllegalArgumentException if the given buffers have an inconsistent {@linkplain #order() byte order}.
     */
    static Buf compose(Buf... bufs) {
        return new CompositeBuf(bufs);
    }

    /**
     * Change the default byte order of this buffer, and return this buffer.
     *
     * @param order The new default byte order, used by accessor methods that don't use an explicit byte order.
     * @return This buffer instance.
     */
    Buf order(ByteOrder order);

    /**
     * The default byte order of this buffer.
     * @return The default byte order of this buffer.
     */
    ByteOrder order();

    /**
     * The capacity of this buffer, that is, the maximum number of bytes it can contain.
     *
     * @return The capacity in bytes.
     */
    int capacity();

    /**
     * Get the current reader index. The next read will happen from this byte index into the buffer.
     *
     * @return The current reader index.
     */
    int readerIndex();

    /**
     * Set the reader index. Make the next read happen from the given index.
     *
     * @param index The reader index to set.
     * @return This Buf.
     * @throws IndexOutOfBoundsException if the specified {@code index} is less than zero or greater than the current
     *                                   {@link #writerIndex()}.
     */
    Buf readerIndex(int index);

    /**
     * Get the current writer index. The next write will happen at this byte index into the byffer.
     *
     * @return The current writer index.
     */
    int writerIndex();

    /**
     * Set the writer index. Make the next write happen at the given index.
     *
     * @param index The writer index to set.
     * @return This Buf.
     * @throws IndexOutOfBoundsException if the specified {@code index} is less than the current {@link #readerIndex()}
     *                                   or greater than {@link #capacity()}.
     */
    Buf writerIndex(int index);

    /**
     * Returns the number of readable bytes which is equal to {@code (writerIndex() - readerIndex())}.
     */
    default int readableBytes() {
        return writerIndex() - readerIndex();
    }

    /**
     * Returns the number of writable bytes which is equal to {@code (capacity() - writerIndex())}.
     */
    default int writableBytes() {
        return capacity() - writerIndex();
    }

    /**
     * Fill the buffer with the given byte value. This method does not respect the {@link #readerIndex()} or {@link
     * #writerIndex()}, but copies the full capacity of the buffer. The {@link #readerIndex()} and {@link
     * #writerIndex()} are not modified.
     *
     * @param value The byte value to write at every index in the buffer.
     * @return This Buf.
     */
    Buf fill(byte value);

    /**
     * Create a byte array, and fill it with the complete contents of this buffer. This method does not respect the
     * {@link #readerIndex()} or {@link #writerIndex()}, but copies the full capacity of the buffer. The {@link
     * #readerIndex()} and {@link #writerIndex()} are not modified.
     *
     * @return A byte array that contains a copy of the contents of this buffer.
     */
    byte[] copy();

    /**
     * Give the native memory address backing this buffer, or return 0 if this buffer has no native memory address.
     * @return The native memory address, if any, otherwise 0.
     */
    long getNativeAddress();

    /**
     * Returns a slice of this buffer's readable bytes.
     * Modifying the content of the returned buffer or this buffer affects each other's content,
     * while they maintain separate indexes. This method is identical to
     * {@code buf.slice(buf.readerIndex(), buf.readableBytes())}.
     * This method does not modify {@link #readerIndex()} or {@link #writerIndex()} of this buffer.
     * <p>
     * This method increments the reference count of this buffer.
     * The reference count is decremented again when the slice is deallocated.
     *
     * @return A new buffer instance, with independent {@link #readerIndex()} and {@link #writerIndex()},
     * that is a view of the readable region of this buffer.
     */
    default Buf slice() {
        return slice(readerIndex(), readableBytes());
    }

    /**
     * Returns a slice of the given region of this buffer.
     * Modifying the content of the returned buffer or this buffer affects each other's content,
     * while they maintain separate indexes.
     * This method does not modify {@link #readerIndex()} or {@link #writerIndex()} of this buffer.
     * <p>
     * This method increments the reference count of this buffer.
     * The reference count is decremented again when the slice is deallocated.
     *
     * @return A new buffer instance, with independent {@link #readerIndex()} and {@link #writerIndex()},
     * that is a view of the given region of this buffer.
     */
    Buf slice(int offset, int length);

    /**
     * Copies the given length of data from this buffer into the given destination array, beginning at the given source
     * position in this buffer, and the given destination position in the destination array.
     * <p>
     * This method does not read or modify the {@linkplain #writerIndex() write offset} or the
     * {@linkplain #readerIndex() read offset}.
     *
     * @param srcPos The byte offset into this buffer wherefrom the copying should start; the byte at this offset in
     *              this buffer will be copied to the {@code destPos} index in the {@code dest} array.
     * @param dest The destination byte array.
     * @param destPos The index into the {@code dest} array wherefrom the copying should start.
     * @param length The number of bytes to copy.
     * @throws NullPointerException if the destination array is null.
     * @throws IndexOutOfBoundsException if the source or destination positions, or the length, are negative,
     * or if the resulting end positions reaches beyond the end of either this buffer or the destination array.
     */
    void copyInto(int srcPos, byte[] dest, int destPos, int length);

    /**
     * Copies the given length of data from this buffer into the given destination byte buffer, beginning at the given
     * source position in this buffer, and the given destination position in the destination byte buffer.
     * <p>
     * This method does not read or modify the {@linkplain #writerIndex() write offset} or the
     * {@linkplain #readerIndex() read offset}, nor is the position of the destination buffer changed.
     * <p>
     * The position and limit of the destination byte buffer are also ignored, and do not influence {@code destPos}
     * or {@code length}.
     *
     * @param srcPos The byte offset into this buffer wherefrom the copying should start; the byte at this offset in
     *              this buffer will be copied to the {@code destPos} index in the {@code dest} array.
     * @param dest The destination byte buffer.
     * @param destPos The index into the {@code dest} array wherefrom the copying should start.
     * @param length The number of bytes to copy.
     * @throws NullPointerException if the destination array is null.
     * @throws IndexOutOfBoundsException if the source or destination positions, or the length, are negative,
     * or if the resulting end positions reaches beyond the end of either this buffer or the destination array.
     */
    void copyInto(int srcPos, ByteBuffer dest, int destPos, int length);

    /**
     * Copies the given length of data from this buffer into the given destination buffer, beginning at the given
     * source position in this buffer, and the given destination position in the destination buffer.
     * <p>
     * This method does not read or modify the {@linkplain #writerIndex() write offset} or the
     * {@linkplain #readerIndex() read offset} on this buffer, nor on the destination buffer.
     * <p>
     * The read and write offsets of the destination buffer are also ignored, and do not influence {@code destPos}
     * or {@code length}.
     *
     * @param srcPos The byte offset into this buffer wherefrom the copying should start; the byte at this offset in
     *              this buffer will be copied to the {@code destPos} index in the {@code dest} array.
     * @param dest The destination buffer.
     * @param destPos The index into the {@code dest} array wherefrom the copying should start.
     * @param length The number of bytes to copy.
     * @throws NullPointerException if the destination array is null.
     * @throws IndexOutOfBoundsException if the source or destination positions, or the length, are negative,
     * or if the resulting end positions reaches beyond the end of either this buffer or the destination array.
     */
    void copyInto(int srcPos, Buf dest, int destPos, int length);

    /**
     * Resets the {@linkplain #readerIndex() read offset} and the {@linkplain #writerIndex() write offset} on this
     * buffer to their initial values.
     */
    default void reset() {
        readerIndex(0);
        writerIndex(0);
    }
}