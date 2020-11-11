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

import io.netty.util.ByteIterator;
import jdk.incubator.foreign.MemorySegment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.NoSuchElementException;

import static jdk.incubator.foreign.MemoryAccess.getByteAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.getByteAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.getCharAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.getCharAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.getDoubleAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.getDoubleAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.getFloatAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.getFloatAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.getIntAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.getIntAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.getLongAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.getLongAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.getShortAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.getShortAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.setByteAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.setByteAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.setCharAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.setCharAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.setDoubleAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.setDoubleAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.setFloatAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.setFloatAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.setIntAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.setIntAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.setLongAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.setLongAtOffset_LE;
import static jdk.incubator.foreign.MemoryAccess.setShortAtOffset_BE;
import static jdk.incubator.foreign.MemoryAccess.setShortAtOffset_LE;

class MemSegBuf extends RcSupport<Buf, MemSegBuf> implements Buf {
    static final Drop<MemSegBuf> SEGMENT_CLOSE = buf -> buf.seg.close();
    final MemorySegment seg;
    private final boolean isSendable;
    private boolean isBigEndian;
    private int roff;
    private int woff;

    MemSegBuf(MemorySegment segmet, Drop<MemSegBuf> drop) {
        this(segmet, drop, true);
    }

    private MemSegBuf(MemorySegment segment, Drop<MemSegBuf> drop, boolean isSendable) {
        super(drop);
        seg = segment;
        this.isSendable = isSendable;
        isBigEndian = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
    }

    @Override
    public Buf order(ByteOrder order) {
        isBigEndian = order == ByteOrder.BIG_ENDIAN;
        return this;
    }

    @Override
    public ByteOrder order() {
        return isBigEndian? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
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
        var slice = seg.asSlice(offset, length);
        acquire();
        Drop<MemSegBuf> drop = b -> close();
        var sendable = false; // Sending implies ownership change, which we can't do for slices.
        return new MemSegBuf(slice, drop, sendable).writerOffset(length).order(order());
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
        var itr = iterateReverse(srcPos + length - 1, length);
        ByteOrder prevOrder = dest.order();
        // We read longs in BE, in reverse, so they need to be flipped for writing.
        dest.order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (itr.hasNextLong()) {
                long val = itr.nextLong();
                length -= Long.BYTES;
                dest.setLong(destPos + length, val);
            }
            while (itr.hasNextByte()) {
                dest.setByte(destPos + --length, itr.nextByte());
            }
        } finally {
            dest.order(prevOrder);
        }
    }

    @Override
    public ByteIterator iterate(int fromOffset, int length) {
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
        return new ByteIterator() {
            final MemorySegment segment = seg;
            int index = fromOffset;
            final int end = index + length;

            @Override
            public boolean hasNextLong() {
                return index + Long.BYTES <= end;
            }

            @Override
            public long nextLong() {
                if (!hasNextLong()) {
                    throw new NoSuchElementException("No 'long' value at offet " + currentOffset() + '.');
                }
                long val = getLongAtOffset_BE(segment, index);
                index += Long.BYTES;
                return val;
            }

            @Override
            public boolean hasNextByte() {
                return index < end;
            }

            @Override
            public byte nextByte() {
                if (!hasNextByte()) {
                    throw new NoSuchElementException("No 'byte' value at offet " + currentOffset() + '.');
                }
                byte val = getByteAtOffset_BE(segment, index);
                index++;
                return val;
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
    public ByteIterator iterateReverse(int fromOffset, int length) {
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
        return new ByteIterator() {
            final MemorySegment segment = seg;
            int index = fromOffset;
            final int end = index - length;

            @Override
            public boolean hasNextLong() {
                return index - Long.BYTES >= end;
            }

            @Override
            public long nextLong() {
                if (!hasNextLong()) {
                    throw new NoSuchElementException("No 'long' value at offet " + currentOffset() + '.');
                }
                index -= 7;
                long val = getLongAtOffset_LE(segment, index);
                index--;
                return val;
            }

            @Override
            public boolean hasNextByte() {
                return index > end;
            }

            @Override
            public byte nextByte() {
                if (!hasNextByte()) {
                    throw new NoSuchElementException("No 'byte' value at offet " + currentOffset() + '.');
                }
                byte val = getByteAtOffset_LE(segment, index);
                index--;
                return val;
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

    // <editor-fold defaultstate="collapsed" desc="Primitive accessors implementation.">
    @Override
    public byte readByte() {
        checkRead(roff, Byte.BYTES);
        byte value = isBigEndian? getByteAtOffset_BE(seg, roff) : getByteAtOffset_LE(seg, roff);
        roff += Byte.BYTES;
        return value;
    }

    @Override
    public byte getByte(int roff) {
        checkRead(roff, Byte.BYTES);
        return isBigEndian? getByteAtOffset_BE(seg, roff) : getByteAtOffset_LE(seg, roff);
    }

    @Override
    public int readUnsignedByte() {
        checkRead(roff, Byte.BYTES);
        int value = (isBigEndian? getByteAtOffset_BE(seg, roff) : getByteAtOffset_LE(seg, roff)) & 0xFF;
        roff += Byte.BYTES;
        return value;
    }

    @Override
    public int getUnsignedByte(int roff) {
        checkRead(roff, Byte.BYTES);
        return (isBigEndian? getByteAtOffset_BE(seg, roff) : getByteAtOffset_LE(seg, roff)) & 0xFF;
    }

    @Override
    public Buf writeByte(byte value) {
        if (isBigEndian) {
            setByteAtOffset_BE(seg, woff, value);
        } else {
            setByteAtOffset_LE(seg, woff, value);
        }
        woff += Byte.BYTES;
        return this;
    }

    @Override
    public Buf setByte(int woff, byte value) {
        if (isBigEndian) {
            setByteAtOffset_BE(seg, woff, value);
        } else {
            setByteAtOffset_LE(seg, woff, value);
        }
        return this;
    }

    @Override
    public Buf writeUnsignedByte(int value) {
        if (isBigEndian) {
            setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));
        } else {
            setByteAtOffset_LE(seg, woff, (byte) (value & 0xFF));
        }
        woff += Byte.BYTES;
        return this;
    }

    @Override
    public Buf setUnsignedByte(int woff, int value) {
        if (isBigEndian) {
            setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));
        } else {
            setByteAtOffset_LE(seg, woff, (byte) (value & 0xFF));
        }
        return this;
    }

    @Override
    public char readChar() {
        checkRead(roff, 2);
        char value = isBigEndian? getCharAtOffset_BE(seg, roff) : getCharAtOffset_LE(seg, roff);
        roff += 2;
        return value;
    }

    @Override
    public char getChar(int roff) {
        checkRead(roff, 2);
        return isBigEndian? getCharAtOffset_BE(seg, roff) : getCharAtOffset_LE(seg, roff);
    }

    @Override
    public Buf writeChar(char value) {
        if (isBigEndian) {
            setCharAtOffset_BE(seg, woff, value);
        } else {
            setCharAtOffset_LE(seg, woff, value);
        }
        woff += 2;
        return this;
    }

    @Override
    public Buf setChar(int woff, char value) {
        if (isBigEndian) {
            setCharAtOffset_BE(seg, woff, value);
        } else {
            setCharAtOffset_LE(seg, woff, value);
        }
        return this;
    }

    @Override
    public short readShort() {
        checkRead(roff, Short.BYTES);
        short value = isBigEndian? getShortAtOffset_BE(seg, roff) : getShortAtOffset_LE(seg, roff);
        roff += Short.BYTES;
        return value;
    }

    @Override
    public short getShort(int roff) {
        checkRead(roff, Short.BYTES);
        return isBigEndian? getShortAtOffset_BE(seg, roff) : getShortAtOffset_LE(seg, roff);
    }

    @Override
    public int readUnsignedShort() {
        checkRead(roff, Short.BYTES);
        int value = (isBigEndian? getShortAtOffset_BE(seg, roff) : getShortAtOffset_LE(seg, roff)) & 0xFFFF;
        roff += Short.BYTES;
        return value;
    }

    @Override
    public int getUnsignedShort(int roff) {
        checkRead(roff, Short.BYTES);
        return (isBigEndian? getShortAtOffset_BE(seg, roff) : getShortAtOffset_LE(seg, roff)) & 0xFFFF;
    }

    @Override
    public Buf writeShort(short value) {
        if (isBigEndian) {
            setShortAtOffset_BE(seg, woff, value);
        } else {
            setShortAtOffset_LE(seg, woff, value);
        }
        woff += Short.BYTES;
        return this;
    }

    @Override
    public Buf setShort(int woff, short value) {
        if (isBigEndian) {
            setShortAtOffset_BE(seg, woff, value);
        } else {
            setShortAtOffset_LE(seg, woff, value);
        }
        return this;
    }

    @Override
    public Buf writeUnsignedShort(int value) {
        if (isBigEndian) {
            setShortAtOffset_BE(seg, woff, (short) (value & 0xFFFF));
        } else {
            setShortAtOffset_LE(seg, woff, (short) (value & 0xFFFF));
        }
        woff += Short.BYTES;
        return this;
    }

    @Override
    public Buf setUnsignedShort(int woff, int value) {
        if (isBigEndian) {
            setShortAtOffset_BE(seg, woff, (short) (value & 0xFFFF));
        } else {
            setShortAtOffset_LE(seg, woff, (short) (value & 0xFFFF));
        }
        return this;
    }

    @Override
    public int readMedium() {
        checkRead(roff, 3);
        int value = isBigEndian?
                getByteAtOffset_BE(seg, roff) << 16 |
                (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset_BE(seg, roff + 2) & 0xFF :
                getByteAtOffset_BE(seg, roff) & 0xFF |
                (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset_BE(seg, roff + 2) << 16;
        roff += 3;
        return value;
    }

    @Override
    public int getMedium(int roff) {
        checkRead(roff, 3);
        return isBigEndian?
                getByteAtOffset_BE(seg, roff) << 16 |
                (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset_BE(seg, roff + 2) & 0xFF :
                getByteAtOffset_BE(seg, roff) & 0xFF |
                (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset_BE(seg, roff + 2) << 16;
    }

    @Override
    public int readUnsignedMedium() {
        checkRead(roff, 3);
        int value = isBigEndian?
                (getByteAtOffset_BE(seg, roff) << 16 |
                (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset_BE(seg, roff + 2) & 0xFF) & 0xFFFFFF :
                (getByteAtOffset_BE(seg, roff) & 0xFF |
                (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset_BE(seg, roff + 2) << 16) & 0xFFFFFF;
        roff += 3;
        return value;
    }

    @Override
    public int getUnsignedMedium(int roff) {
        checkRead(roff, 3);
        return isBigEndian?
                (getByteAtOffset_BE(seg, roff) << 16 |
                (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset_BE(seg, roff + 2) & 0xFF) & 0xFFFFFF :
                (getByteAtOffset_BE(seg, roff) & 0xFF |
                (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                getByteAtOffset_BE(seg, roff + 2) << 16) & 0xFFFFFF;
    }

    @Override
    public Buf writeMedium(int value) {
        checkWrite(woff, 3);
        if (isBigEndian) {
            setByteAtOffset_BE(seg, woff, (byte) (value >> 16));
            setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset_BE(seg, woff + 2, (byte) (value & 0xFF));
        } else {
            setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));
            setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset_BE(seg, woff + 2, (byte) (value >> 16 & 0xFF));
        }
        woff += 3;
        return this;
    }

    @Override
    public Buf setMedium(int woff, int value) {
        checkWrite(woff, 3);
        if (isBigEndian) {
            setByteAtOffset_BE(seg, woff, (byte) (value >> 16));
            setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset_BE(seg, woff + 2, (byte) (value & 0xFF));
        } else {
            setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));
            setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset_BE(seg, woff + 2, (byte) (value >> 16 & 0xFF));
        }
        return this;
    }

    @Override
    public Buf writeUnsignedMedium(int value) {
        checkWrite(woff, 3);
        if (isBigEndian) {
            setByteAtOffset_BE(seg, woff, (byte) (value >> 16));
            setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset_BE(seg, woff + 2, (byte) (value & 0xFF));
        } else {
            setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));
            setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset_BE(seg, woff + 2, (byte) (value >> 16 & 0xFF));
        }
        woff += 3;
        return this;
    }

    @Override
    public Buf setUnsignedMedium(int woff, int value) {
        checkWrite(woff, 3);
        if (isBigEndian) {
            setByteAtOffset_BE(seg, woff, (byte) (value >> 16));
            setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset_BE(seg, woff + 2, (byte) (value & 0xFF));
        } else {
            setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));
            setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
            setByteAtOffset_BE(seg, woff + 2, (byte) (value >> 16 & 0xFF));
        }
        return this;
    }

    @Override
    public int readInt() {
        checkRead(roff, Integer.BYTES);
        int value = isBigEndian? getIntAtOffset_BE(seg, roff) : getIntAtOffset_LE(seg, roff);
        roff += Integer.BYTES;
        return value;
    }

    @Override
    public int getInt(int roff) {
        checkRead(roff, Integer.BYTES);
        return isBigEndian? getIntAtOffset_BE(seg, roff) : getIntAtOffset_LE(seg, roff);
    }

    @Override
    public long readUnsignedInt() {
        checkRead(roff, Integer.BYTES);
        long value = (isBigEndian? getIntAtOffset_BE(seg, roff) : getIntAtOffset_LE(seg, roff)) & 0xFFFFFFFFL;
        roff += Integer.BYTES;
        return value;
    }

    @Override
    public long getUnsignedInt(int roff) {
        checkRead(roff, Integer.BYTES);
        return (isBigEndian? getIntAtOffset_BE(seg, roff) : getIntAtOffset_LE(seg, roff)) & 0xFFFFFFFFL;
    }

    @Override
    public Buf writeInt(int value) {
        if (isBigEndian) {
            setIntAtOffset_BE(seg, woff, value);
        } else {
            setIntAtOffset_LE(seg, woff, value);
        }
        woff += Integer.BYTES;
        return this;
    }

    @Override
    public Buf setInt(int woff, int value) {
        if (isBigEndian) {
            setIntAtOffset_BE(seg, woff, value);
        } else {
            setIntAtOffset_LE(seg, woff, value);
        }
        return this;
    }

    @Override
    public Buf writeUnsignedInt(long value) {
        if (isBigEndian) {
            setIntAtOffset_BE(seg, woff, (int) (value & 0xFFFFFFFFL));
        } else {
            setIntAtOffset_LE(seg, woff, (int) (value & 0xFFFFFFFFL));
        }
        woff += Integer.BYTES;
        return this;
    }

    @Override
    public Buf setUnsignedInt(int woff, long value) {
        if (isBigEndian) {
            setIntAtOffset_BE(seg, woff, (int) (value & 0xFFFFFFFFL));
        } else {
            setIntAtOffset_LE(seg, woff, (int) (value & 0xFFFFFFFFL));
        }
        return this;
    }

    @Override
    public float readFloat() {
        checkRead(roff, Float.BYTES);
        float value = isBigEndian? getFloatAtOffset_BE(seg, roff) : getFloatAtOffset_LE(seg, roff);
        roff += Float.BYTES;
        return value;
    }

    @Override
    public float getFloat(int roff) {
        checkRead(roff, Float.BYTES);
        return isBigEndian? getFloatAtOffset_BE(seg, roff) : getFloatAtOffset_LE(seg, roff);
    }

    @Override
    public Buf writeFloat(float value) {
        if (isBigEndian) {
            setFloatAtOffset_BE(seg, woff, value);
        } else {
            setFloatAtOffset_LE(seg, woff, value);
        }
        woff += Float.BYTES;
        return this;
    }

    @Override
    public Buf setFloat(int woff, float value) {
        if (isBigEndian) {
            setFloatAtOffset_BE(seg, woff, value);
        } else {
            setFloatAtOffset_LE(seg, woff, value);
        }
        return this;
    }

    @Override
    public long readLong() {
        checkRead(roff, Long.BYTES);
        long value = isBigEndian? getLongAtOffset_BE(seg, roff) : getLongAtOffset_LE(seg, roff);
        roff += Long.BYTES;
        return value;
    }

    @Override
    public long getLong(int roff) {
        checkRead(roff, Long.BYTES);
        return isBigEndian? getLongAtOffset_BE(seg, roff) : getLongAtOffset_LE(seg, roff);
    }

    @Override
    public Buf writeLong(long value) {
        if (isBigEndian) {
            setLongAtOffset_BE(seg, woff, value);
        } else {
            setLongAtOffset_LE(seg, woff, value);
        }
        woff += Long.BYTES;
        return this;
    }

    @Override
    public Buf setLong(int woff, long value) {
        if (isBigEndian) {
            setLongAtOffset_BE(seg, woff, value);
        } else {
            setLongAtOffset_LE(seg, woff, value);
        }
        return this;
    }

    @Override
    public double readDouble() {
        checkRead(roff, Double.BYTES);
        double value = isBigEndian? getDoubleAtOffset_BE(seg, roff) : getDoubleAtOffset_LE(seg, roff);
        roff += Double.BYTES;
        return value;
    }

    @Override
    public double getDouble(int roff) {
        checkRead(roff, Double.BYTES);
        return isBigEndian? getDoubleAtOffset_BE(seg, roff) : getDoubleAtOffset_LE(seg, roff);
    }

    @Override
    public Buf writeDouble(double value) {
        if (isBigEndian) {
            setDoubleAtOffset_BE(seg, woff, value);
        } else {
            setDoubleAtOffset_LE(seg, woff, value);
        }
        woff += Double.BYTES;
        return this;
    }

    @Override
    public Buf setDouble(int woff, double value) {
        if (isBigEndian) {
            setDoubleAtOffset_BE(seg, woff, value);
        } else {
            setDoubleAtOffset_LE(seg, woff, value);
        }
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
                var newSegment = isConfined? transferSegment.handoff(Thread.currentThread()) : transferSegment;
                MemSegBuf copy = new MemSegBuf(newSegment, drop);
                copy.isBigEndian = outer.isBigEndian;
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
    public boolean isSendable() {
        return isSendable && super.isSendable();
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
}