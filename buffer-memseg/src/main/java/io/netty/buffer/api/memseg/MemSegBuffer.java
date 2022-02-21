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
package io.netty.buffer.api.memseg;

import io.netty.buffer.api.AllocatorControl;
import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.BufferAllocator;
import io.netty.buffer.api.BufferReadOnlyException;
import io.netty.buffer.api.ByteCursor;
import io.netty.buffer.api.Drop;
import io.netty.buffer.api.Owned;
import io.netty.buffer.api.ReadableComponent;
import io.netty.buffer.api.ReadableComponentProcessor;
import io.netty.buffer.api.WritableComponent;
import io.netty.buffer.api.WritableComponentProcessor;
import io.netty.buffer.api.internal.AdaptableBuffer;
import io.netty.buffer.api.internal.Statics;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;
import jdk.incubator.foreign.ValueLayout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import static io.netty.buffer.api.internal.Statics.bufferIsClosed;
import static io.netty.buffer.api.internal.Statics.bufferIsReadOnly;

class MemSegBuffer extends AdaptableBuffer<MemSegBuffer> implements ReadableComponent, WritableComponent {
    private static final ValueLayout.OfByte JAVA_BYTE =
            ValueLayout.JAVA_BYTE.withOrder(ByteOrder.BIG_ENDIAN).withBitAlignment(Byte.SIZE);
    private static final ValueLayout.OfChar JAVA_CHAR =
            ValueLayout.JAVA_CHAR.withOrder(ByteOrder.BIG_ENDIAN).withBitAlignment(Byte.SIZE);
    private static final ValueLayout.OfShort JAVA_SHORT =
            ValueLayout.JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN).withBitAlignment(Byte.SIZE);
    private static final ValueLayout.OfInt JAVA_INT =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN).withBitAlignment(Byte.SIZE);
    private static final ValueLayout.OfFloat JAVA_FLOAT =
            ValueLayout.JAVA_FLOAT.withOrder(ByteOrder.BIG_ENDIAN).withBitAlignment(Byte.SIZE);
    private static final ValueLayout.OfLong JAVA_LONG =
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN).withBitAlignment(Byte.SIZE);
    private static final ValueLayout.OfDouble JAVA_DOUBLE =
            ValueLayout.JAVA_DOUBLE.withOrder(ByteOrder.BIG_ENDIAN).withBitAlignment(Byte.SIZE);

    private static final MemorySegment CLOSED_SEGMENT;
    private static final MemorySegment ZERO_OFFHEAP_SEGMENT;
    private static final MemorySegment ZERO_ONHEAP_SEGMENT;
    static final Drop<MemSegBuffer> SEGMENT_CLOSE;

    static {
        try (ResourceScope scope = ResourceScope.newSharedScope()) {
            // We are not allowed to allocate a zero-sized native buffer, but we *can* take a zero-sized slice from it.
            // We need the CLOSED_SEGMENT to have a size of zero, because we'll use its size for bounds checks after
            // the buffer is closed.
            MemorySegment segment = MemorySegment.allocateNative(1, scope);
            CLOSED_SEGMENT = segment.asSlice(0, 0);
        }
        ZERO_OFFHEAP_SEGMENT = MemorySegment.allocateNative(1, ResourceScope.globalScope()).asSlice(0, 0);
        ZERO_ONHEAP_SEGMENT = MemorySegment.ofArray(new byte[0]);
        SEGMENT_CLOSE = new Drop<MemSegBuffer>() {
            @Override
            public void drop(MemSegBuffer buf) {
                ResourceScope scope = buf.base.scope();
                if (scope != ResourceScope.globalScope()) {
                    scope.close();
                }
            }

            @Override
            public Drop<MemSegBuffer> fork() {
                return this;
            }

            @Override
            public void attach(MemSegBuffer obj) {
            }

            @Override
            public String toString() {
                return "SEGMENT_CLOSE";
            }
        };
    }

    private final AllocatorControl control;
    private MemorySegment base;
    private MemorySegment seg;
    private MemorySegment wseg;
    private int roff;
    private int woff;

    MemSegBuffer(MemorySegment base, MemorySegment view, Drop<MemSegBuffer> drop, AllocatorControl control) {
        super(drop, control);
        this.control = control;
        this.base = base;
        seg = view;
        wseg = view;
    }

    /**
     * Constructor for {@linkplain BufferAllocator#constBufferSupplier(byte[]) const buffers}.
     */
    MemSegBuffer(MemSegBuffer parent, Drop<MemSegBuffer> drop) {
        super(drop, parent.control);
        control = parent.control;
        base = parent.base;
        seg = parent.seg;
        wseg = parent.wseg;
        roff = parent.roff;
        woff = parent.woff;
    }

    @Override
    public String toString() {
        return "Buffer[roff:" + roff + ", woff:" + woff + ", cap:" + seg.byteSize() + ']';
    }

    @Override
    protected RuntimeException createResourceClosedException() {
        return bufferIsClosed(this);
    }

    @Override
    public int capacity() {
        return (int) seg.byteSize();
    }

    @Override
    public int readerOffset() {
        return roff;
    }

    @Override
    public void skipReadable(int delta) {
        readerOffset(readerOffset() + delta);
    }

    @Override
    public MemSegBuffer readerOffset(int offset) {
        checkRead(offset, 0);
        roff = offset;
        return this;
    }

    @Override
    public int writerOffset() {
        return woff;
    }

    @Override
    public void skipWritable(int delta) {
        writerOffset(writerOffset() + delta);
    }

    @Override
    public MemSegBuffer writerOffset(int offset) {
        checkWrite(offset, 0);
        woff = offset;
        return this;
    }

    @Override
    public int readableBytes() {
        return writerOffset() - readerOffset();
    }

    @Override
    public int writableBytes() {
        return capacity() - writerOffset();
    }

    @Override
    public Buffer fill(byte value) {
        if (!isAccessible()) {
            throw bufferIsClosed(this);
        }
        checkSet(0, capacity());
        seg.fill(value);
        return this;
    }

    // <editor-fold defaultstate="collapsed" desc="Readable/WritableComponent implementation.">
    @Override
    public boolean hasReadableArray() {
        return false;
    }

    @Override
    public byte[] readableArray() {
        throw new UnsupportedOperationException("This component has no backing array.");
    }

    @Override
    public int readableArrayOffset() {
        throw new UnsupportedOperationException("This component has no backing array.");
    }

    @Override
    public int readableArrayLength() {
        throw new UnsupportedOperationException("This component has no backing array.");
    }

    @Override
    public long readableNativeAddress() {
        return nativeAddress(roff);
    }

    @Override
    public ByteBuffer readableBuffer() {
        var buffer = seg.asByteBuffer();
        buffer = buffer.asReadOnlyBuffer();
        buffer = buffer.position(readerOffset()).limit(readerOffset() + readableBytes());
        return buffer;
    }

    @Override
    public boolean hasWritableArray() {
        return false;
    }

    @Override
    public byte[] writableArray() {
        throw new UnsupportedOperationException("This component has no backing array.");
    }

    @Override
    public int writableArrayOffset() {
        throw new UnsupportedOperationException("This component has no backing array.");
    }

    @Override
    public int writableArrayLength() {
        throw new UnsupportedOperationException("This component has no backing array.");
    }

    @Override
    public long writableNativeAddress() {
        return nativeAddress(woff);
    }

    @Override
    public ByteBuffer writableBuffer() {
        var buffer = wseg.asByteBuffer();
        buffer = buffer.position(writerOffset()).limit(writerOffset() + writableBytes());
        return buffer;
    }
    // </editor-fold>

    private long nativeAddress(int offset) {
        if (!isAccessible()) {
            throw bufferIsClosed(this);
        }
        if (seg.isNative()) {
            return seg.address().toRawLongValue() + offset;
        }
        return 0; // This is a heap segment.
    }

    @Override
    public Buffer makeReadOnly() {
        wseg = CLOSED_SEGMENT;
        return this;
    }

    @Override
    public boolean readOnly() {
        return wseg == CLOSED_SEGMENT && seg != CLOSED_SEGMENT;
    }

    @Override
    public boolean isDirect() {
        return seg.isNative();
    }

    @Override
    public Buffer copy(int offset, int length) {
        checkGet(offset, length);
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative: " + length + '.');
        }

        if (length == 0) {
            // Special case zero-length segments, since allocators don't support allocating empty buffers.
            final MemorySegment zero;
            if (!seg.isNative()) {
                zero = ZERO_ONHEAP_SEGMENT;
            } else {
                zero = ZERO_OFFHEAP_SEGMENT;
            }
            return new MemSegBuffer(zero, zero, SEGMENT_CLOSE, control);
        }

        AllocatorControl.UntetheredMemory memory = control.allocateUntethered(this, length);
        MemorySegment segment = memory.memory();
        Buffer copy = new MemSegBuffer(segment, segment, memory.drop(), control);
        copyInto(offset, copy, 0, length);
        copy.writerOffset(length);
        if (readOnly()) {
            copy = copy.makeReadOnly();
        }
        return copy;
    }

    @Override
    public void copyInto(int srcPos, byte[] dest, int destPos, int length) {
        copyInto(srcPos, MemorySegment.ofArray(dest), destPos, length);
    }

    @Override
    public void copyInto(int srcPos, ByteBuffer dest, int destPos, int length) {
        if (dest.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
        copyInto(srcPos, MemorySegment.ofByteBuffer(dest.duplicate().clear()), destPos, length);
    }

    private void copyInto(int srcPos, MemorySegment dest, int destPos, int length) {
        if (seg == CLOSED_SEGMENT) {
            throw bufferIsClosed(this);
        }
        if (srcPos < 0) {
            throw new IllegalArgumentException("The srcPos cannot be negative: " + srcPos + '.');
        }
        if (length < 0) {
            throw new IllegalArgumentException("The length cannot be negative: " + length + '.');
        }
        if (seg.byteSize() < srcPos + length) {
            throw new IllegalArgumentException("The srcPos + length is beyond the end of the buffer: " +
                                               "srcPos = " + srcPos + ", length = " + length + '.');
        }
        dest.asSlice(destPos, length).copyFrom(seg.asSlice(srcPos, length));
    }

    @Override
    public void copyInto(int srcPos, Buffer dest, int destPos, int length) {
        if (dest instanceof MemSegBuffer memSegBuf) {
            memSegBuf.checkSet(destPos, length);
            copyInto(srcPos, memSegBuf.seg, destPos, length);
            return;
        }

        Statics.copyToViaReverseLoop(this, srcPos, dest, destPos, length);
    }

    @Override
    public int transferTo(WritableByteChannel channel, int length) throws IOException {
        if (!isAccessible()) {
            throw bufferIsClosed(this);
        }
        length = Math.min(readableArrayLength(), length);
        if (length == 0) {
            return 0;
        }
        checkGet(readerOffset(), length);
        int bytesWritten = channel.write(readableBuffer().limit(length));
        skipReadable(bytesWritten);
        return bytesWritten;
    }

    @Override
    public int transferFrom(ReadableByteChannel channel, int length) throws IOException {
        if (!isAccessible()) {
            throw bufferIsClosed(this);
        }
        if (readOnly()) {
            throw bufferIsReadOnly(this);
        }
        length = Math.min(writableBytes(), length);
        if (length == 0) {
            return 0;
        }
        checkSet(writerOffset(), length);
        int bytesRead = channel.read(writableBuffer().limit(length));
        if (bytesRead != -1) {
            skipWritable(bytesRead);
        }
        return bytesRead;
    }

    @Override
    public int bytesBefore(byte needle) {
        // For the details of this algorithm, see Hacker's Delight, Chapter 6, Searching Words.
        // Richard Startin also describes this on his blog: https://richardstartin.github.io/posts/finding-bytes
        if (!isAccessible()) {
            throw bufferIsClosed(this);
        }
        int offset = roff;
        final int length = woff - roff;
        final int end = woff;

        if (length > 7) {
            final long pattern = (needle & 0xFFL) * 0x101010101010101L;
            for (final int longEnd = offset + (length >>> 3) * Long.BYTES;
                 offset < longEnd;
                 offset += Long.BYTES) {
                final long word = getLongAtOffset(seg, offset);

                long input = word ^ pattern;
                long tmp = (input & 0x7F7F7F7F7F7F7F7FL) + 0x7F7F7F7F7F7F7F7FL;
                tmp = ~(tmp | input | 0x7F7F7F7F7F7F7F7FL);
                final int binaryPosition = Long.numberOfLeadingZeros(tmp);

                int index = binaryPosition >>> 3;
                if (index < Long.BYTES) {
                    return offset + index - roff;
                }
            }
        }
        for (; offset < end; offset++) {
            if (getByteAtOffset(seg, offset) == needle) {
                return offset - roff;
            }
        }

        return -1;
    }

    @Override
    public ByteCursor openCursor() {
        return openCursor(readerOffset(), readableBytes());
    }

    @Override
    public ByteCursor openCursor(int fromOffset, int length) {
        if (seg == CLOSED_SEGMENT) {
            throw bufferIsClosed(this);
        }
        if (fromOffset < 0) {
            throw new IllegalArgumentException("The fromOffset cannot be negative: " + fromOffset + '.');
        }
        if (length < 0) {
            throw new IllegalArgumentException("The length cannot be negative: " + length + '.');
        }
        if (seg.byteSize() < fromOffset + length) {
            throw new IllegalArgumentException("The fromOffset + length is beyond the end of the buffer: " +
                                               "fromOffset = " + fromOffset + ", length = " + length + '.');
        }
        return new ByteCursor() {
            final MemorySegment segment = seg;
            int index = fromOffset;
            final int end = index + length;
            byte byteValue = -1;

            @Override
            public boolean readByte() {
                if (index < end) {
                    byteValue = segment.get(JAVA_BYTE, index);
                    index++;
                    return true;
                }
                return false;
            }

            @Override
            public byte getByte() {
                return byteValue;
            }

            @Override
            public int currentOffset() {
                return index;
            }

            @Override
            public int bytesLeft() {
                return end - index;
            }
        };
    }

    @Override
    public ByteCursor openReverseCursor() {
        int woff = writerOffset();
        return openReverseCursor(woff == 0? 0 : woff - 1, readableBytes());
    }

    @Override
    public ByteCursor openReverseCursor(int fromOffset, int length) {
        if (seg == CLOSED_SEGMENT) {
            throw bufferIsClosed(this);
        }
        if (fromOffset < 0) {
            throw new IllegalArgumentException("The fromOffset cannot be negative: " + fromOffset + '.');
        }
        if (length < 0) {
            throw new IllegalArgumentException("The length cannot be negative: " + length + '.');
        }
        if (seg.byteSize() <= fromOffset) {
            throw new IllegalArgumentException("The fromOffset is beyond the end of the buffer: " + fromOffset + '.');
        }
        if (fromOffset - length < -1) {
            throw new IllegalArgumentException("The fromOffset - length would underflow the buffer: " +
                                               "fromOffset = " + fromOffset + ", length = " + length + '.');
        }
        return new ByteCursor() {
            final MemorySegment segment = seg;
            int index = fromOffset;
            final int end = index - length;
            byte byteValue = -1;

            @Override
            public boolean readByte() {
                if (index > end) {
                    byteValue = segment.get(JAVA_BYTE, index);
                    index--;
                    return true;
                }
                return false;
            }

            @Override
            public byte getByte() {
                return byteValue;
            }

            @Override
            public int currentOffset() {
                return index;
            }

            @Override
            public int bytesLeft() {
                return index - end;
            }
        };
    }

    @Override
    public Buffer ensureWritable(int size, int minimumGrowth, boolean allowCompaction) {
        if (!isAccessible()) {
            throw bufferIsClosed(this);
        }
        if (!isOwned()) {
            throw attachTrace(new IllegalStateException(
                    "Buffer is not owned. Only owned buffers can call ensureWritable."));
        }
        if (size < 0) {
            throw new IllegalArgumentException("Cannot ensure writable for a negative size: " + size + '.');
        }
        if (minimumGrowth < 0) {
            throw new IllegalArgumentException("The minimum growth cannot be negative: " + minimumGrowth + '.');
        }
        if (seg != wseg) {
            throw bufferIsReadOnly(this);
        }
        if (writableBytes() >= size) {
            // We already have enough space.
            return this;
        }

        if (allowCompaction && writableBytes() + readerOffset() >= size) {
            // We can solve this with compaction.
            return compact();
        }

        // Allocate a bigger buffer.
        long newSize = capacity() + (long) Math.max(size - writableBytes(), minimumGrowth);
        Statics.assertValidBufferSize(newSize);
        var untethered = control.allocateUntethered(this, (int) newSize);
        MemorySegment newSegment = untethered.memory();

        // Copy contents.
        newSegment.copyFrom(seg);

        // Release the old memory segment and install the new one:
        Drop<MemSegBuffer> drop = untethered.drop();
        disconnectDrop(drop);
        attachNewMemorySegment(newSegment, drop);
        return this;
    }

    private void disconnectDrop(Drop<MemSegBuffer> newDrop) {
        var drop = unsafeGetDrop();
        // Disconnect from the current arc drop, since we'll get our own fresh memory segment.
        int roff = this.roff;
        int woff = this.woff;
        drop.drop(this);
        unsafeSetDrop(newDrop);
        this.roff = roff;
        this.woff = woff;
    }

    private void attachNewMemorySegment(MemorySegment newSegment, Drop<MemSegBuffer> drop) {
        base = newSegment;
        seg = newSegment;
        wseg = newSegment;
        drop.attach(this);
    }

    @Override
    public Buffer split(int splitOffset) {
        if (splitOffset < 0) {
            throw new IllegalArgumentException("The split offset cannot be negative: " + splitOffset + '.');
        }
        if (capacity() < splitOffset) {
            throw new IllegalArgumentException("The split offset cannot be greater than the buffer capacity, " +
                    "but the split offset was " + splitOffset + ", and capacity is " + capacity() + '.');
        }
        if (!isAccessible()) {
            throw attachTrace(bufferIsClosed(this));
        }
        if (!isOwned()) {
            throw attachTrace(new IllegalStateException("Cannot split a buffer that is not owned."));
        }
        var drop = unsafeGetDrop().fork();
        var splitSegment = seg.asSlice(0, splitOffset);
        var splitBuffer = new MemSegBuffer(base, splitSegment, drop, control);
        splitBuffer.woff = Math.min(woff, splitOffset);
        splitBuffer.roff = Math.min(roff, splitOffset);
        boolean readOnly = readOnly();
        if (readOnly) {
            splitBuffer.makeReadOnly();
        }
        seg = seg.asSlice(splitOffset, seg.byteSize() - splitOffset);
        if (!readOnly) {
            wseg = seg;
        }
        woff = Math.max(woff, splitOffset) - splitOffset;
        roff = Math.max(roff, splitOffset) - splitOffset;
        return splitBuffer;
    }

    @Override
    public Buffer compact() {
        if (!isOwned()) {
            throw attachTrace(new IllegalStateException("Buffer must be owned in order to compact."));
        }
        if (readOnly()) {
            throw new BufferReadOnlyException("Buffer must be writable in order to compact, but was read-only.");
        }
        int distance = roff;
        if (distance == 0) {
            return this;
        }
        seg.copyFrom(seg.asSlice(roff, woff - roff));
        roff -= distance;
        woff -= distance;
        return this;
    }

    @Override
    public int countComponents() {
        return 1;
    }

    @Override
    public int countReadableComponents() {
        return readableBytes() > 0? 1 : 0;
    }

    @Override
    public int countWritableComponents() {
        return writableBytes() > 0? 1 : 0;
    }

    @Override
    public <E extends Exception> int forEachReadable(int initialIndex, ReadableComponentProcessor<E> processor)
            throws E {
        checkRead(readerOffset(), Math.max(1, readableBytes()));
        return processor.process(initialIndex, this)? 1 : -1;
    }

    @Override
    public <E extends Exception> int forEachWritable(int initialIndex, WritableComponentProcessor<E> processor)
            throws E {
        checkWrite(writerOffset(), Math.max(1, writableBytes()));
        return processor.process(initialIndex, this)? 1 : -1;
    }

    // <editor-fold defaultstate="collapsed" desc="Primitive accessors implementation.">
    private static byte getByteAtOffset(MemorySegment seg, int roff) {
        return seg.get(JAVA_BYTE, roff);
    }

    private static void setByteAtOffset(MemorySegment seg, int woff, byte value) {
        seg.set(JAVA_BYTE, woff, value);
    }

    private static short getShortAtOffset(MemorySegment seg, int roff) {
        return seg.get(JAVA_SHORT, roff);
    }

    private static void setShortAtOffset(MemorySegment seg, int woff, short value) {
        seg.set(JAVA_SHORT, woff, value);
    }

    private static char getCharAtOffset(MemorySegment seg, int roff) {
        return seg.get(JAVA_CHAR, roff);
    }

    private static void setCharAtOffset(MemorySegment seg, int woff, char value) {
        seg.set(JAVA_CHAR, woff, value);
    }

    private static int getIntAtOffset(MemorySegment seg, int roff) {
        return seg.get(JAVA_INT, roff);
    }

    private static void setIntAtOffset(MemorySegment seg, int woff, int value) {
        seg.set(JAVA_INT, woff, value);
    }

    private static float getFloatAtOffset(MemorySegment seg, int roff) {
        return seg.get(JAVA_FLOAT, roff);
    }

    private static void setFloatAtOffset(MemorySegment seg, int woff, float value) {
        seg.set(JAVA_FLOAT, woff, value);
    }

    private static long getLongAtOffset(MemorySegment seg, int roff) {
        return seg.get(JAVA_LONG, roff);
    }

    private static void setLongAtOffset(MemorySegment seg, int woff, long value) {
        seg.set(JAVA_LONG, woff, value);
    }

    private static double getDoubleAtOffset(MemorySegment seg, int roff) {
        return seg.get(JAVA_DOUBLE, roff);
    }

    private static void setDoubleAtOffset(MemorySegment seg, int woff, double value) {
        seg.set(JAVA_DOUBLE, woff, value);
    }

    @Override
    public byte readByte() {
        checkRead(roff, Byte.BYTES);
        byte value = getByteAtOffset(seg, roff);
        roff += Byte.BYTES;
        return value;
    }

    @Override
    public byte getByte(int roff) {
        checkGet(roff, Byte.BYTES);
        return getByteAtOffset(seg, roff);
    }

    @Override
    public int readUnsignedByte() {
        checkRead(roff, Byte.BYTES);
        int value = getByteAtOffset(seg, roff) & 0xFF;
        roff += Byte.BYTES;
        return value;
    }

    @Override
    public int getUnsignedByte(int roff) {
        checkGet(roff, Byte.BYTES);
        return getByteAtOffset(seg, roff) & 0xFF;
    }

    @Override
    public Buffer writeByte(byte value) {
        try {
            setByteAtOffset(wseg, woff, value);
            woff += Byte.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setByte(int woff, byte value) {
        try {
            setByteAtOffset(wseg, woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer writeUnsignedByte(int value) {
        try {
            setByteAtOffset(wseg, woff, (byte) (value & 0xFF));
            woff += Byte.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setUnsignedByte(int woff, int value) {
        try {
            setByteAtOffset(wseg, woff, (byte) (value & 0xFF));
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public char readChar() {
        checkRead(roff, 2);
        char value = getCharAtOffset(seg, roff);
        roff += 2;
        return value;
    }

    @Override
    public char getChar(int roff) {
        checkGet(roff, 2);
        return getCharAtOffset(seg, roff);
    }

    @Override
    public Buffer writeChar(char value) {
        try {
            setCharAtOffset(wseg, woff, value);
            woff += 2;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setChar(int woff, char value) {
        try {
            setCharAtOffset(wseg, woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public short readShort() {
        checkRead(roff, Short.BYTES);
        short value = getShortAtOffset(seg, roff);
        roff += Short.BYTES;
        return value;
    }

    @Override
    public short getShort(int roff) {
        checkGet(roff, Short.BYTES);
        return getShortAtOffset(seg, roff);
    }

    @Override
    public int readUnsignedShort() {
        checkRead(roff, Short.BYTES);
        int value = getShortAtOffset(seg, roff) & 0xFFFF;
        roff += Short.BYTES;
        return value;
    }

    @Override
    public int getUnsignedShort(int roff) {
        checkGet(roff, Short.BYTES);
        return getShortAtOffset(seg, roff) & 0xFFFF;
    }

    @Override
    public Buffer writeShort(short value) {
        try {
            setShortAtOffset(wseg, woff, value);
            woff += Short.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setShort(int woff, short value) {
        try {
            setShortAtOffset(wseg, woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer writeUnsignedShort(int value) {
        try {
            setShortAtOffset(wseg, woff, (short) (value & 0xFFFF));
            woff += Short.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setUnsignedShort(int woff, int value) {
        try {
            setShortAtOffset(wseg, woff, (short) (value & 0xFFFF));
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public int readMedium() {
        checkRead(roff, 3);
        int value = getByteAtOffset(seg, roff) << 16 |
                (getByteAtOffset(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset(seg, roff + 2) & 0xFF;
        roff += 3;
        return value;
    }

    @Override
    public int getMedium(int roff) {
        checkGet(roff, 3);
        return getByteAtOffset(seg, roff) << 16 |
                (getByteAtOffset(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset(seg, roff + 2) & 0xFF;
    }

    @Override
    public int readUnsignedMedium() {
        checkRead(roff, 3);
        int value = (getByteAtOffset(seg, roff) << 16 |
                (getByteAtOffset(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset(seg, roff + 2) & 0xFF) & 0xFFFFFF;
        roff += 3;
        return value;
    }

    @Override
    public int getUnsignedMedium(int roff) {
        checkGet(roff, 3);
        return (getByteAtOffset(seg, roff) << 16 |
                (getByteAtOffset(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset(seg, roff + 2) & 0xFF) & 0xFFFFFF;
    }

    @Override
    public Buffer writeMedium(int value) {
        checkWrite(woff, 3);
        setByteAtOffset(wseg, woff, (byte) (value >> 16));
        setByteAtOffset(wseg, woff + 1, (byte) (value >> 8 & 0xFF));
        setByteAtOffset(wseg, woff + 2, (byte) (value & 0xFF));
        woff += 3;
        return this;
    }

    @Override
    public Buffer setMedium(int woff, int value) {
        checkSet(woff, 3);
        setByteAtOffset(wseg, woff, (byte) (value >> 16));
        setByteAtOffset(wseg, woff + 1, (byte) (value >> 8 & 0xFF));
        setByteAtOffset(wseg, woff + 2, (byte) (value & 0xFF));
        return this;
    }

    @Override
    public Buffer writeUnsignedMedium(int value) {
        checkWrite(woff, 3);
        setByteAtOffset(wseg, woff, (byte) (value >> 16));
        setByteAtOffset(wseg, woff + 1, (byte) (value >> 8 & 0xFF));
        setByteAtOffset(wseg, woff + 2, (byte) (value & 0xFF));
        woff += 3;
        return this;
    }

    @Override
    public Buffer setUnsignedMedium(int woff, int value) {
        checkSet(woff, 3);
        setByteAtOffset(wseg, woff, (byte) (value >> 16));
        setByteAtOffset(wseg, woff + 1, (byte) (value >> 8 & 0xFF));
        setByteAtOffset(wseg, woff + 2, (byte) (value & 0xFF));
        return this;
    }

    @Override
    public int readInt() {
        checkRead(roff, Integer.BYTES);
        int value = getIntAtOffset(seg, roff);
        roff += Integer.BYTES;
        return value;
    }

    @Override
    public int getInt(int roff) {
        checkGet(roff, Integer.BYTES);
        return getIntAtOffset(seg, roff);
    }

    @Override
    public long readUnsignedInt() {
        checkRead(roff, Integer.BYTES);
        long value = getIntAtOffset(seg, roff) & 0xFFFFFFFFL;
        roff += Integer.BYTES;
        return value;
    }

    @Override
    public long getUnsignedInt(int roff) {
        checkGet(roff, Integer.BYTES);
        return getIntAtOffset(seg, roff) & 0xFFFFFFFFL;
    }

    @Override
    public Buffer writeInt(int value) {
        try {
            setIntAtOffset(wseg, woff, value);
            woff += Integer.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setInt(int woff, int value) {
        try {
            setIntAtOffset(wseg, woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer writeUnsignedInt(long value) {
        try {
            setIntAtOffset(wseg, woff, (int) (value & 0xFFFFFFFFL));
            woff += Integer.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setUnsignedInt(int woff, long value) {
        try {
            setIntAtOffset(wseg, woff, (int) (value & 0xFFFFFFFFL));
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public float readFloat() {
        checkRead(roff, Float.BYTES);
        float value = getFloatAtOffset(seg, roff);
        roff += Float.BYTES;
        return value;
    }

    @Override
    public float getFloat(int roff) {
        checkGet(roff, Float.BYTES);
        return getFloatAtOffset(seg, roff);
    }

    @Override
    public Buffer writeFloat(float value) {
        try {
            setFloatAtOffset(wseg, woff, value);
            woff += Float.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setFloat(int woff, float value) {
        try {
            setFloatAtOffset(wseg, woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public long readLong() {
        checkRead(roff, Long.BYTES);
        long value = getLongAtOffset(seg, roff);
        roff += Long.BYTES;
        return value;
    }

    @Override
    public long getLong(int roff) {
        checkGet(roff, Long.BYTES);
        return getLongAtOffset(seg, roff);
    }

    @Override
    public Buffer writeLong(long value) {
        try {
            setLongAtOffset(wseg, woff, value);
            woff += Long.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setLong(int woff, long value) {
        try {
            setLongAtOffset(wseg, woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public double readDouble() {
        checkRead(roff, Double.BYTES);
        double value = getDoubleAtOffset(seg, roff);
        roff += Double.BYTES;
        return value;
    }

    @Override
    public double getDouble(int roff) {
        checkGet(roff, Double.BYTES);
        return getDoubleAtOffset(seg, roff);
    }

    @Override
    public Buffer writeDouble(double value) {
        try {
            setDoubleAtOffset(wseg, woff, value);
            woff += Double.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setDouble(int woff, double value) {
        try {
            setDoubleAtOffset(wseg, woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }
    // </editor-fold>

    @Override
    protected Owned<MemSegBuffer> prepareSend() {
        var roff = this.roff;
        var woff = this.woff;
        var readOnly = readOnly();
        MemorySegment transferSegment = seg;
        MemorySegment base = this.base;
        return new Owned<MemSegBuffer>() {
            @Override
            public MemSegBuffer transferOwnership(Drop<MemSegBuffer> drop) {
                MemSegBuffer copy = new MemSegBuffer(base, transferSegment, drop, control);
                copy.roff = roff;
                copy.woff = woff;
                if (readOnly) {
                    copy.makeReadOnly();
                }
                return copy;
            }
        };
    }

    @Override
    protected void makeInaccessible() {
        base = CLOSED_SEGMENT;
        seg = CLOSED_SEGMENT;
        wseg = CLOSED_SEGMENT;
        roff = 0;
        woff = 0;
    }

    private void checkRead(int index, int size) {
        if (index < 0 || woff < index + size) {
            throw readAccessCheckException(index);
        }
    }

    private void checkGet(int index, int size) {
        if (index < 0 || seg.byteSize() < index + size) {
            throw readAccessCheckException(index);
        }
    }

    private void checkWrite(int index, int size) {
        if (index < roff || wseg.byteSize() < index + size) {
            throw writeAccessCheckException(index);
        }
    }

    private void checkSet(int index, int size) {
        if (index < 0 || wseg.byteSize() < index + size) {
            throw writeAccessCheckException(index);
        }
    }

    private RuntimeException checkWriteState(IndexOutOfBoundsException ioobe) {
        if (seg == CLOSED_SEGMENT) {
            return bufferIsClosed(this);
        }
        if (wseg != seg) {
            return bufferIsReadOnly(this);
        }
        return ioobe;
    }

    Buffer newConstChild() {
        assert readOnly();
        Drop<MemSegBuffer> drop = unsafeGetDrop().fork();
        MemSegBuffer child = new MemSegBuffer(this, drop);
        drop.attach(child);
        return child;
    }

    private RuntimeException readAccessCheckException(int index) {
        if (seg == CLOSED_SEGMENT) {
            throw bufferIsClosed(this);
        }
        return outOfBounds(index);
    }

    private RuntimeException writeAccessCheckException(int index) {
        if (seg == CLOSED_SEGMENT) {
            throw bufferIsClosed(this);
        }
        if (wseg != seg) {
            return bufferIsReadOnly(this);
        }
        return outOfBounds(index);
    }

    private IndexOutOfBoundsException outOfBounds(int index) {
        return new IndexOutOfBoundsException(
                "Index " + index + " is out of bounds: [read 0 to " + woff + ", write 0 to " +
                seg.byteSize() + "].");
    }

    Object recoverableMemory() {
        return base;
    }
}
