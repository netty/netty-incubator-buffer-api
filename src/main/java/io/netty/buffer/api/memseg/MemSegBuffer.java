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

import io.netty.buffer.api.BufferAllocator;
import io.netty.buffer.api.AllocatorControl;
import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.ByteCursor;
import io.netty.buffer.api.ReadableComponent;
import io.netty.buffer.api.ReadableComponentProcessor;
import io.netty.buffer.api.WritableComponent;
import io.netty.buffer.api.WritableComponentProcessor;
import io.netty.buffer.api.Drop;
import io.netty.buffer.api.Owned;
import io.netty.buffer.api.RcSupport;
import jdk.incubator.foreign.MemorySegment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static jdk.incubator.foreign.MemoryAccess.getByteAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getCharAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getDoubleAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getFloatAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getIntAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getLongAtOffset;
import static jdk.incubator.foreign.MemoryAccess.getShortAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setByteAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setCharAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setDoubleAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setFloatAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setIntAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setLongAtOffset;
import static jdk.incubator.foreign.MemoryAccess.setShortAtOffset;

class MemSegBuffer extends RcSupport<Buffer, MemSegBuffer> implements Buffer, ReadableComponent, WritableComponent {
    private static final MemorySegment CLOSED_SEGMENT;
    static final Drop<MemSegBuffer> SEGMENT_CLOSE;

    static {
        CLOSED_SEGMENT = MemorySegment.ofArray(new byte[0]);
        CLOSED_SEGMENT.close();
        SEGMENT_CLOSE = buf -> {
            try (var ignore = buf.seg) {
                buf.makeInaccessible();
            }
        };
    }

    private final AllocatorControl alloc;
    private final boolean isSendable;
    private MemorySegment seg;
    private MemorySegment wseg;
    private ByteOrder order;
    private int roff;
    private int woff;

    MemSegBuffer(MemorySegment segmet, Drop<MemSegBuffer> drop, AllocatorControl alloc) {
        this(segmet, drop, alloc, true);
    }

    private MemSegBuffer(MemorySegment segment, Drop<MemSegBuffer> drop, AllocatorControl alloc, boolean isSendable) {
        super(drop);
        this.alloc = alloc;
        seg = segment;
        wseg = segment;
        this.isSendable = isSendable;
        order = ByteOrder.nativeOrder();
    }

    @Override
    public String toString() {
        return "Buffer[roff:" + roff + ", woff:" + woff + ", cap:" + seg.byteSize() + ']';
    }

    @Override
    public Buffer order(ByteOrder order) {
        this.order = order;
        return this;
    }

    @Override
    public ByteOrder order() {
        return order;
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
    public MemSegBuffer readerOffset(int index) {
        checkRead(index, 0);
        roff = index;
        return this;
    }

    @Override
    public int writerOffset() {
        return woff;
    }

    @Override
    public MemSegBuffer writerOffset(int index) {
        checkWrite(index, 0);
        woff = index;
        return this;
    }

    @Override
    public Buffer fill(byte value) {
        checkWrite(0, capacity());
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
    public long readableNativeAddress() {
        return nativeAddress();
    }

    @Override
    public ByteBuffer readableBuffer() {
        var buffer = seg.asByteBuffer();
        if (buffer.isDirect()) {
            // TODO Remove this when the slicing of shared, native segments JDK bug is fixed.
            //  See https://mail.openjdk.java.net/pipermail/panama-dev/2021-January/011810.html
            ByteBuffer tmp = ByteBuffer.allocateDirect(buffer.capacity());
            tmp.put(buffer);
            buffer = tmp.position(0);
        }
        buffer = buffer.asReadOnlyBuffer();
        // TODO avoid slicing and just set position+limit when JDK bug is fixed.
        return buffer.slice(readerOffset(), readableBytes()).order(order);
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
    public long writableNativeAddress() {
        return nativeAddress();
    }

    @Override
    public ByteBuffer writableBuffer() {
        var buffer = wseg.asByteBuffer();

        if (buffer.isDirect()) {
            buffer = buffer.position(writerOffset()).limit(writerOffset() + writableBytes());
        } else {
            // TODO avoid slicing and just set position when JDK bug is fixed.
            buffer = buffer.slice(writerOffset(), writableBytes());
        }
        return buffer.order(order);
    }
    // </editor-fold>

    @Override
    public long nativeAddress() {
        try {
            return seg.address().toRawLongValue();
        } catch (UnsupportedOperationException e) {
            return 0; // This is a heap segment.
        }
    }

    @Override
    public Buffer readOnly(boolean readOnly) {
        wseg = readOnly? CLOSED_SEGMENT : seg;
        return this;
    }

    @Override
    public boolean readOnly() {
        return wseg == CLOSED_SEGMENT && seg != CLOSED_SEGMENT;
    }

    @Override
    public Buffer slice(int offset, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative: " + length + '.');
        }
        var slice = seg.asSlice(offset, length);
        acquire();
        Drop<MemSegBuffer> drop = b -> {
            close();
            b.makeInaccessible();
        };
        var sendable = false; // Sending implies ownership change, which we can't do for slices.
        return new MemSegBuffer(slice, drop, alloc, sendable)
                .writerOffset(length)
                .order(order())
                .readOnly(readOnly());
    }

    @Override
    public void copyInto(int srcPos, byte[] dest, int destPos, int length) {
        try (var target = MemorySegment.ofArray(dest)) {
            copyInto(srcPos, target, destPos, length);
        }
    }

    @Override
    public void copyInto(int srcPos, ByteBuffer dest, int destPos, int length) {
        try (var target = MemorySegment.ofByteBuffer(dest.duplicate().clear())) {
            copyInto(srcPos, target, destPos, length);
        }
    }

    private void copyInto(int srcPos, MemorySegment dest, int destPos, int length) {
        if (seg == CLOSED_SEGMENT) {
            throw bufferIsClosed();
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
        if (dest instanceof MemSegBuffer) {
            var memSegBuf = (MemSegBuffer) dest;
            memSegBuf.checkWrite(destPos, length);
            copyInto(srcPos, memSegBuf.seg, destPos, length);
            return;
        }

        // Iterate in reverse to account for src and dest buffer overlap.
        var itr = openReverseCursor(srcPos + length - 1, length);
        ByteOrder prevOrder = dest.order();
        // We read longs in BE, in reverse, so they need to be flipped for writing.
        dest.order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (itr.readLong()) {
                long val = itr.getLong();
                length -= Long.BYTES;
                dest.setLong(destPos + length, val);
            }
            while (itr.readByte()) {
                dest.setByte(destPos + --length, itr.getByte());
            }
        } finally {
            dest.order(prevOrder);
        }
    }

    @Override
    public ByteCursor openCursor() {
        return openCursor(readerOffset(), readableBytes());
    }

    @Override
    public ByteCursor openCursor(int fromOffset, int length) {
        if (seg == CLOSED_SEGMENT) {
            throw bufferIsClosed();
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
            long longValue = -1;
            byte byteValue = -1;

            @Override
            public boolean readLong() {
                if (index + Long.BYTES <= end) {
                    longValue = getLongAtOffset(segment, index, ByteOrder.BIG_ENDIAN);
                    index += Long.BYTES;
                    return true;
                }
                return false;
            }

            @Override
            public long getLong() {
                return longValue;
            }

            @Override
            public boolean readByte() {
                if (index < end) {
                    byteValue = getByteAtOffset(segment, index);
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
            throw bufferIsClosed();
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
            long longValue = -1;
            byte byteValue = -1;

            @Override
            public boolean readLong() {
                if (index - Long.BYTES >= end) {
                    index -= 7;
                    longValue = getLongAtOffset(segment, index, ByteOrder.LITTLE_ENDIAN);
                    index--;
                    return true;
                }
                return false;
            }

            @Override
            public long getLong() {
                return longValue;
            }

            @Override
            public boolean readByte() {
                if (index > end) {
                    byteValue = getByteAtOffset(segment, index);
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
    public void ensureWritable(int size, boolean allowCompaction) {
        if (!isOwned()) {
            throw new IllegalStateException("Buffer is not owned. Only owned buffers can call ensureWritable.");
        }
        if (size < 0) {
            throw new IllegalArgumentException("Cannot ensure writable for a negative size: " + size + '.');
        }
        if (seg != wseg) {
            throw bufferIsReadOnly();
        }
        if (writableBytes() >= size) {
            // We already have enough space.
            return;
        }

        if (allowCompaction && writableBytes() + readerOffset() >= size) {
            // We can solve this with compaction.
            compact();
            return;
        }

        // Allocate a bigger buffer.
        long newSize = capacity() + size - (long) writableBytes();
        BufferAllocator.checkSize(newSize);
        RecoverableMemory recoverableMemory = (RecoverableMemory) alloc.allocateUntethered(this, (int) newSize);
        var newSegment = recoverableMemory.segment;

        // Copy contents.
        newSegment.copyFrom(seg);

        // Release old memory segment:
        var drop = unsafeGetDrop();
        if (drop instanceof BifurcatedDrop) {
            // Disconnect from the bifurcated drop, since we'll get our own fresh memory segment.
            int roff = this.roff;
            int woff = this.woff;
            drop.drop(this);
            drop = ((BifurcatedDrop) drop).unwrap();
            unsafeSetDrop(drop);
            this.roff = roff;
            this.woff = woff;
        } else {
            alloc.recoverMemory(recoverableMemory());
        }

        seg = newSegment;
        wseg = newSegment;
        drop.attach(this);
    }

    @Override
    public Buffer bifurcate() {
        if (!isOwned()) {
            throw new IllegalStateException("Cannot bifurcate a buffer that is not owned.");
        }
        var drop = unsafeGetDrop();
        if (seg.ownerThread() != null) {
            seg = seg.share();
            drop.attach(this);
        }
        if (drop instanceof BifurcatedDrop) {
            ((BifurcatedDrop) drop).increment();
        } else {
            drop = new BifurcatedDrop(new MemSegBuffer(seg, drop, alloc), drop);
            unsafeSetDrop(drop);
        }
        var bifurcatedSeg = seg.asSlice(0, woff);
        var bifurcatedBuf = new MemSegBuffer(bifurcatedSeg, drop, alloc);
        bifurcatedBuf.woff = woff;
        bifurcatedBuf.roff = roff;
        bifurcatedBuf.order(order);
        boolean readOnly = readOnly();
        bifurcatedBuf.readOnly(readOnly);
        seg = seg.asSlice(woff, seg.byteSize() - woff);
        if (!readOnly) {
            wseg = seg;
        }
        woff = 0;
        roff = 0;
        return bifurcatedBuf;
    }

    @Override
    public void compact() {
        if (!isOwned()) {
            throw new IllegalStateException("Buffer must be owned in order to compact.");
        }
        if (readOnly()) {
            throw new IllegalStateException("Buffer must be writable in order to compact, but was read-only.");
        }
        int distance = roff;
        if (distance == 0) {
            return;
        }
        seg.copyFrom(seg.asSlice(roff, woff - roff));
        roff -= distance;
        woff -= distance;
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
    @Override
    public byte readByte() {
        checkRead(roff, Byte.BYTES);
        byte value = getByteAtOffset(seg, roff);
        roff += Byte.BYTES;
        return value;
    }

    @Override
    public byte getByte(int roff) {
        checkRead(roff, Byte.BYTES);
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
        checkRead(roff, Byte.BYTES);
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
        char value = getCharAtOffset(seg, roff, order);
        roff += 2;
        return value;
    }

    @Override
    public char getChar(int roff) {
        checkRead(roff, 2);
        return getCharAtOffset(seg, roff, order);
    }

    @Override
    public Buffer writeChar(char value) {
        try {
            setCharAtOffset(wseg, woff, order, value);
            woff += 2;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setChar(int woff, char value) {
        try {
            setCharAtOffset(wseg, woff, order, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public short readShort() {
        checkRead(roff, Short.BYTES);
        short value = getShortAtOffset(seg, roff, order);
        roff += Short.BYTES;
        return value;
    }

    @Override
    public short getShort(int roff) {
        checkRead(roff, Short.BYTES);
        return getShortAtOffset(seg, roff, order);
    }

    @Override
    public int readUnsignedShort() {
        checkRead(roff, Short.BYTES);
        int value = getShortAtOffset(seg, roff, order) & 0xFFFF;
        roff += Short.BYTES;
        return value;
    }

    @Override
    public int getUnsignedShort(int roff) {
        checkRead(roff, Short.BYTES);
        return getShortAtOffset(seg, roff, order) & 0xFFFF;
    }

    @Override
    public Buffer writeShort(short value) {
        try {
            setShortAtOffset(wseg, woff, order, value);
            woff += Short.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setShort(int woff, short value) {
        try {
            setShortAtOffset(wseg, woff, order, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer writeUnsignedShort(int value) {
        try {
            setShortAtOffset(wseg, woff, order, (short) (value & 0xFFFF));
            woff += Short.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setUnsignedShort(int woff, int value) {
        try {
            setShortAtOffset(wseg, woff, order, (short) (value & 0xFFFF));
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public int readMedium() {
        checkRead(roff, 3);
        int value = order == ByteOrder.BIG_ENDIAN?
                getByteAtOffset(seg, roff) << 16 |
                (getByteAtOffset(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset(seg, roff + 2) & 0xFF :
                getByteAtOffset(seg, roff) & 0xFF |
                (getByteAtOffset(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset(seg, roff + 2) << 16;
        roff += 3;
        return value;
    }

    @Override
    public int getMedium(int roff) {
        checkRead(roff, 3);
        return order == ByteOrder.BIG_ENDIAN?
                getByteAtOffset(seg, roff) << 16 |
                (getByteAtOffset(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset(seg, roff + 2) & 0xFF :
                getByteAtOffset(seg, roff) & 0xFF |
                (getByteAtOffset(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset(seg, roff + 2) << 16;
    }

    @Override
    public int readUnsignedMedium() {
        checkRead(roff, 3);
        int value = order == ByteOrder.BIG_ENDIAN?
                (getByteAtOffset(seg, roff) << 16 |
                (getByteAtOffset(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset(seg, roff + 2) & 0xFF) & 0xFFFFFF :
                (getByteAtOffset(seg, roff) & 0xFF |
                (getByteAtOffset(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset(seg, roff + 2) << 16) & 0xFFFFFF;
        roff += 3;
        return value;
    }

    @Override
    public int getUnsignedMedium(int roff) {
        checkRead(roff, 3);
        return order == ByteOrder.BIG_ENDIAN?
                (getByteAtOffset(seg, roff) << 16 |
                (getByteAtOffset(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset(seg, roff + 2) & 0xFF) & 0xFFFFFF :
                (getByteAtOffset(seg, roff) & 0xFF |
                (getByteAtOffset(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset(seg, roff + 2) << 16) & 0xFFFFFF;
    }

    @Override
    public Buffer writeMedium(int value) {
        checkWrite(woff, 3);
        if (order == ByteOrder.BIG_ENDIAN) {
            setByteAtOffset(wseg, woff, (byte) (value >> 16));
            setByteAtOffset(wseg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(wseg, woff + 2, (byte) (value & 0xFF));
        } else {
            setByteAtOffset(wseg, woff, (byte) (value & 0xFF));
            setByteAtOffset(wseg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(wseg, woff + 2, (byte) (value >> 16 & 0xFF));
        }
        woff += 3;
        return this;
    }

    @Override
    public Buffer setMedium(int woff, int value) {
        checkWrite(woff, 3);
        if (order == ByteOrder.BIG_ENDIAN) {
            setByteAtOffset(wseg, woff, (byte) (value >> 16));
            setByteAtOffset(wseg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(wseg, woff + 2, (byte) (value & 0xFF));
        } else {
            setByteAtOffset(wseg, woff, (byte) (value & 0xFF));
            setByteAtOffset(wseg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(wseg, woff + 2, (byte) (value >> 16 & 0xFF));
        }
        return this;
    }

    @Override
    public Buffer writeUnsignedMedium(int value) {
        checkWrite(woff, 3);
        if (order == ByteOrder.BIG_ENDIAN) {
            setByteAtOffset(wseg, woff, (byte) (value >> 16));
            setByteAtOffset(wseg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(wseg, woff + 2, (byte) (value & 0xFF));
        } else {
            setByteAtOffset(wseg, woff, (byte) (value & 0xFF));
            setByteAtOffset(wseg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(wseg, woff + 2, (byte) (value >> 16 & 0xFF));
        }
        woff += 3;
        return this;
    }

    @Override
    public Buffer setUnsignedMedium(int woff, int value) {
        checkWrite(woff, 3);
        if (order == ByteOrder.BIG_ENDIAN) {
            setByteAtOffset(wseg, woff, (byte) (value >> 16));
            setByteAtOffset(wseg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(wseg, woff + 2, (byte) (value & 0xFF));
        } else {
            setByteAtOffset(wseg, woff, (byte) (value & 0xFF));
            setByteAtOffset(wseg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(wseg, woff + 2, (byte) (value >> 16 & 0xFF));
        }
        return this;
    }

    @Override
    public int readInt() {
        checkRead(roff, Integer.BYTES);
        int value = getIntAtOffset(seg, roff, order);
        roff += Integer.BYTES;
        return value;
    }

    @Override
    public int getInt(int roff) {
        checkRead(roff, Integer.BYTES);
        return getIntAtOffset(seg, roff, order);
    }

    @Override
    public long readUnsignedInt() {
        checkRead(roff, Integer.BYTES);
        long value = getIntAtOffset(seg, roff, order) & 0xFFFFFFFFL;
        roff += Integer.BYTES;
        return value;
    }

    @Override
    public long getUnsignedInt(int roff) {
        checkRead(roff, Integer.BYTES);
        return getIntAtOffset(seg, roff, order) & 0xFFFFFFFFL;
    }

    @Override
    public Buffer writeInt(int value) {
        try {
            setIntAtOffset(wseg, woff, order, value);
            woff += Integer.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setInt(int woff, int value) {
        try {
            setIntAtOffset(wseg, woff, order, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer writeUnsignedInt(long value) {
        try {
            setIntAtOffset(wseg, woff, order, (int) (value & 0xFFFFFFFFL));
            woff += Integer.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setUnsignedInt(int woff, long value) {
        try {
            setIntAtOffset(wseg, woff, order, (int) (value & 0xFFFFFFFFL));
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public float readFloat() {
        checkRead(roff, Float.BYTES);
        float value = getFloatAtOffset(seg, roff, order);
        roff += Float.BYTES;
        return value;
    }

    @Override
    public float getFloat(int roff) {
        checkRead(roff, Float.BYTES);
        return getFloatAtOffset(seg, roff, order);
    }

    @Override
    public Buffer writeFloat(float value) {
        try {
            setFloatAtOffset(wseg, woff, order, value);
            woff += Float.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setFloat(int woff, float value) {
        try {
            setFloatAtOffset(wseg, woff, order, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public long readLong() {
        checkRead(roff, Long.BYTES);
        long value = getLongAtOffset(seg, roff, order);
        roff += Long.BYTES;
        return value;
    }

    @Override
    public long getLong(int roff) {
        checkRead(roff, Long.BYTES);
        return getLongAtOffset(seg, roff, order);
    }

    @Override
    public Buffer writeLong(long value) {
        try {
            setLongAtOffset(wseg, woff, order, value);
            woff += Long.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setLong(int woff, long value) {
        try {
            setLongAtOffset(wseg, woff, order, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public double readDouble() {
        checkRead(roff, Double.BYTES);
        double value = getDoubleAtOffset(seg, roff, order);
        roff += Double.BYTES;
        return value;
    }

    @Override
    public double getDouble(int roff) {
        checkRead(roff, Double.BYTES);
        return getDoubleAtOffset(seg, roff, order);
    }

    @Override
    public Buffer writeDouble(double value) {
        try {
            setDoubleAtOffset(wseg, woff, order, value);
            woff += Double.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }

    @Override
    public Buffer setDouble(int woff, double value) {
        try {
            setDoubleAtOffset(wseg, woff, order, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e);
        }
    }
    // </editor-fold>

    @Override
    protected Owned<MemSegBuffer> prepareSend() {
        var order = this.order;
        var roff = this.roff;
        var woff = this.woff;
        var readOnly = readOnly();
        boolean isConfined = seg.ownerThread() == null;
        MemorySegment transferSegment = isConfined? seg : seg.share();
        makeInaccessible();
        return new Owned<MemSegBuffer>() {
            @Override
            public MemSegBuffer transferOwnership(Drop<MemSegBuffer> drop) {
                MemSegBuffer copy = new MemSegBuffer(transferSegment, drop, alloc);
                copy.order = order;
                copy.roff = roff;
                copy.woff = woff;
                copy.readOnly(readOnly);
                return copy;
            }
        };
    }

    void makeInaccessible() {
        seg = CLOSED_SEGMENT;
        wseg = CLOSED_SEGMENT;
        roff = 0;
        woff = 0;
    }

    @Override
    protected IllegalStateException notSendableException() {
        if (!isSendable) {
            return new IllegalStateException(
                    "Cannot send() this buffer. This buffer might be a slice of another buffer.");
        }
        return super.notSendableException();
    }

    @Override
    public boolean isOwned() {
        return isSendable && super.isOwned();
    }

    private void checkRead(int index, int size) {
        if (index < 0 || woff < index + size) {
            throw readAccessCheckException(index);
        }
    }

    private void checkWrite(int index, int size) {
        if (index < 0 || wseg.byteSize() < index + size) {
            throw writeAccessCheckException(index);
        }
    }

    private RuntimeException checkWriteState(IndexOutOfBoundsException ioobe) {
        if (seg == CLOSED_SEGMENT) {
            return bufferIsClosed();
        }
        if (wseg != seg) {
            return bufferIsReadOnly();
        }
        return ioobe;
    }

    private RuntimeException readAccessCheckException(int index) {
        if (seg == CLOSED_SEGMENT) {
            throw bufferIsClosed();
        }
        return outOfBounds(index);
    }

    private RuntimeException writeAccessCheckException(int index) {
        if (seg == CLOSED_SEGMENT) {
            throw bufferIsClosed();
        }
        if (wseg != seg) {
            return bufferIsReadOnly();
        }
        return outOfBounds(index);
    }

    private static IllegalStateException bufferIsClosed() {
        return new IllegalStateException("This buffer is closed.");
    }

    private static IllegalStateException bufferIsReadOnly() {
        return new IllegalStateException("This buffer is read-only.");
    }

    private IndexOutOfBoundsException outOfBounds(int index) {
        return new IndexOutOfBoundsException(
                "Index " + index + " is out of bounds: [read 0 to " + woff + ", write 0 to " +
                (seg.byteSize() - 1) + "].");
    }

    Object recoverableMemory() {
        return new RecoverableMemory(seg, alloc);
    }

    static final class RecoverableMemory {
        private final MemorySegment segment;
        private final AllocatorControl alloc;

        RecoverableMemory(MemorySegment segment, AllocatorControl alloc) {
            this.segment = segment;
            this.alloc = alloc;
        }

        Buffer recover(Drop<MemSegBuffer> drop) {
            return new MemSegBuffer(segment, drop, alloc);
        }
    }
}
