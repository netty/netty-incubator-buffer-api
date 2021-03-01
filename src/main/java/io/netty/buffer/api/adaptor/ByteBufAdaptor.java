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

import io.netty.buffer.ByteBufConvertible;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.BufferAllocator;
import io.netty.util.ByteProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.Charset;

public final class ByteBufAdaptor extends ByteBuf {
    private final ByteBufAllocatorAdaptor alloc;
    private final Buffer buffer;

    public ByteBufAdaptor(ByteBufAllocatorAdaptor alloc, Buffer buffer) {
        this.alloc = alloc;
        this.buffer = buffer;
    }

    /**
     * Extracts the underlying {@link Buffer} instance that is backing this {@link ByteBuf}, if any.
     * This is similar to {@link #unwrap()} except the return type is a {@link Buffer}.
     * If this {@link ByteBuf} does not wrap a {@link Buffer}, then {@code null} is returned.
     *
     * @param byteBuf The {@link ByteBuf} to extract the {@link Buffer} from.
     * @return The {@link Buffer} instance that is backing the given {@link ByteBuf}, or {@code null} if the given
     * {@link ByteBuf} is not backed by a {@link Buffer}.
     */
    public static Buffer extract(ByteBuf byteBuf) {
        if (byteBuf instanceof ByteBufAdaptor) {
            ByteBufAdaptor bba = (ByteBufAdaptor) byteBuf;
            return bba.buffer;
        }
        return null;
    }

    @Override
    public int capacity() {
        return buffer.capacity();
    }

    @Override
    public ByteBuf capacity(int newCapacity) {
        int diff = newCapacity - capacity() - buffer.writableBytes();
        if (diff > 0) {
            buffer.ensureWritable(diff);
        }
        return this;
    }

    @Override
    public int maxCapacity() {
        return capacity();
    }

    @Override
    public ByteBufAllocator alloc() {
        return alloc;
    }

    @Override
    public ByteOrder order() {
        return buffer.order();
    }

    @Override
    public ByteBuf order(ByteOrder endianness) {
        buffer.order(endianness);
        return this;
    }

    @Override
    public ByteBuf unwrap() {
        return null;
    }

    @Override
    public boolean isDirect() {
        return buffer.nativeAddress() != 0;
    }

    @Override
    public boolean isReadOnly() {
        return buffer.readOnly();
    }

    @Override
    public ByteBuf asReadOnly() {
        return Unpooled.unreleasableBuffer(this);
    }

    @Override
    public int readerIndex() {
        return buffer.readerOffset();
    }

    @Override
    public ByteBuf readerIndex(int readerIndex) {
        buffer.readerOffset(readerIndex);
        return this;
    }

    @Override
    public int writerIndex() {
        return buffer.writerOffset();
    }

    @Override
    public ByteBuf writerIndex(int writerIndex) {
        buffer.writerOffset(writerIndex);
        return this;
    }

    @Override
    public ByteBuf setIndex(int readerIndex, int writerIndex) {
        return readerIndex(readerIndex).writerIndex(writerIndex);
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
    public int maxWritableBytes() {
        return writableBytes();
    }

    @Override
    public boolean isReadable() {
        return readableBytes() > 0;
    }

    @Override
    public boolean isReadable(int size) {
        return readableBytes() >= size;
    }

    @Override
    public boolean isWritable() {
        return writableBytes() > 0;
    }

    @Override
    public boolean isWritable(int size) {
        return writableBytes() >= size;
    }

    @Override
    public ByteBuf clear() {
        return setIndex(0, 0);
    }

    @Override
    public ByteBuf discardReadBytes() {
        buffer.compact();
        return this;
    }

    @Override
    public ByteBuf discardSomeReadBytes() {
        return discardReadBytes();
    }

    @Override
    public ByteBuf ensureWritable(int minWritableBytes) {
        buffer.ensureWritable(minWritableBytes);
        return this;
    }

    @Override
    public int ensureWritable(int minWritableBytes, boolean force) {
        buffer.ensureWritable(minWritableBytes);
        return minWritableBytes;
    }

    @Override
    public boolean getBoolean(int index) {
        return getByte(index) != 0;
    }

    @Override
    public byte getByte(int index) {
        return buffer.getByte(index);
    }

    @Override
    public short getUnsignedByte(int index) {
        return (short) buffer.getUnsignedByte(index);
    }

    @Override
    public short getShort(int index) {
        return buffer.getShort(index);
    }

    @Override
    public short getShortLE(int index) {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).getShort(index);
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public int getUnsignedShort(int index) {
        return buffer.getUnsignedShort(index);
    }

    @Override
    public int getUnsignedShortLE(int index) {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).getUnsignedShort(index);
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public int getMedium(int index) {
        return buffer.getMedium(index);
    }

    @Override
    public int getMediumLE(int index) {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).getMedium(index);
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public int getUnsignedMedium(int index) {
        return buffer.getUnsignedMedium(index);
    }

    @Override
    public int getUnsignedMediumLE(int index) {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).getUnsignedMedium(index);
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public int getInt(int index) {
        return buffer.getInt(index);
    }

    @Override
    public int getIntLE(int index) {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).getInt(index);
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public long getUnsignedInt(int index) {
        return buffer.getUnsignedInt(index);
    }

    @Override
    public long getUnsignedIntLE(int index) {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).getUnsignedInt(index);
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public long getLong(int index) {
        return buffer.getLong(index);
    }

    @Override
    public long getLongLE(int index) {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).getLong(index);
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public char getChar(int index) {
        return buffer.getChar(index);
    }

    @Override
    public float getFloat(int index) {
        return buffer.getFloat(index);
    }

    @Override
    public double getDouble(int index) {
        return buffer.getDouble(index);
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst) {
        while (dst.isWritable()) {
            dst.writeByte(getByte(index++));
        }
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst, int length) {
        for (int i = 0; i < length; i++) {
            dst.writeByte(getByte(index + i));
        }
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
        for (int i = 0; i < length; i++) {
            dst.setByte(dstIndex + i, getByte(index + i));
        }
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, byte[] dst) {
        return getBytes(index, dst, 0, dst.length);
    }

    @Override
    public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
        for (int i = 0; i < length; i++) {
            dst[dstIndex + i] = getByte(index + i);
        }
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuffer dst) {
        while (dst.hasRemaining()) {
            dst.put(getByte(index));
            index++;
        }
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
        for (int i = 0; i < length; i++) {
            out.write(getByte(index + i));
        }
        return this;
    }

    @Override
    public int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
        ByteBuffer transfer = ByteBuffer.allocate(length);
        buffer.copyInto(index, transfer, 0, length);
        return out.write(transfer);
    }

    @Override
    public int getBytes(int index, FileChannel out, long position, int length) throws IOException {
        ByteBuffer transfer = ByteBuffer.allocate(length);
        buffer.copyInto(index, transfer, 0, length);
        return out.write(transfer, position);
    }

    @Override
    public CharSequence getCharSequence(int index, int length, Charset charset) {
        byte[] bytes = new byte[length];
        getBytes(index, bytes);
        return new String(bytes, charset);
    }

    @Override
    public ByteBuf setBoolean(int index, boolean value) {
        return setByte(index, value? 1 : 0);
    }

    @Override
    public ByteBuf setByte(int index, int value) {
        buffer.setByte(index, (byte) value);
        return this;
    }

    @Override
    public ByteBuf setShort(int index, int value) {
        buffer.setShort(index, (short) value);
        return this;
    }

    @Override
    public ByteBuf setShortLE(int index, int value) {
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN).setShort(index, (short) value);
            return this;
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public ByteBuf setMedium(int index, int value) {
        buffer.setMedium(index, value);
        return this;
    }

    @Override
    public ByteBuf setMediumLE(int index, int value) {
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN).setMedium(index, value);
            return this;
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public ByteBuf setInt(int index, int value) {
        buffer.setInt(index, value);
        return this;
    }

    @Override
    public ByteBuf setIntLE(int index, int value) {
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN).setInt(index, value);
            return this;
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public ByteBuf setLong(int index, long value) {
        buffer.setLong(index, value);
        return this;
    }

    @Override
    public ByteBuf setLongLE(int index, long value) {
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN).setLong(index, value);
            return this;
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public ByteBuf setChar(int index, int value) {
        buffer.setChar(index, (char) value);
        return this;
    }

    @Override
    public ByteBuf setFloat(int index, float value) {
        buffer.setFloat(index, value);
        return this;
    }

    @Override
    public ByteBuf setDouble(int index, double value) {
        buffer.setDouble(index, value);
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src) {
        while (src.isReadable() && index < capacity()) {
            setByte(index++, src.readByte());
        }
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src, int length) {
        for (int i = 0; i < length; i++) {
            setByte(index + i, src.readByte());
        }
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
        for (int i = 0; i < length; i++) {
            setByte(index + i, src.getByte(srcIndex + i));
        }
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, byte[] src) {
        return setBytes(index, src, 0, src.length);
    }

    @Override
    public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
        for (int i = 0; i < length; i++) {
            setByte(index + i, src[srcIndex + i]);
        }
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuffer src) {
        while (src.hasRemaining()) {
            setByte(index, src.get());
            index++;
        }
        return this;
    }

    @Override
    public int setBytes(int index, InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        setBytes(index, bytes, 0, length);
        return bytes.length;
    }

    @Override
    public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        ByteBuffer transfer = ByteBuffer.allocate(length);
        int bytes = in.read(transfer);
        transfer.flip();
        setBytes(index, transfer);
        return bytes;
    }

    @Override
    public int setBytes(int index, FileChannel in, long position, int length) throws IOException {
        ByteBuffer transfer = ByteBuffer.allocate(length);
        int bytes = in.read(transfer, position);
        transfer.flip();
        setBytes(index, transfer);
        return bytes;
    }

    @Override
    public ByteBuf setZero(int index, int length) {
        for (int i = 0; i < length; i++) {
            setByte(index + i, 0);
        }
        return this;
    }

    @Override
    public int setCharSequence(int index, CharSequence sequence, Charset charset) {
        byte[] bytes = sequence.toString().getBytes(charset);
        for (int i = 0; i < bytes.length; i++) {
            setByte(index + i, bytes[i]);
        }
        return bytes.length;
    }

    @Override
    public boolean readBoolean() {
        return readByte() != 0;
    }

    @Override
    public byte readByte() {
        return buffer.readByte();
    }

    @Override
    public short readUnsignedByte() {
        return (short) buffer.readUnsignedByte();
    }

    @Override
    public short readShort() {
        return buffer.readShort();
    }

    @Override
    public short readShortLE() {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).readShort();
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public int readUnsignedShort() {
        return buffer.readUnsignedShort();
    }

    @Override
    public int readUnsignedShortLE() {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).readUnsignedShort();
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public int readMedium() {
        return buffer.readMedium();
    }

    @Override
    public int readMediumLE() {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).readMedium();
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public int readUnsignedMedium() {
        return buffer.readUnsignedMedium();
    }

    @Override
    public int readUnsignedMediumLE() {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).readUnsignedMedium();
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public int readInt() {
        return buffer.readInt();
    }

    @Override
    public int readIntLE() {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).readInt();
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public long readUnsignedInt() {
        return buffer.readUnsignedInt();
    }

    @Override
    public long readUnsignedIntLE() {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).readUnsignedInt();
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public long readLong() {
        return buffer.readLong();
    }

    @Override
    public long readLongLE() {
        ByteOrder originalOrder = buffer.order();
        try {
            return buffer.order(ByteOrder.LITTLE_ENDIAN).readLong();
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public char readChar() {
        return buffer.readChar();
    }

    @Override
    public float readFloat() {
        return buffer.readFloat();
    }

    @Override
    public double readDouble() {
        return buffer.readDouble();
    }

    @Override
    public ByteBuf readBytes(int length) {
        Buffer copy = preferredBufferAllocator().allocate(length);
        buffer.copyInto(0, copy, 0, length);
        return wrap(copy);
    }

    @Override
    public ByteBuf readSlice(int length) {
        return readRetainedSlice(length);
    }

    @Override
    public ByteBuf readRetainedSlice(int length) {
        return slice(readerIndex(), length);
    }

    @Override
    public ByteBuf readBytes(ByteBuf dst) {
        while (dst.isWritable()) {
            dst.writeByte(readByte());
        }
        return this;
    }

    @Override
    public ByteBuf readBytes(ByteBuf dst, int length) {
        for (int i = 0; i < length; i++) {
            dst.writeByte(readByte());
        }
        return this;
    }

    @Override
    public ByteBuf readBytes(ByteBuf dst, int dstIndex, int length) {
        for (int i = 0; i < length; i++) {
            dst.setByte(dstIndex + i, readByte());
        }
        return this;
    }

    @Override
    public ByteBuf readBytes(byte[] dst) {
        return readBytes(dst, 0, dst.length);
    }

    @Override
    public ByteBuf readBytes(byte[] dst, int dstIndex, int length) {
        for (int i = 0; i < length; i++) {
            dst[dstIndex + i] = readByte();
        }
        return this;
    }

    @Override
    public ByteBuf readBytes(ByteBuffer dst) {
        while (dst.hasRemaining()) {
            dst.put(readByte());
        }
        return this;
    }

    @Override
    public ByteBuf readBytes(OutputStream out, int length) throws IOException {
        for (int i = 0; i < length; i++) {
            out.write(readByte());
        }
        return this;
    }

    @Override
    public int readBytes(GatheringByteChannel out, int length) throws IOException {
        ByteBuffer[] components = new ByteBuffer[buffer.countReadableComponents()];
        buffer.forEachReadable(0, (i, component) -> {
            components[i] = component.readableBuffer();
            return true;
        });
        int written = (int) out.write(components);
        skipBytes(written);
        return written;
    }

    @Override
    public CharSequence readCharSequence(int length, Charset charset) {
        byte[] bytes = new byte[length];
        readBytes(bytes);
        return new String(bytes, charset);
    }

    @Override
    public int readBytes(FileChannel out, long position, int length) throws IOException {
        ByteBuffer[] components = new ByteBuffer[buffer.countReadableComponents()];
        buffer.forEachReadable(0, (i, component) -> {
            components[i] = component.readableBuffer();
            return true;
        });
        int written = 0;
        for (ByteBuffer component : components) {
            written += out.write(component, position + written);
            if (component.hasRemaining()) {
                break;
            }
        }
        skipBytes(written);
        return written;
    }

    @Override
    public ByteBuf skipBytes(int length) {
        buffer.readerOffset(length + buffer.readerOffset());
        return this;
    }

    @Override
    public ByteBuf writeBoolean(boolean value) {
        return writeByte(value? 1 : 0);
    }

    @Override
    public ByteBuf writeByte(int value) {
        ensureWritable(1);
        buffer.writeByte((byte) value);
        return this;
    }

    @Override
    public ByteBuf writeShort(int value) {
        ensureWritable(2);
        buffer.writeShort((short) value);
        return this;
    }

    @Override
    public ByteBuf writeShortLE(int value) {
        ensureWritable(2);
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN).writeShort((short) value);
            return this;
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public ByteBuf writeMedium(int value) {
        ensureWritable(3);
        buffer.writeMedium(value);
        return this;
    }

    @Override
    public ByteBuf writeMediumLE(int value) {
        ensureWritable(3);
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN).writeMedium(value);
            return this;
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public ByteBuf writeInt(int value) {
        ensureWritable(4);
        buffer.writeInt(value);
        return this;
    }

    @Override
    public ByteBuf writeIntLE(int value) {
        ensureWritable(4);
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN).writeInt(value);
            return this;
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public ByteBuf writeLong(long value) {
        ensureWritable(8);
        buffer.writeLong(value);
        return this;
    }

    @Override
    public ByteBuf writeLongLE(long value) {
        ensureWritable(8);
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN).writeLong(value);
            return this;
        } finally {
            buffer.order(originalOrder);
        }
    }

    @Override
    public ByteBuf writeChar(int value) {
        ensureWritable(2);
        buffer.writeChar((char) value);
        return this;
    }

    @Override
    public ByteBuf writeFloat(float value) {
        ensureWritable(4);
        buffer.writeFloat(value);
        return this;
    }

    @Override
    public ByteBuf writeDouble(double value) {
        ensureWritable(8);
        buffer.writeDouble(value);
        return this;
    }

    @Override
    public ByteBuf writeBytes(ByteBuf src) {
        return writeBytes(src, src.readableBytes());
    }

    @Override
    public ByteBuf writeBytes(ByteBuf src, int length) {
        ensureWritable(length);
        for (int i = 0; i < length; i++) {
            writeByte(src.readByte());
        }
        return this;
    }

    @Override
    public ByteBuf writeBytes(ByteBuf src, int srcIndex, int length) {
        ensureWritable(length);
        for (int i = 0; i < length; i++) {
            writeByte(src.getByte(srcIndex + i));
        }
        return this;
    }

    @Override
    public ByteBuf writeBytes(byte[] src) {
        ensureWritable(src.length);
        for (byte b : src) {
            writeByte(b);
        }
        return this;
    }

    @Override
    public ByteBuf writeBytes(byte[] src, int srcIndex, int length) {
        ensureWritable(length);
        for (int i = 0; i < length; i++) {
            writeByte(src[srcIndex + i]);
        }
        return this;
    }

    @Override
    public ByteBuf writeBytes(ByteBuffer src) {
        ensureWritable(src.remaining());
        while (src.hasRemaining()) {
            writeByte(src.get());
        }
        return this;
    }

    @Override
    public int writeBytes(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        writeBytes(bytes);
        return bytes.length;
    }

    @Override
    public int writeBytes(ScatteringByteChannel in, int length) throws IOException {
        ensureWritable(length);
        ByteBuffer[] components = new ByteBuffer[buffer.countWritableComponents()];
        buffer.forEachWritable(0, (i, component) -> {
            components[i] = component.writableBuffer();
            return true;
        });
        int read = (int) in.read(components);
        if (read > 0) {
            writerIndex(read + writerIndex());
        }
        return read;
    }

    @Override
    public int writeBytes(FileChannel in, long position, int length) throws IOException {
        ensureWritable(length);
        ByteBuffer[] components = new ByteBuffer[buffer.countWritableComponents()];
        buffer.forEachWritable(0, (i, component) -> {
            components[i] = component.writableBuffer();
            return true;
        });
        int read = 0;
        for (ByteBuffer component : components) {
            int r = in.read(component, position + read);
            if (r > 0) {
                read += r;
            }
            if (component.hasRemaining()) {
                break;
            }
        }
        writerIndex(read + writerIndex());
        return read;
    }

    @Override
    public ByteBuf writeZero(int length) {
        ensureWritable(length);
        for (int i = 0; i < length; i++) {
            writeByte(0);
        }
        return this;
    }

    @Override
    public int writeCharSequence(CharSequence sequence, Charset charset) {
        byte[] bytes = sequence.toString().getBytes(charset);
        writeBytes(bytes);
        return bytes.length;
    }

    @Override
    public int indexOf(int fromIndex, int toIndex, byte value) {
        for (int i = fromIndex; i < toIndex; i++) {
            if (getByte(i) == value) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int bytesBefore(byte value) {
        return indexOf(readerIndex(), writerIndex(), value);
    }

    @Override
    public int bytesBefore(int length, byte value) {
        return indexOf(readerIndex(), readerIndex() + length, value);
    }

    @Override
    public int bytesBefore(int index, int length, byte value) {
        return indexOf(index, index + length, value);
    }

    @Override
    public int forEachByte(ByteProcessor processor) {
        return buffer.openCursor().process(processor);
    }

    @Override
    public int forEachByte(int index, int length, ByteProcessor processor) {
        return buffer.openCursor(index, length).process(processor);
    }

    @Override
    public int forEachByteDesc(ByteProcessor processor) {
        return buffer.openReverseCursor().process(processor);
    }

    @Override
    public int forEachByteDesc(int index, int length, ByteProcessor processor) {
        return buffer.openReverseCursor(index, length).process(processor);
    }

    @Override
    public ByteBuf copy() {
        return copy(0, capacity());
    }

    @Override
    public ByteBuf copy(int index, int length) {
        BufferAllocator allocator = preferredBufferAllocator();
        Buffer copy = allocator.allocate(length);
        buffer.copyInto(index, copy, 0, length);
        copy.order(buffer.order());
        return wrap(copy).setIndex(readerIndex(), writerIndex());
    }

    @Override
    public ByteBuf slice() {
        return retainedSlice();
    }

    @Override
    public ByteBuf retainedSlice() {
        return wrap(buffer.slice());
    }

    @Override
    public ByteBuf slice(int index, int length) {
        return retainedSlice(index, length);
    }

    @Override
    public ByteBuf retainedSlice(int index, int length) {
        return wrap(buffer.slice(index, length));
    }

    @Override
    public ByteBuf duplicate() {
        return retainedDuplicate();
    }

    @Override
    public ByteBuf retainedDuplicate() {
        return retainedSlice(0, capacity());
    }

    @Override
    public int nioBufferCount() {
        return -1;
    }

    @Override
    public ByteBuffer nioBuffer() {
        throw new UnsupportedOperationException("Cannot create shared NIO buffer.");
    }

    @Override
    public ByteBuffer nioBuffer(int index, int length) {
        return nioBuffer();
    }

    @Override
    public ByteBuffer internalNioBuffer(int index, int length) {
        return nioBuffer();
    }

    @Override
    public ByteBuffer[] nioBuffers() {
        throw new UnsupportedOperationException("Cannot create shared NIO buffers.");
    }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) {
        return nioBuffers();
    }

    @Override
    public boolean hasArray() {
        return false;
    }

    @Override
    public byte[] array() {
        throw new UnsupportedOperationException("This buffer has no array.");
    }

    @Override
    public int arrayOffset() {
        throw new UnsupportedOperationException("This buffer has no array.");
    }

    @Override
    public boolean hasMemoryAddress() {
        return buffer.nativeAddress() != 0;
    }

    @Override
    public long memoryAddress() {
        if (!hasMemoryAddress()) {
            throw new UnsupportedOperationException("No memory address associated with this buffer.");
        }
        return buffer.nativeAddress();
    }

    @Override
    public String toString(Charset charset) {
        return toString(readerIndex(), readableBytes(), charset);
    }

    @Override
    public String toString(int index, int length, Charset charset) {
        byte[] bytes = new byte[length];
        getBytes(index, bytes);
        return new String(bytes, charset);
    }

    @Override
    public int hashCode() {
        int hash = 4242;
        int capacity = capacity();
        for (int i = 0; i < capacity; i++) {
            hash = 31 * hash + getByte(i);
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ByteBufConvertible) {
            ByteBuf other = ((ByteBufConvertible) obj).asByteBuf();
            boolean equal = true;
            int capacity = capacity();
            if (other.capacity() != capacity) {
                return false;
            }
            for (int i = 0; i < capacity; i++) {
                equal &= getByte(i) == other.getByte(i);
            }
            return equal;
        }
        return false;
    }

    @Override
    public int compareTo(ByteBuf buffer) {
        var cap = Math.min(capacity(), buffer.capacity());
        for (int i = 0; i < cap; i++) {
            int cmp = Byte.compare(getByte(i), buffer.getByte(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(capacity(), buffer.capacity());
    }

    @Override
    public String toString() {
        return "ByteBuf(" + readerIndex() + ", " + writerIndex() + ", " + capacity() + ')';
    }

    @Override
    public ByteBuf retain(int increment) {
        for (int i = 0; i < increment; i++) {
            buffer.acquire();
        }
        return this;
    }

    @Override
    public int refCnt() {
        return buffer.isAccessible()? 1 + buffer.countBorrows() : 0;
    }

    @Override
    public ByteBuf retain() {
        return retain(1);
    }

    @Override
    public ByteBuf touch() {
        return this;
    }

    @Override
    public ByteBuf touch(Object hint) {
        return this;
    }

    @Override
    public boolean release() {
        return release(1);
    }

    @Override
    public boolean release(int decrement) {
        for (int i = 0; i < decrement; i++) {
            buffer.close();
        }
        return !buffer.isAccessible();
    }

    private ByteBufAdaptor wrap(Buffer copy) {
        return new ByteBufAdaptor(alloc, copy);
    }

    private BufferAllocator preferredBufferAllocator() {
        return isDirect()? alloc.getOffHeap() : alloc.getOnHeap();
    }
}
