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

import io.netty.buffer.api.Allocator;
import io.netty.buffer.api.AllocatorControl;
import io.netty.buffer.api.Buf;
import io.netty.buffer.api.ByteCursor;
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

class MemSegBuf extends RcSupport<Buf, MemSegBuf> implements Buf {
    static final Drop<MemSegBuf> SEGMENT_CLOSE = buf -> buf.seg.close();
    private final AllocatorControl alloc;
    private final boolean isSendable;
    private MemorySegment seg;
    private ByteOrder order;
    private int roff;
    private int woff;

    MemSegBuf(MemorySegment segmet, Drop<MemSegBuf> drop, AllocatorControl alloc) {
        this(segmet, drop, alloc, true);
    }

    private MemSegBuf(MemorySegment segment, Drop<MemSegBuf> drop, AllocatorControl alloc, boolean isSendable) {
        super(drop);
        this.alloc = alloc;
        seg = segment;
        this.isSendable = isSendable;
        order = ByteOrder.nativeOrder();
    }

    @Override
    public String toString() {
        return "Buf[roff:" + roff + ", woff:" + woff + ", cap:" + seg.byteSize() + ']';
    }

    @Override
    public Buf order(ByteOrder order) {
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
    public MemSegBuf readerOffset(int index) {
        checkRead(index, 0);
        roff = index;
        return this;
    }

    @Override
    public int writerOffset() {
        return woff;
    }

    @Override
    public MemSegBuf writerOffset(int index) {
        checkWrite(index, 0);
        woff = index;
        return this;
    }

    @Override
    public Buf fill(byte value) {
        seg.fill(value);
        return this;
    }

    @Override
    public long getNativeAddress() {
        try {
            return seg.address().toRawLongValue();
        } catch (UnsupportedOperationException e) {
            return 0; // This is a heap segment.
        }
    }

    @Override
    public Buf slice(int offset, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative: " + length + '.');
        }
        var slice = seg.asSlice(offset, length);
        acquire();
        Drop<MemSegBuf> drop = b -> close();
        var sendable = false; // Sending implies ownership change, which we can't do for slices.
        return new MemSegBuf(slice, drop, alloc, sendable).writerOffset(length).order(order());
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
        dest.asSlice(destPos, length).copyFrom(seg.asSlice(srcPos, length));
    }

    @Override
    public void copyInto(int srcPos, Buf dest, int destPos, int length) {
        // todo optimise: specialise for MemSegBuf.
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
    public ByteCursor openCursor(int fromOffset, int length) {
        if (fromOffset < 0) {
            throw new IllegalArgumentException("The fromOffset cannot be negative: " + fromOffset + '.');
        }
        if (length < 0) {
            throw new IllegalArgumentException("The length cannot be negative: " + length + '.');
        }
        if (seg.byteSize() < fromOffset + length) {
            throw new IllegalArgumentException("The fromOffset+length is beyond the end of the buffer: " +
                                               "fromOffset=" + fromOffset + ", length=" + length + '.');
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
    public ByteCursor openReverseCursor(int fromOffset, int length) {
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
            throw new IllegalArgumentException("The fromOffset-length would underflow the buffer: " +
                                               "fromOffset=" + fromOffset + ", length=" + length + '.');
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
    public void ensureWritable(int size) {
        if (!isOwned()) {
            throw new IllegalStateException("Buffer is not owned. Only owned buffers can call ensureWritable.");
        }
        if (size < 0) {
            throw new IllegalArgumentException("Cannot ensure writable for a negative size: " + size + '.');
        }
        if (writableBytes() < size) {
            long newSize = capacity() + size - (long) writableBytes();
            Allocator.checkSize(newSize);
            RecoverableMemory recoverableMemory = (RecoverableMemory) alloc.allocateUntethered(this, (int) newSize);
            var newSegment = recoverableMemory.segment;
            newSegment.copyFrom(seg);

            // Release old memory segment:
            var drop = unsafeGetDrop();
            if (drop instanceof BifurcatedDrop) {
                // Disconnect from the bifurcated drop, since we'll get our own fresh memory segment.
                drop.drop(this);
                drop = ((BifurcatedDrop<MemSegBuf>) drop).unwrap();
                unsafeSetDrop(drop);
            } else {
                alloc.recoverMemory(recoverableMemory());
            }

            seg = newSegment;
            drop.attach(this);
        }
    }

    @Override
    public Buf bifurcate() {
        if (!isOwned()) {
            throw new IllegalStateException("Cannot bifurcate a buffer that is not owned.");
        }
        var drop = unsafeGetDrop();
        if (seg.ownerThread() != null) {
            seg = seg.share();
            drop.attach(this);
        }
        if (drop instanceof BifurcatedDrop) {
            ((BifurcatedDrop<?>) drop).increment();
        } else {
            drop = new BifurcatedDrop<MemSegBuf>(new MemSegBuf(seg, drop, alloc), drop);
            unsafeSetDrop(drop);
        }
        var bifurcatedSeg = seg.asSlice(0, woff);
        var bifurcatedBuf = new MemSegBuf(bifurcatedSeg, drop, alloc);
        bifurcatedBuf.woff = woff;
        bifurcatedBuf.roff = roff;
        bifurcatedBuf.order(order);
        seg = seg.asSlice(woff, seg.byteSize() - woff);
        woff = 0;
        roff = 0;
        return bifurcatedBuf;
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
    public Buf writeByte(byte value) {
        setByteAtOffset(seg, woff, value);
        woff += Byte.BYTES;
        return this;
    }

    @Override
    public Buf setByte(int woff, byte value) {
        setByteAtOffset(seg, woff, value);
        return this;
    }

    @Override
    public Buf writeUnsignedByte(int value) {
        setByteAtOffset(seg, woff, (byte) (value & 0xFF));
        woff += Byte.BYTES;
        return this;
    }

    @Override
    public Buf setUnsignedByte(int woff, int value) {
        setByteAtOffset(seg, woff, (byte) (value & 0xFF));
        return this;
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
    public Buf writeChar(char value) {
        setCharAtOffset(seg, woff, order, value);
        woff += 2;
        return this;
    }

    @Override
    public Buf setChar(int woff, char value) {
        setCharAtOffset(seg, woff, order, value);
        return this;
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
    public Buf writeShort(short value) {
        setShortAtOffset(seg, woff, order, value);
        woff += Short.BYTES;
        return this;
    }

    @Override
    public Buf setShort(int woff, short value) {
        setShortAtOffset(seg, woff, order, value);
        return this;
    }

    @Override
    public Buf writeUnsignedShort(int value) {
        setShortAtOffset(seg, woff, order, (short) (value & 0xFFFF));
        woff += Short.BYTES;
        return this;
    }

    @Override
    public Buf setUnsignedShort(int woff, int value) {
        setShortAtOffset(seg, woff, order, (short) (value & 0xFFFF));
        return this;
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
    public Buf writeMedium(int value) {
        checkWrite(woff, 3);
        if (order == ByteOrder.BIG_ENDIAN) {
            setByteAtOffset(seg, woff, (byte) (value >> 16));
            setByteAtOffset(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(seg, woff + 2, (byte) (value & 0xFF));
        } else {
            setByteAtOffset(seg, woff, (byte) (value & 0xFF));
            setByteAtOffset(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(seg, woff + 2, (byte) (value >> 16 & 0xFF));
        }
        woff += 3;
        return this;
    }

    @Override
    public Buf setMedium(int woff, int value) {
        checkWrite(woff, 3);
        if (order == ByteOrder.BIG_ENDIAN) {
            setByteAtOffset(seg, woff, (byte) (value >> 16));
            setByteAtOffset(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(seg, woff + 2, (byte) (value & 0xFF));
        } else {
            setByteAtOffset(seg, woff, (byte) (value & 0xFF));
            setByteAtOffset(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(seg, woff + 2, (byte) (value >> 16 & 0xFF));
        }
        return this;
    }

    @Override
    public Buf writeUnsignedMedium(int value) {
        checkWrite(woff, 3);
        if (order == ByteOrder.BIG_ENDIAN) {
            setByteAtOffset(seg, woff, (byte) (value >> 16));
            setByteAtOffset(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(seg, woff + 2, (byte) (value & 0xFF));
        } else {
            setByteAtOffset(seg, woff, (byte) (value & 0xFF));
            setByteAtOffset(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(seg, woff + 2, (byte) (value >> 16 & 0xFF));
        }
        woff += 3;
        return this;
    }

    @Override
    public Buf setUnsignedMedium(int woff, int value) {
        checkWrite(woff, 3);
        if (order == ByteOrder.BIG_ENDIAN) {
            setByteAtOffset(seg, woff, (byte) (value >> 16));
            setByteAtOffset(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(seg, woff + 2, (byte) (value & 0xFF));
        } else {
            setByteAtOffset(seg, woff, (byte) (value & 0xFF));
            setByteAtOffset(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset(seg, woff + 2, (byte) (value >> 16 & 0xFF));
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
    public Buf writeInt(int value) {
        setIntAtOffset(seg, woff, order, value);
        woff += Integer.BYTES;
        return this;
    }

    @Override
    public Buf setInt(int woff, int value) {
        setIntAtOffset(seg, woff, order, value);
        return this;
    }

    @Override
    public Buf writeUnsignedInt(long value) {
        setIntAtOffset(seg, woff, order, (int) (value & 0xFFFFFFFFL));
        woff += Integer.BYTES;
        return this;
    }

    @Override
    public Buf setUnsignedInt(int woff, long value) {
        setIntAtOffset(seg, woff, order, (int) (value & 0xFFFFFFFFL));
        return this;
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
    public Buf writeFloat(float value) {
        setFloatAtOffset(seg, woff, order, value);
        woff += Float.BYTES;
        return this;
    }

    @Override
    public Buf setFloat(int woff, float value) {
        setFloatAtOffset(seg, woff, order, value);
        return this;
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
    public Buf writeLong(long value) {
        setLongAtOffset(seg, woff, order, value);
        woff += Long.BYTES;
        return this;
    }

    @Override
    public Buf setLong(int woff, long value) {
        setLongAtOffset(seg, woff, order, value);
        return this;
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
    public Buf writeDouble(double value) {
        setDoubleAtOffset(seg, woff, order, value);
        woff += Double.BYTES;
        return this;
    }

    @Override
    public Buf setDouble(int woff, double value) {
        setDoubleAtOffset(seg, woff, order, value);
        return this;
    }
    // </editor-fold>

    @Override
    protected Owned<MemSegBuf> prepareSend() {
        MemSegBuf outer = this;
        boolean isConfined = seg.ownerThread() == null;
        MemorySegment transferSegment = isConfined? seg : seg.share();
        return new Owned<MemSegBuf>() {
            @Override
            public MemSegBuf transferOwnership(Drop<MemSegBuf> drop) {
                MemSegBuf copy = new MemSegBuf(transferSegment, drop, alloc);
                copy.order = outer.order;
                copy.roff = outer.roff;
                copy.woff = outer.woff;
                return copy;
            }
        };
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
            throw indexOutOfBounds(index);
        }
    }

    private void checkWrite(int index, int size) {
        if (index < 0 || seg.byteSize() < index + size) {
            throw indexOutOfBounds(index);
        }
    }

    private IndexOutOfBoundsException indexOutOfBounds(int index) {
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

        Buf recover(Drop<MemSegBuf> drop) {
            return new MemSegBuf(segment, drop, alloc);
        }
    }
}
