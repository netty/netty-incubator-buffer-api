/*
 * Copyright 2021 The Netty Project
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
package io.netty.buffer.api.adaptor;

import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.BufferHolder;
import io.netty.buffer.api.ByteCursor;
import io.netty.buffer.api.ReadableComponentProcessor;
import io.netty.buffer.api.Send;
import io.netty.buffer.api.WritableComponentProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link Buffer} implementation that delegates all method calls to a given delegate buffer instance.
 */
public abstract class BufferAdaptor implements Buffer {
    protected Buffer buffer;

    protected BufferAdaptor(Buffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "Delegate buffer cannot be null.");
    }

    @Override
    public Buffer order(ByteOrder order) {
        buffer.order(order);
        return this;
    }

    @Override
    public ByteOrder order() {
        return buffer.order();
    }

    @Override
    public int capacity() {
        return buffer.capacity();
    }

    @Override
    public int readerOffset() {
        return buffer.readerOffset();
    }

    @Override
    public Buffer readerOffset(int offset) {
        buffer.readerOffset(offset);
        return this;
    }

    @Override
    public int writerOffset() {
        return buffer.writerOffset();
    }

    @Override
    public Buffer writerOffset(int offset) {
        buffer.writerOffset(offset);
        return this;
    }

    @Override
    public int readableBytes() {
        return buffer.readableBytes();
    }

    @Override
    public int writableBytes() {
        return buffer.writableBytes();
    }

    @Override
    public Buffer fill(byte value) {
        buffer.fill(value);
        return this;
    }

    @Override
    public long nativeAddress() {
        return buffer.nativeAddress();
    }

    @Override
    public Buffer readOnly(boolean readOnly) {
        buffer.readOnly(readOnly);
        return this;
    }

    @Override
    public boolean readOnly() {
        return buffer.readOnly();
    }

    @Override
    public void copyInto(int srcPos, byte[] dest, int destPos, int length) {
        buffer.copyInto(srcPos, dest, destPos, length);
    }

    @Override
    public void copyInto(int srcPos, ByteBuffer dest, int destPos, int length) {
        buffer.copyInto(srcPos, dest, destPos, length);
    }

    @Override
    public void copyInto(int srcPos, Buffer dest, int destPos, int length) {
        buffer.copyInto(srcPos, dest, destPos, length);
    }

    @Override
    public Buffer reset() {
        buffer.reset();
        return this;
    }

    @Override
    public ByteCursor openCursor() {
        return buffer.openCursor();
    }

    @Override
    public ByteCursor openCursor(int fromOffset, int length) {
        return buffer.openCursor(fromOffset, length);
    }

    @Override
    public ByteCursor openReverseCursor() {
        return buffer.openReverseCursor();
    }

    @Override
    public ByteCursor openReverseCursor(int fromOffset, int length) {
        return buffer.openReverseCursor(fromOffset, length);
    }

    @Override
    public void ensureWritable(int size) {
        buffer.ensureWritable(size);
    }

    @Override
    public void ensureWritable(int size, int minimumGrowth, boolean allowCompaction) {
        buffer.ensureWritable(size, minimumGrowth, allowCompaction);
    }

    @Override
    public Buffer slice() {
        buffer.slice();
        return this;
    }

    @Override
    public Buffer slice(int offset, int length) {
        buffer.slice(offset, length);
        return this;
    }

    @Override
    public Buffer bifurcate() {
        buffer.bifurcate();
        return this;
    }

    @Override
    public Buffer bifurcate(int splitOffset) {
        buffer.bifurcate(splitOffset);
        return this;
    }

    @Override
    public void compact() {
        buffer.compact();
    }

    @Override
    public int countComponents() {
        return buffer.countComponents();
    }

    @Override
    public int countReadableComponents() {
        return buffer.countReadableComponents();
    }

    @Override
    public int countWritableComponents() {
        return buffer.countWritableComponents();
    }

    @Override
    public <E extends Exception> int forEachReadable(int initialIndex, ReadableComponentProcessor<E> processor)
            throws E {
        return buffer.forEachReadable(initialIndex, processor);
    }

    @Override
    public <E extends Exception> int forEachWritable(int initialIndex, WritableComponentProcessor<E> processor)
            throws E {
        return buffer.forEachWritable(initialIndex, processor);
    }

    @Override
    public byte readByte() {
        return buffer.readByte();
    }

    @Override
    public byte getByte(int roff) {
        return buffer.getByte(roff);
    }

    @Override
    public int readUnsignedByte() {
        return buffer.readUnsignedByte();
    }

    @Override
    public int getUnsignedByte(int roff) {
        return buffer.getUnsignedByte(roff);
    }

    @Override
    public Buffer writeByte(byte value) {
        buffer.writeByte(value);
        return this;
    }

    @Override
    public Buffer setByte(int woff, byte value) {
        buffer.setByte(woff, value);
        return this;
    }

    @Override
    public Buffer writeUnsignedByte(int value) {
        buffer.writeUnsignedByte(value);
        return this;
    }

    @Override
    public Buffer setUnsignedByte(int woff, int value) {
        buffer.setUnsignedByte(woff, value);
        return this;
    }

    @Override
    public char readChar() {
        return buffer.readChar();
    }

    @Override
    public char getChar(int roff) {
        return buffer.getChar(roff);
    }

    @Override
    public Buffer writeChar(char value) {
        buffer.writeChar(value);
        return this;
    }

    @Override
    public Buffer setChar(int woff, char value) {
        buffer.setChar(woff, value);
        return this;
    }

    @Override
    public short readShort() {
        return buffer.readShort();
    }

    @Override
    public short getShort(int roff) {
        return buffer.getShort(roff);
    }

    @Override
    public int readUnsignedShort() {
        return buffer.readUnsignedShort();
    }

    @Override
    public int getUnsignedShort(int roff) {
        return buffer.getUnsignedShort(roff);
    }

    @Override
    public Buffer writeShort(short value) {
        buffer.writeShort(value);
        return this;
    }

    @Override
    public Buffer setShort(int woff, short value) {
        buffer.setShort(woff, value);
        return this;
    }

    @Override
    public Buffer writeUnsignedShort(int value) {
        buffer.writeUnsignedShort(value);
        return this;
    }

    @Override
    public Buffer setUnsignedShort(int woff, int value) {
        buffer.setUnsignedShort(woff, value);
        return this;
    }

    @Override
    public int readMedium() {
        return buffer.readMedium();
    }

    @Override
    public int getMedium(int roff) {
        return buffer.getMedium(roff);
    }

    @Override
    public int readUnsignedMedium() {
        return buffer.readUnsignedMedium();
    }

    @Override
    public int getUnsignedMedium(int roff) {
        return buffer.getUnsignedMedium(roff);
    }

    @Override
    public Buffer writeMedium(int value) {
        buffer.writeMedium(value);
        return this;
    }

    @Override
    public Buffer setMedium(int woff, int value) {
        buffer.setMedium(woff, value);
        return this;
    }

    @Override
    public Buffer writeUnsignedMedium(int value) {
        buffer.writeUnsignedMedium(value);
        return this;
    }

    @Override
    public Buffer setUnsignedMedium(int woff, int value) {
        buffer.setUnsignedMedium(woff, value);
        return this;
    }

    @Override
    public int readInt() {
        return buffer.readInt();
    }

    @Override
    public int getInt(int roff) {
        return buffer.getInt(roff);
    }

    @Override
    public long readUnsignedInt() {
        return buffer.readUnsignedInt();
    }

    @Override
    public long getUnsignedInt(int roff) {
        return buffer.getUnsignedInt(roff);
    }

    @Override
    public Buffer writeInt(int value) {
        buffer.writeInt(value);
        return this;
    }

    @Override
    public Buffer setInt(int woff, int value) {
        buffer.setInt(woff, value);
        return this;
    }

    @Override
    public Buffer writeUnsignedInt(long value) {
        buffer.writeUnsignedInt(value);
        return this;
    }

    @Override
    public Buffer setUnsignedInt(int woff, long value) {
        buffer.setUnsignedInt(woff, value);
        return this;
    }

    @Override
    public float readFloat() {
        return buffer.readFloat();
    }

    @Override
    public float getFloat(int roff) {
        return buffer.getFloat(roff);
    }

    @Override
    public Buffer writeFloat(float value) {
        buffer.writeFloat(value);
        return this;
    }

    @Override
    public Buffer setFloat(int woff, float value) {
        buffer.setFloat(woff, value);
        return this;
    }

    @Override
    public long readLong() {
        return buffer.readLong();
    }

    @Override
    public long getLong(int roff) {
        return buffer.getLong(roff);
    }

    @Override
    public Buffer writeLong(long value) {
        buffer.writeLong(value);
        return this;
    }

    @Override
    public Buffer setLong(int woff, long value) {
        buffer.setLong(woff, value);
        return this;
    }

    @Override
    public double readDouble() {
        return buffer.readDouble();
    }

    @Override
    public double getDouble(int roff) {
        return buffer.getDouble(roff);
    }

    @Override
    public Buffer writeDouble(double value) {
        buffer.writeDouble(value);
        return this;
    }

    @Override
    public Buffer setDouble(int woff, double value) {
        buffer.setDouble(woff, value);
        return this;
    }

    @Override
    public Buffer acquire() {
        buffer.acquire();
        return this;
    }

    @Override
    public Buffer get() {
        buffer.get();
        return this;
    }

    @Override
    public boolean isInstanceOf(Class<?> cls) {
        return buffer.isInstanceOf(cls);
    }

    @Override
    public void close() {
        buffer.close();
    }

    @SuppressWarnings("unchecked")
    @Override
    public Send<Buffer> send() {
        Class<Buffer> aClass = (Class<Buffer>) (Class<?>) getClass();
        Function<Buffer, Buffer> receive = this::receive;
        return buffer.send().map(aClass, receive);
    }

    /**
     * Called when a {@linkplain #send() sent} {@link BufferAdaptor} is received by the recipient.
     * The {@link BufferAdaptor} should return a new concrete instance, that wraps the given {@link Buffer} object.
     *
     * @param buf The {@link Buffer} that is {@linkplain Send#receive() received} by the recipient,
     *           and needs to be wrapped in a new {@link BufferHolder} instance.
     * @return A new buffer adaptor instance, containing the given {@linkplain Buffer buffer}.
     */
    protected abstract BufferAdaptor receive(Buffer buf);

    @Override
    public boolean isOwned() {
        return buffer.isOwned();
    }

    @Override
    public int countBorrows() {
        return buffer.countBorrows();
    }

    @Override
    public boolean isAccessible() {
        return buffer.isAccessible();
    }
}
