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
 * Buffers are created by {@linkplain BufferAllocator allocators}, and their {@code allocate} family of methods.
 * A number of standard allocators exist, and ara available through static methods on the {@code BufferAllocator}
 * interface.
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
 * And if this buffer is a constituent of a {@linkplain BufferAllocator#compose(Deref...) composite buffer},
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
public interface Buffer extends Rc<Buffer>, BufferAccessors {
    /**
     * Change the default byte order of this buffer, and return this buffer.
     *
     * @param order The new default byte order, used by accessor methods that don't use an explicit byte order.
     * @return This buffer instance.
     */
    Buffer order(ByteOrder order);

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
     * @return This Buffer.
     * @throws IndexOutOfBoundsException if the specified {@code offset} is less than zero or greater than the current
     *                                   {@link #writerOffset()}.
     */
    Buffer readerOffset(int offset);

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
     * @return This Buffer.
     * @throws IndexOutOfBoundsException if the specified {@code offset} is less than the current
     * {@link #readerOffset()} or greater than {@link #capacity()}.
     * @throws IllegalStateException if this buffer is {@linkplain #readOnly() read-only}.
     */
    Buffer writerOffset(int offset);

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
     * @return This Buffer.
     * @throws IllegalStateException if this buffer is {@linkplain #readOnly() read-only}.
     */
    Buffer fill(byte value);

    /**
     * Give the native memory address backing this buffer, or return 0 if this buffer has no native memory address.
     * @return The native memory address, if any, otherwise 0.
     */
    long nativeAddress();

    /**
     * Set the read-only state of this buffer.
     *
     * @return this buffer.
     */
    Buffer readOnly(boolean readOnly);

    /**
     * Query if this buffer is read-only or not.
     *
     * @return {@code true} if this buffer is read-only, {@code false} otherwise.
     */
    boolean readOnly();

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
    default Buffer slice() {
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
    Buffer slice(int offset, int length);

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
    void copyInto(int srcPos, Buffer dest, int destPos, int length);

    /**
     * Resets the {@linkplain #readerOffset() read offset} and the {@linkplain #writerOffset() write offset} on this
     * buffer to their initial values.
     */
    default Buffer reset() {
        readerOffset(0);
        writerOffset(0);
        return this;
    }

    /**
     * Open a cursor to iterate the readable bytes of this buffer. The {@linkplain #readerOffset() reader offset} and
     * {@linkplain #writerOffset() witer offset} are not modified by the cursor.
     * <p>
     * Care should be taken to ensure that the buffers lifetime extends beyond the cursor and the iteration, and that
     * the {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() writer offset} are not modified
     * while the iteration takes place. Otherwise unpredictable behaviour might result.
     *
     * @return A {@link ByteCursor} for iterating the readable bytes of this buffer.
     */
    default ByteCursor openCursor() {
        return openCursor(readerOffset(), readableBytes());
    }

    /**
     * Open a cursor to iterate the given number bytes of this buffer, starting at the given offset.
     * The {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() witer offset} are not modified by
     * the cursor.
     * <p>
     * Care should be taken to ensure that the buffers lifetime extends beyond the cursor and the iteration, and that
     * the {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() writer offset} are not modified
     * while the iteration takes place. Otherwise unpredictable behaviour might result.
     *
     * @param fromOffset The offset into the buffer where iteration should start.
     *                  The first byte read from the iterator will be the byte at this offset.
     * @param length The number of bytes to iterate.
     * @return A {@link ByteCursor} for the given stretch of bytes of this buffer.
     * @throws IllegalArgumentException if the length is negative, or if the region given by the {@code fromOffset} and
     * the {@code length} reaches outside of the bounds of this buffer.
     */
    ByteCursor openCursor(int fromOffset, int length);

    /**
     * Open a cursor to iterate the readable bytes of this buffer, in reverse.
     * The {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() witer offset} are not modified by
     * the cursor.
     * <p>
     * Care should be taken to ensure that the buffers lifetime extends beyond the cursor and the iteration, and that
     * the {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() writer offset} are not modified
     * while the iteration takes place. Otherwise unpredictable behaviour might result.
     *
     * @return A {@link ByteCursor} for the readable bytes of this buffer.
     */
    default ByteCursor openReverseCursor() {
        int woff = writerOffset();
        return openReverseCursor(woff == 0? 0 : woff - 1, readableBytes());
    }

    /**
     * Open a cursor to iterate the given number bytes of this buffer, in reverse, starting at the given offset.
     * The {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() witer offset} are not modified by
     * the cursor.
     * <p>
     * Care should be taken to ensure that the buffers lifetime extends beyond the cursor and the iteration, and that
     * the {@linkplain #readerOffset() reader offset} and {@linkplain #writerOffset() writer offset} are not modified
     * while the iteration takes place. Otherwise unpredictable behaviour might result.
     *
     * @param fromOffset The offset into the buffer where iteration should start.
     *                  The first byte read from the iterator will be the byte at this offset.
     * @param length The number of bytes to iterate.
     * @return A {@link ByteCursor} for the given stretch of bytes of this buffer.
     * @throws IllegalArgumentException if the length is negative, or if the region given by the {@code fromOffset} and
     * the {@code length} reaches outside of the bounds of this buffer.
     */
    ByteCursor openReverseCursor(int fromOffset, int length);

    /**
     * Ensure that this buffer has {@linkplain #writableBytes() available space for writing} the given number of
     * bytes.
     * The buffer must be in {@linkplain #isOwned() an owned state}, or an exception will be thrown.
     * If this buffer already has the necessary space, then this method returns immediately.
     * If this buffer does not already have the necessary space, then it will be expanded using the
     * {@link BufferAllocator} the buffer was created with.
     * This method is the same as calling {@link #ensureWritable(int, boolean)} where {@code allowCompaction} is
     * {@code false}.
     *
     * @param size The requested number of bytes of space that should be available for writing.
     * @throws IllegalStateException if this buffer is not in an {@linkplain #isOwned() owned} state,
     * or is {@linkplain #readOnly() read-only}.
     */
    default void ensureWritable(int size) {
        ensureWritable(size, true);
    }

    /**
     * Ensure that this buffer has {@linkplain #writableBytes() available space for writing} the given number of
     * bytes.
     * The buffer must be in {@linkplain #isOwned() an owned state}, or an exception will be thrown.
     * If this buffer already has the necessary space, then this method returns immediately.
     * If this buffer does not already have the necessary space, then space will be made available in one or all of
     * the following available ways:
     *
     * <ul>
     *     <li>
     *         If {@code allowCompaction} is {@code true}, and sum of the read and writable bytes would be enough to
     *         satisfy the request, and it (depending on the buffer implementation) seems faster and easier to compact
     *         the existing buffer rather than allocation a new buffer, then the requested bytes will be made available
     *         that way. The compaction will not necessarily work the same way as the {@link #compact()} method, as the
     *         implementation may be able to make the requested bytes available with less effort than is strictly
     *         mandated by the {@link #compact()} method.
     *     </li>
     *     <li>
     *         Regardless of the value of the {@code allowCompaction}, the implementation may make more space available
     *         by just allocating more or larger buffers. This allocation would use the same {@link BufferAllocator}
     *         that this buffer was created with.
     *     </li>
     *     <li>
     *         If {@code allowCompaction} is {@code true}, then the implementation may choose to do a combination of
     *         compaction and allocation.
     *     </li>
     * </ul>
     *
     * @param size The requested number of bytes of space that should be available for writing.
     * @param allowCompaction {@code true} if the method is allowed to modify the
     *                                   {@linkplain #readerOffset() reader offset} and
     *                                   {@linkplain #writerOffset() writer offset}, otherwise {@code false}.
     * @throws IllegalStateException if this buffer is not in an {@linkplain #isOwned() owned} state,
     *      * or is {@linkplain #readOnly() read-only}.
     */
    void ensureWritable(int size, boolean allowCompaction);

    /**
     * Split the buffer into two, at the {@linkplain #writerOffset() write offset} position.
     * <p>
     * The buffer must be in {@linkplain #isOwned() an owned state}, or an exception will be thrown.
     * <p>
     * The region of this buffer that contain the read and readable bytes, will be captured and returned in a new
     * buffer, that will hold its own ownership of that region. This allows the returned buffer to be indepentently
     * {@linkplain #send() sent} to other threads.
     * <p>
     * The returned buffer will adopt the {@link #readerOffset()} of this buffer, and have its {@link #writerOffset()}
     * and {@link #capacity()} both set to the equal to the write offset of this buffer.
     * <p>
     * The memory region in the returned buffer will become inaccessible through this buffer. This buffer will have its
     * capacity reduced by the capacity of the returned buffer, and the read and write offsets of this buffer will both
     * become zero, even though their position in memory remain unchanged.
     * <p>
     * Effectively, the following transformation takes place:
     * <pre>{@code
     *         This buffer:
     *          +------------------------------------------+
     *         0|   |r/o                  |w/o             |cap
     *          +---+---------------------+----------------+
     *         /   /                     / \               \
     *        /   /                     /   \               \
     *       /   /                     /     \               \
     *      /   /                     /       \               \
     *     /   /                     /         \               \
     *    +---+---------------------+           +---------------+
     *    |   |r/o                  |w/o & cap  |r/o & w/o      |cap
     *    +---+---------------------+           +---------------+
     *    Returned buffer.                      This buffer.
     * }</pre>
     * When the buffers are in this state, both of the bifurcated parts retain an atomic reference count on the
     * underlying memory. This means that shared underlying memory will not be deallocated or returned to a pool, until
     * all of the bifurcated parts have been closed.
     * <p>
     * Composite buffers have it a little easier, in that at most only one of the constituent buffers will actually be
     * bifurcated. If the split point lands perfectly between two constituent buffers, then a composite buffer can
     * simply split its internal array in two.
     * <p>
     * Bifurcated buffers support all operations that normal buffers do, including {@link #ensureWritable(int)}.
     *
     * @return A new buffer with independent and exclusive ownership over the read and readable bytes from this buffer.
     */
    Buffer bifurcate();

    /**
     * Discards the read bytes, and moves the buffer contents to the beginning of the buffer.
     *
     * @throws IllegalStateException if this buffer is not in an {@linkplain #isOwned() owned} state,
     * or is {@linkplain #readOnly() read-only}.
     */
    void compact();

    /**
     * Get the number of "components" in this buffer. For composite buffers, this is the number of transitive
     * constituent buffers, while non-composite buffers only have one component.
     *
     * @return The number of components in this buffer.
     */
    int countComponents();

    /**
     * Get the number of "components" in this buffer, that are readable. These are the components that would be
     * processed by {@link #forEachReadable(int, ReadableComponentProcessor)}. For composite buffers, this is the
     * number of transitive constituent buffers that are readable, while non-composite buffers only have at most one
     * readable component.
     * <p>
     * The number of readable components may be less than the {@link #countComponents() component count}, if not all of
     * them have readable data.
     *
     * @return The number of readable components in this buffer.
     */
    int countReadableComponents();

    /**
     * Get the number of "components" in this buffer, that are writable. These are the components that would be
     * processed by {@link #forEachWritable(int, WritableComponentProcessor)}. For composite buffers, this is the
     * number of transitive constituent buffers that are writable, while non-composite buffers only have at most one
     * writable component.
     * <p>
     * The number of writable components may be less than the {@link #countComponents() component count}, if not all of
     * them have space for writing.
     *
     * @return The number of writable components in this buffer.
     */
    int countWritableComponents();

    /**
     * Process all readable components of this buffer, and return the number of components processed.
     * <p>
     * The given {@linkplain ReadableComponentProcessor processor} is called for each readable component in this buffer,
     * and passed a component index, for the given component in the iteration, and a {@link ReadableComponent} object
     * for accessing the data within the given component.
     * <p>
     * The component index is specific to the particular invokation of this method. The first call to the consumer will
     * be passed the given initial index, and the next call will be passed the initial index plus one, and so on.
     * <p>
     * The {@linkplain ReadableComponentProcessor component processor} may stop the iteration at any time by returning
     * {@code false}.
     * This will cause the number of components processed to be returned as a negative number (to signal early return),
     * and the number of components processed may then be less than the
     * {@linkplain #countReadableComponents() readable component count}.
     * <p>
     * <strong>Note</strong> that the {@link ReadableComponent} instance passed to the consumer could be reused for
     * multiple calls, so the data must be extracted from the component in the context of the iteration.
     * <p>
     * The {@link ByteBuffer} instances obtained from the component, share life time with that internal component.
     * This means they can be accessed as long as the internal memory store remain unchanged. Methods that may cause
     * such changes, are any method that requires the buffer to be {@linkplain #isOwned() owned}.
     * <p>
     * The best way to ensure this doesn't cause any trouble, is to use the buffers directly as part of the iteration,
     * or immediately after the iteration while we are still in the scope of the method that triggered the iteration.
     * <p>
     * <strong>Note</strong> that the arrays, memory addresses, and byte buffers exposed as components by this method,
     * should not be used for changing the buffer contents. Doing so may cause undefined behaviour.
     * <p>
     * Changes to position and limit of the byte buffers exposed via the processed components, are not reflected back to
     * this buffer instance.
     *
     * @param initialIndex The initial index of the iteration, and the index that will be passed to the first call to
     *                    the {@linkplain ReadableComponentProcessor#process(int, ReadableComponent) processor}.
     * @param processor The processor that will be used to process the buffer components.
     * @return The number of readable components processed, as a positive number of all readable components were
     * processed, or as a negative number if the iteration was stopped because
     * {@link ReadableComponentProcessor#process(int, ReadableComponent)} returned {@code false}.
     * In any case, the number of components processed may be less than {@link #countComponents()}.
     */
    <E extends Exception> int forEachReadable(int initialIndex, ReadableComponentProcessor<E> processor) throws E;

    /**
     * Process all writable components of this buffer, and return the number of components processed.
     * <p>
     * The given {@linkplain WritableComponentProcessor processor} is called for each writable component in this buffer,
     * and passed a component index, for the given component in the iteration, and a {@link WritableComponent} object
     * for accessing the data within the given component.
     * <p>
     * The component index is specific to the particular invokation of this method. The first call to the consumer will
     * be passed the given initial index, and the next call will be passed the initial index plus one, and so on.
     * <p>
     * The {@link WritableComponentProcessor component processor} may stop the iteration at any time by returning
     * {@code false}.
     * This will cause the number of components processed to be returned as a negative number (to signal early return),
     * and the number of components processed may then be less than the
     * {@linkplain #countReadableComponents() readable component count}.
     * <p>
     * <strong>Note</strong> that the {@link WritableComponent} instance passed to the consumer could be reused for
     * multiple calls, so the data must be extracted from the component in the context of the iteration.
     * <p>
     * The {@link ByteBuffer} instances obtained from the component, share life time with that internal component.
     * This means they can be accessed as long as the internal memory store remain unchanged. Methods that may cause
     * such changes, are any method that requires the buffer to be {@linkplain #isOwned() owned}.
     * <p>
     * The best way to ensure this doesn't cause any trouble, is to use the buffers directly as part of the iteration,
     * or immediately after the iteration while we are still in the scope of the method that triggered the iteration.
     * <p>
     * Changes to position and limit of the byte buffers exposed via the processed components, are not reflected back to
     * this buffer instance.
     *
     * @param initialIndex The initial index of the iteration, and the index that will be passed to the first call to
     *                    the {@linkplain WritableComponentProcessor#process(int, WritableComponent) processor}.
     * @param processor The processor that will be used to process the buffer components.
     * @return The number of writable components processed, as a positive number of all writable components were
     * processed, or as a negative number if the iteration was stopped because
     * {@link WritableComponentProcessor#process(int, WritableComponent)} returned {@code false}.
     * In any case, the number of components processed may be less than {@link #countComponents()}.
     */
    <E extends Exception> int forEachWritable(int initialIndex, WritableComponentProcessor<E> processor) throws E;
}
