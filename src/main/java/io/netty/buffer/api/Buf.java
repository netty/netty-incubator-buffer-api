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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A reference counted buffer of memory, with separate reader and writer offsets.
 * <p>
 * A buffer is a sequential stretch of memory with a certain capacity, an offset for writing, and an offset for reading.
 *
 * <h3>Creating a buffer</h3>
 *
 * Buffers are created by {@linkplain Allocator allocators}, and their {@code allocate} family of methods.
 * A number of standard allocators exist, and ara available through static methods on the {@code Allocator} interface.
 *
 * <h3>Life cycle and reference counting</h3>
 *
 * The buffer has a life cycle, where it is allocated, used, and deallocated.
 * The reference count controls this life cycle.
 * <p>
 * When the buffer is initially allocated, a pairing {@link #close()} call will deallocate it.
 * In this state, the buffer {@linkplain #isOwned() is "owned"}.
 * <p>
 * The buffer can also be {@linkplain #acquire() acquired} when it's about to be involved in a complicated life time.
 * The {@link #acquire()} call increments the reference count of the buffer,
 * and a pairing {@link #close()} call will decrement the reference count.
 * Each acquire lends out the buffer, and the buffer is said to be in a "borrowed" state.
 * <p>
 * Certain operations, such as {@link #send()}, are only available on owned buffers.
 *
 * <h3>Thread-safety</h3>
 *
 * Buffers are not thread-safe.
 * The reference counting implied by the {@link Rc} interface is itself not thread-safe,
 * and buffers additionally contain other mutable data that is not thread-safe.
 * Depending on the buffer implementation, the buffer may impose confinement restrictions as well,
 * so that the buffer cannot even be read using absolute offsets,
 * such as with the {@link #getByte(int)} method,
 * from multiple threads.
 * <p>
 * Confined buffers will initially be confined to the thread that allocates them.
 * <p>
 * If a buffer needs to be accessed by a different thread,
 * then the ownership of that buffer must be sent to that thread.
 * This can be done with the {@link #send()} method.
 * The send method consumes the buffer, if it is in an owned state, and produces a {@link Send} object.
 * The {@link Send} object can then be shared in a thread-safe way (so called "safe publication"),
 * with the intended recipient thread.
 * <p>
 * To send a buffer to another thread, the buffer must not have any outstanding borrows.
 * That is to say, all {@linkplain #acquire() acquires} must have been paired with a {@link #close()};
 * all {@linkplain #slice() slices} must have been closed.
 * And if this buffer is a constituent of a {@linkplain Allocator#compose(Buf...) composite buffer},
 * then that composite buffer must be closed.
 * And if this buffer is itself a composite buffer, then it must own all of its constituent buffers.
 * The {@link #isOwned()} method can be used on any buffer to check if it can be sent or not.
 *
 * <h3>Accessing data</h3>
 *
 * Data access methods fall into two classes:
 * <ol>
 *     <li>Access that are based on, and updates, the read or write offset positions.</li>
 *     <ul><li>These accessor methods are typically called {@code readX} or {@code writeX}.</li></ul>
 *     <li>Access that take offsets as arguments, and do not update read or write offset positions.</li>
 *     <ul><li>These accessor methods are typically called {@code getX} or {@code setX}.</li></ul>
 * </ol>
 *
 * A buffer contain two mutable offset positions: one for reading and one for writing.
 * These positions use <a href="https://en.wikipedia.org/wiki/Zero-based_numbering">zero-based indexing</a>,
 * such that the first byte of data in the buffer is placed at offset {@code 0},
 * and the last byte in the buffer is at offset {@link #capacity() capacity - 1}.
 * The {@link #readerOffset()} is the offset into the buffer from which the next read will take place,
 * and is initially zero.
 * The reader offset must always be less than or equal to the {@link #writerOffset()}.
 * The {@link #writerOffset()} is likewise the offset into the buffer where the next write will take place.
 * The writer offset is also initially zero, and must be less than or equal to the {@linkplain #capacity() capacity}.
 * <p>
 * This carves the buffer into three regions, as demonstrated by this diagram:
 * <pre>
 *      +-------------------+------------------+------------------+
 *      | discardable bytes |  readable bytes  |  writable bytes  |
 *      |                   |     (CONTENT)    |                  |
 *      +-------------------+------------------+------------------+
 *      |                   |                  |                  |
 *      0      <=     readerOffset  <=   writerOffset    <=    capacity
 * </pre>
 *
 */
public interface Buf extends Rc<Buf>, BufAccessors {
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
     * Get the current reader offset. The next read will happen from this byte offset into the buffer.
     *
     * @return The current reader offset.
     */
    int readerOffset();

    /**
     * Set the reader offset. Make the next read happen from the given offset into the buffer.
     *
     * @param offset The reader offset to set.
     * @return This Buf.
     * @throws IndexOutOfBoundsException if the specified {@code offset} is less than zero or greater than the current
     *                                   {@link #writerOffset()}.
     */
    Buf readerOffset(int offset);

    /**
     * Get the current writer offset. The next write will happen at this byte offset into the byffer.
     *
     * @return The current writer offset.
     */
    int writerOffset();

    /**
     * Set the writer offset. Make the next write happen at the given offset.
     *
     * @param offset The writer offset to set.
     * @return This Buf.
     * @throws IndexOutOfBoundsException if the specified {@code offset} is less than the current
     * {@link #readerOffset()} or greater than {@link #capacity()}.
     */
    Buf writerOffset(int offset);

    /**
     * Returns the number of readable bytes which is equal to {@code (writerOffset() - readerOffset())}.
     */
    default int readableBytes() {
        return writerOffset() - readerOffset();
    }

    /**
     * Returns the number of writable bytes which is equal to {@code (capacity() - writerOffset())}.
     */
    default int writableBytes() {
        return capacity() - writerOffset();
    }

    /**
     * Fill the buffer with the given byte value. This method does not respect the {@link #readerOffset()} or {@link
     * #writerOffset()}, but copies the full capacity of the buffer. The {@link #readerOffset()} and {@link
     * #writerOffset()} are not modified.
     *
     * @param value The byte value to write at every position in the buffer.
     * @return This Buf.
     */
    Buf fill(byte value);

    /**
     * Give the native memory address backing this buffer, or return 0 if this buffer has no native memory address.
     * @return The native memory address, if any, otherwise 0.
     */
    long getNativeAddress();

    /**
     * Returns a slice of this buffer's readable bytes.
     * Modifying the content of the returned buffer or this buffer affects each other's content,
     * while they maintain separate offsets. This method is identical to
     * {@code buf.slice(buf.readerOffset(), buf.readableBytes())}.
     * This method does not modify {@link #readerOffset()} or {@link #writerOffset()} of this buffer.
     * <p>
     * This method increments the reference count of this buffer.
     * The reference count is decremented again when the slice is deallocated.
     * <p>
     * The slice is created with a {@linkplain #writerOffset() write offset} equal to the length of the slice,
     * so that the entire contents of the slice is ready to be read.
     *
     * @return A new buffer instance, with independent {@link #readerOffset()} and {@link #writerOffset()},
     * that is a view of the readable region of this buffer.
     */
    default Buf slice() {
        return slice(readerOffset(), readableBytes());
    }

    /**
     * Returns a slice of the given region of this buffer.
     * Modifying the content of the returned buffer or this buffer affects each other's content,
     * while they maintain separate offsets.
     * This method does not modify {@link #readerOffset()} or {@link #writerOffset()} of this buffer.
     * <p>
     * This method increments the reference count of this buffer.
     * The reference count is decremented again when the slice is deallocated.
     * <p>
     * The slice is created with a {@linkplain #writerOffset() write offset} equal to the length of the slice,
     * so that the entire contents of the slice is ready to be read.
     *
     * @return A new buffer instance, with independent {@link #readerOffset()} and {@link #writerOffset()},
     * that is a view of the given region of this buffer.
     */
    Buf slice(int offset, int length);

    /**
     * Copies the given length of data from this buffer into the given destination array, beginning at the given source
     * position in this buffer, and the given destination position in the destination array.
     * <p>
     * This method does not read or modify the {@linkplain #writerOffset() write offset} or the
     * {@linkplain #readerOffset() read offset}.
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
     * This method does not read or modify the {@linkplain #writerOffset() write offset} or the
     * {@linkplain #readerOffset() read offset}, nor is the position of the destination buffer changed.
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
     * This method does not read or modify the {@linkplain #writerOffset() write offset} or the
     * {@linkplain #readerOffset() read offset} on this buffer, nor on the destination buffer.
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
     * Resets the {@linkplain #readerOffset() read offset} and the {@linkplain #writerOffset() write offset} on this
     * buffer to their initial values.
     */
    default Buf reset() {
        readerOffset(0);
        writerOffset(0);
        return this;
    }

    /**
     * Iterate the readable bytes of this buffer. The {@linkplain #readerOffset() reader offset} and
     * {@linkplain #writerOffset() witer offset} are not modified by the iterator.
     * <p>
     * Care should be taken to ensure that the buffers lifetime extends beyond the iteration, and the
     * {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() writer offset} are not modified while
     * the iteration takes place. Otherwise unpredictable behaviour might result.
     *
     * @return A {@link ByteIterator} for the readable bytes of this buffer.
     */
    default ByteIterator iterate() {
        return iterate(readerOffset(), readableBytes());
    }

    /**
     * Iterate the given number bytes of this buffer, starting at the given offset.
     * The {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() witer offset} are not modified by
     * the iterator.
     * <p>
     * Care should be taken to ensure that the buffers lifetime extends beyond the iteration, and the
     * {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() writer offset} are not modified while
     * the iteration takes place. Otherwise unpredictable behaviour might result.
     *
     * @param fromOffset The offset into the buffer where iteration should start.
     *                  The first byte read from the iterator will be the byte at this offset.
     * @param length The number of bytes to iterate.
     * @return A {@link ByteIterator} for the given stretch of bytes of this buffer.
     * @throws IllegalArgumentException if the length is negative, or if the region given by the {@code fromOffset} and
     * the {@code length} reaches outside of the bounds of this buffer.
     */
    ByteIterator iterate(int fromOffset, int length);

    /**
     * Iterate the readable bytes of this buffer, in reverse. The {@linkplain #readerOffset() reader offset} and
     * {@linkplain #writerOffset() witer offset} are not modified by the iterator.
     * <p>
     * Care should be taken to ensure that the buffers lifetime extends beyond the iteration, and the
     * {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() writer offset} are not modified while
     * the iteration takes place. Otherwise unpredictable behaviour might result.
     *
     * @return A {@link ByteIterator} for the readable bytes of this buffer.
     */
    default ByteIterator iterateReverse() {
        int woff = writerOffset();
        return iterateReverse(woff == 0? 0 : woff - 1, readableBytes());
    }

    /**
     * Iterate the given number bytes of this buffer, in reverse, starting at the given offset.
     * The {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() witer offset} are not modified by
     * the iterator.
     * <p>
     * Care should be taken to ensure that the buffers lifetime extends beyond the iteration, and the
     * {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() writer offset} are not modified while
     * the iteration takes place. Otherwise unpredictable behaviour might result.
     *
     * @param fromOffset The offset into the buffer where iteration should start.
     *                  The first byte read from the iterator will be the byte at this offset.
     * @param length The number of bytes to iterate.
     * @return A {@link ByteIterator} for the given stretch of bytes of this buffer.
     * @throws IllegalArgumentException if the length is negative, or if the region given by the {@code fromOffset} and
     * the {@code length} reaches outside of the bounds of this buffer.
     */
    ByteIterator iterateReverse(int fromOffset, int length);

    /**
     * Ensure that this buffer has {@linkplain #writableBytes() available space for writing} the given number of
     * bytes.
     * The buffer must be in {@linkplain #isOwned() an owned state}, or an exception will be thrown.
     * If this buffer already has the necessary space, then this method returns immediately.
     * If this buffer does not already have the necessary space, then it will be expanded using the {@link Allocator}
     * the buffer was created with.
     *
     * @param size The requested number of bytes of space that should be available for writing.
     * @throws IllegalStateException if this buffer is not in an owned state.
     * That is, if {@link #countBorrows()} is not {@code 0}.
     */
    void ensureWritable(int size);
}
