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

/**
 * A reference counted buffer API with separate reader and writer indexes.
 */
public interface Buf extends Rc<Buf> {
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
    int readableBytes();

    /**
     * Returns the number of writable bytes which is equal to {@code (capacity() - writerIndex())}.
     */
    int writableBytes();

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
     * Give the native memory address backing this buffer, or return 0 if this is a heap backed buffer.
     * @return The native memory address, if any, otherwise 0.
     */
    long getNativeAddress();

    /**
     * Get the byte value at the current {@link #readerIndex()} and increases the index by 1.
     *
     * @return The byte value at the current reader index.
     * @throws IndexOutOfBoundsException If {@link #readableBytes} is less than 1.
     */
    byte readByte();

    /**
     * Get the byte value at the given index. The {@link #readerIndex()} is not modified.
     *
     * @param index The absolute index into this buffer to read from.
     * @return The byte value at the given index.
     * @throws IndexOutOfBoundsException if the given index is out of bounds of the buffer, that is, less than 0 or
     *                                   greater than or equal to {@link #capacity()}.
     */
    byte readByte(int index);

    /**
     * Set the given byte value at the current {@link #writerIndex()} and increases the index by 1.
     *
     * @param value The byte value to write.
     * @return This Buf.
     */
    Buf writeByte(byte value);

    /**
     * Set the given byte value at the given index. The {@link #writerIndex()} is not modified.
     *
     * @param index The byte value to write.
     * @param value The absolute index into this buffer to write to.
     * @return This Buf.
     * @throws IndexOutOfBoundsException if the given index is out of bounds of the buffer, that is, less than 0 or
     *                                   greater than or equal to {@link #capacity()}.
     */
    Buf writeByte(int index, byte value);

    /**
     * Get the long value at the current {@link #readerIndex()} and increases the index by {@link Long#BYTES}.
     *
     * @return The long value at the current reader index.
     * @throws IndexOutOfBoundsException If {@link #readableBytes} is less than {@link Long#BYTES}.
     */
    long readLong();

    /**
     * Get the long value at the given index. The {@link #readerIndex()} is not modified.
     *
     * @param index The absolute index into this buffer to read from.
     * @return The long value at the given index.
     * @throws IndexOutOfBoundsException if the given index is out of bounds of the buffer, that is, less than 0 or
     *                                   greater than or equal to {@link #capacity()}.
     */
    long readLong(int index);

    /**
     * Set the given long value at the current {@link #writerIndex()} and increases the index by {@link Long#BYTES}.
     *
     * @param value The long value to write.
     * @return This Buf.
     */
    Buf writeLong(long value);

    /**
     * Set the given long value at the given index. The {@link #writerIndex()} is not modified.
     *
     * @param index The long value to write.
     * @param value The absolute index into this buffer to write to.
     * @return This Buf.
     * @throws IndexOutOfBoundsException if the given index is out of bounds of the buffer, that is, less than 0 or
     *                                   greater than or equal to {@link #capacity()}.
     */
    Buf writeLong(int index, long value);

    /**
     * Get the int value at the current {@link #readerIndex()} and increases the index by {@link Integer#BYTES}.
     *
     * @return The int value at the current reader index.
     * @throws IndexOutOfBoundsException If {@link #readableBytes} is less than {@link Integer#BYTES}.
     */
    int readInt();

    /**
     * Get the int value at the given index. The {@link #readerIndex()} is not modified.
     *
     * @param index The absolute index into this buffer to read from.
     * @return The int value at the given index.
     * @throws IndexOutOfBoundsException if the given index is out of bounds of the buffer, that is, less than 0 or
     *                                   greater than or equal to {@link #capacity()}.
     */
    int readInt(int index);

    /**
     * Set the given int value at the current {@link #writerIndex()} and increases the index by {@link Integer#BYTES}.
     *
     * @param value The int value to write.
     * @return This Buf.
     */
    Buf writeInt(int value);

    /**
     * Set the given int value at the given index. The {@link #writerIndex()} is not modified.
     *
     * @param index The int value to write.
     * @param value The absolute index into this buffer to write to.
     * @return This Buf.
     * @throws IndexOutOfBoundsException if the given index is out of bounds of the buffer, that is, less than 0 or
     *                                   greater than or equal to {@link #capacity()}.
     */
    Buf writeInt(int index, int value);

    /**
     * Get the short value at the current {@link #readerIndex()} and increases the index by {@link Short#BYTES}.
     *
     * @return The short value at the current reader index.
     * @throws IndexOutOfBoundsException If {@link #readableBytes} is less than {@link Short#BYTES}.
     */
    short readShort();

    /**
     * Get the short value at the given index. The {@link #readerIndex()} is not modified.
     *
     * @param index The absolute index into this buffer to read from.
     * @return The short value at the given index.
     * @throws IndexOutOfBoundsException if the given index is out of bounds of the buffer, that is, less than 0 or
     *                                   greater than or equal to {@link #capacity()}.
     */
    short readShort(int index);

    /**
     * Set the given short value at the current {@link #writerIndex()} and increases the index by {@link Short#BYTES}.
     *
     * @param value The short value to write.
     * @return This Buf.
     */
    Buf writeShort(short value);

    /**
     * Set the given short value at the given index. The {@link #writerIndex()} is not modified.
     *
     * @param index The short value to write.
     * @param value The absolute index into this buffer to write to.
     * @return This Buf.
     * @throws IndexOutOfBoundsException if the given index is out of bounds of the buffer, that is, less than 0 or
     *                                   greater than or equal to {@link #capacity()}.
     */
    Buf writeShort(int index, short value);
}
