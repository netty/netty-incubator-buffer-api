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

import jdk.incubator.foreign.MemorySegment;

import java.nio.ByteOrder;

import static jdk.incubator.foreign.MemoryAccess.getByteAtOffset_BE;
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

class BBuf extends RcSupport<Buf, BBuf> implements Buf {
    static final Drop<BBuf> SEGMENT_CLOSE = buf -> buf.seg.close();
    final MemorySegment seg;
    private boolean isBigEndian;
    private int roff;
    private int woff;

    BBuf(MemorySegment segment, Drop<BBuf> drop) {
        super(drop);
        seg = segment;
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
    public int readerIndex() {
        return roff;
    }

    @Override
    public BBuf readerIndex(int index) {
        checkRead(index, 0);
        roff = index;
        return this;
    }

    @Override
    public int writerIndex() {
        return woff;
    }

    @Override
    public BBuf writerIndex(int index) {
        checkWrite(index, 0);
        woff = index;
        return this;
    }

    @Override
    public int readableBytes() {
        return writerIndex() - readerIndex();
    }

    @Override
    public int writableBytes() {
        return capacity() - writerIndex();
    }

    @Override
    public Buf fill(byte value) {
        seg.fill(value);
        return this;
    }

    @Override
    public byte[] copy() {
        return seg.toByteArray();
    }

    @Override
    public long getNativeAddress() {
        try {
            return seg.address().toRawLongValue();
        } catch (UnsupportedOperationException e) {
            return 0; // This is a heap segment.
        }
    }

    // ### CODEGEN START primitive accessors implementation
    // <editor-fold defaultstate="collapsed" desc="Generated primitive accessors implementation.">

    @Override
    public byte readByte() {
        checkRead(roff, Byte.BYTES);
        byte value = getByteAtOffset_BE(seg, roff);
        roff += Byte.BYTES;
        return value;
    }

    @Override
    public byte readByte(int roff) {
        checkRead(roff, Byte.BYTES);
        return getByteAtOffset_BE(seg, roff);
    }

    @Override
    public int readUnsignedByte() {
        checkRead(roff, Byte.BYTES);
        int value = getByteAtOffset_BE(seg, roff) & 0xFF;
        roff += Byte.BYTES;
        return value;
    }

    @Override
    public int readUnsignedByte(int roff) {
        checkRead(roff, Byte.BYTES);
        return getByteAtOffset_BE(seg, roff) & 0xFF;
    }

    @Override
    public Buf writeByte(byte value) {
        setByteAtOffset_BE(seg, woff, value);
        woff += Byte.BYTES;
        return this;
    }

    @Override
    public Buf writeByte(int woff, byte value) {
        setByteAtOffset_BE(seg, woff, value);
        return this;
    }

    @Override
    public Buf writeUnsignedByte(int value) {
        setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));
        woff += Byte.BYTES;
        return this;
    }

    @Override
    public Buf writeUnsignedByte(int woff, int value) {
        setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));
        return this;
    }

    @Override
    public char readChar() {
        checkRead(roff, 2);
        char value = getCharAtOffset_BE(seg, roff);
        roff += 2;
        return value;
    }

    @Override
    public char readChar(int roff) {
        checkRead(roff, 2);
        return getCharAtOffset_BE(seg, roff);
    }

    @Override
    public char readCharLE() {
        checkRead(roff, 2);
        char value = getCharAtOffset_LE(seg, roff);
        roff += 2;
        return value;
    }

    @Override
    public char readCharLE(int roff) {
        checkRead(roff, 2);
        return getCharAtOffset_LE(seg, roff);
    }

    @Override
    public Buf writeChar(char value) {
        setCharAtOffset_BE(seg, woff, value);
        woff += 2;
        return this;
    }

    @Override
    public Buf writeChar(int woff, char value) {
        setCharAtOffset_BE(seg, woff, value);
        return this;
    }

    @Override
    public Buf writeCharLE(char value) {
        setCharAtOffset_LE(seg, woff, value);
        woff += 2;
        return this;
    }

    @Override
    public Buf writeCharLE(int woff, char value) {
        setCharAtOffset_LE(seg, woff, value);
        return this;
    }

    @Override
    public short readShort() {
        checkRead(roff, Short.BYTES);
        short value = getShortAtOffset_BE(seg, roff);
        roff += Short.BYTES;
        return value;
    }

    @Override
    public short readShort(int roff) {
        checkRead(roff, Short.BYTES);
        return getShortAtOffset_BE(seg, roff);
    }

    @Override
    public short readShortLE() {
        checkRead(roff, Short.BYTES);
        short value = getShortAtOffset_LE(seg, roff);
        roff += Short.BYTES;
        return value;
    }

    @Override
    public short readShortLE(int roff) {
        checkRead(roff, Short.BYTES);
        return getShortAtOffset_LE(seg, roff);
    }

    @Override
    public int readUnsignedShort() {
        checkRead(roff, Short.BYTES);
        int value = getShortAtOffset_BE(seg, roff) & 0xFFFF;
        roff += Short.BYTES;
        return value;
    }

    @Override
    public int readUnsignedShort(int roff) {
        checkRead(roff, Short.BYTES);
        return getShortAtOffset_BE(seg, roff) & 0xFFFF;
    }

    @Override
    public int readUnsignedShortLE() {
        checkRead(roff, Short.BYTES);
        int value = getShortAtOffset_LE(seg, roff) & 0xFFFF;
        roff += Short.BYTES;
        return value;
    }

    @Override
    public int readUnsignedShortLE(int roff) {
        checkRead(roff, Short.BYTES);
        return getShortAtOffset_LE(seg, roff) & 0xFFFF;
    }

    @Override
    public Buf writeShort(short value) {
        setShortAtOffset_BE(seg, woff, value);
        woff += Short.BYTES;
        return this;
    }

    @Override
    public Buf writeShort(int woff, short value) {
        setShortAtOffset_BE(seg, woff, value);
        return this;
    }

    @Override
    public Buf writeShortLE(short value) {
        setShortAtOffset_LE(seg, woff, value);
        woff += Short.BYTES;
        return this;
    }

    @Override
    public Buf writeShortLE(int woff, short value) {
        setShortAtOffset_LE(seg, woff, value);
        return this;
    }

    @Override
    public Buf writeUnsignedShort(int value) {
        setShortAtOffset_BE(seg, woff, (short) (value & 0xFFFF));
        woff += Short.BYTES;
        return this;
    }

    @Override
    public Buf writeUnsignedShort(int woff, int value) {
        setShortAtOffset_BE(seg, woff, (short) (value & 0xFFFF));
        return this;
    }

    @Override
    public Buf writeUnsignedShortLE(int value) {
        setShortAtOffset_LE(seg, woff, (short) (value & 0xFFFF));
        woff += Short.BYTES;
        return this;
    }

    @Override
    public Buf writeUnsignedShortLE(int woff, int value) {
        setShortAtOffset_LE(seg, woff, (short) (value & 0xFFFF));
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
    public int readMedium(int roff) {
        checkRead(roff, 3);
        return getByteAtOffset_BE(seg, roff) << 16 |
               (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
               getByteAtOffset_BE(seg, roff + 2) & 0xFF;
    }

    @Override
    public int readMediumLE() {
        checkRead(roff, 3);
        int value = getByteAtOffset_BE(seg, roff) & 0xFF |
                    (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                    getByteAtOffset_BE(seg, roff + 2) << 16;
        roff += 3;
        return value;
    }

    @Override
    public int readMediumLE(int roff) {
        checkRead(roff, 3);
        return getByteAtOffset_BE(seg, roff) & 0xFF |
                    (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                    getByteAtOffset_BE(seg, roff + 2) << 16;
    }

    @Override
    public int readUnsignedMedium() {
        checkRead(roff, 3);
        int value = (getByteAtOffset_BE(seg, roff) << 16 |
                    (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                    getByteAtOffset_BE(seg, roff + 2) & 0xFF) & 0xFFFFFF;
        roff += 3;
        return value;
    }

    @Override
    public int readUnsignedMedium(int roff) {
        checkRead(roff, 3);
        return (getByteAtOffset_BE(seg, roff) << 16 |
                    (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                    getByteAtOffset_BE(seg, roff + 2) & 0xFF) & 0xFFFFFF;
    }

    @Override
    public int readUnsignedMediumLE() {
        checkRead(roff, 3);
        int value = (getByteAtOffset_BE(seg, roff) & 0xFF |
                    (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                    getByteAtOffset_BE(seg, roff + 2) << 16) & 0xFFFFFF;
        roff += 3;
        return value;
    }

    @Override
    public int readUnsignedMediumLE(int roff) {
        checkRead(roff, 3);
        return (getByteAtOffset_BE(seg, roff) & 0xFF |
                    (getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |
                    getByteAtOffset_BE(seg, roff + 2) << 16) & 0xFFFFFF;
    }

    @Override
    public Buf writeMedium(int value) {
        checkWrite(woff, 3);
        setByteAtOffset_BE(seg, woff, (byte) (value >> 16));
        setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
        setByteAtOffset_BE(seg, woff + 2, (byte) (value & 0xFF));
        woff += 3;
        return this;
    }

    @Override
    public Buf writeMedium(int woff, int value) {
        checkWrite(woff, 3);
        setByteAtOffset_BE(seg, woff, (byte) (value >> 16));
        setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
        setByteAtOffset_BE(seg, woff + 2, (byte) (value & 0xFF));
        return this;
    }

    @Override
    public Buf writeMediumLE(int value) {
        checkWrite(woff, 3);
        setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));
        setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
        setByteAtOffset_BE(seg, woff + 2, (byte) (value >> 16 & 0xFF));
        woff += 3;
        return this;
    }

    @Override
    public Buf writeMediumLE(int woff, int value) {
        checkWrite(woff, 3);
        setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));
        setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
        setByteAtOffset_BE(seg, woff + 2, (byte) (value >> 16 & 0xFF));
        return this;
    }

    @Override
    public Buf writeUnsignedMedium(int value) {
        checkWrite(woff, 3);
        setByteAtOffset_BE(seg, woff, (byte) (value >> 16));
        setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
        setByteAtOffset_BE(seg, woff + 2, (byte) (value & 0xFF));
        woff += 3;
        return this;
    }

    @Override
    public Buf writeUnsignedMedium(int woff, int value) {
        checkWrite(woff, 3);
        setByteAtOffset_BE(seg, woff, (byte) (value >> 16));
        setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
        setByteAtOffset_BE(seg, woff + 2, (byte) (value & 0xFF));
        return this;
    }

    @Override
    public Buf writeUnsignedMediumLE(int value) {
        checkWrite(woff, 3);
        setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));
        setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
        setByteAtOffset_BE(seg, woff + 2, (byte) (value >> 16 & 0xFF));
        woff += 3;
        return this;
    }

    @Override
    public Buf writeUnsignedMediumLE(int woff, int value) {
        checkWrite(woff, 3);
        setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));
        setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));
        setByteAtOffset_BE(seg, woff + 2, (byte) (value >> 16 & 0xFF));
        return this;
    }

    @Override
    public int readInt() {
        checkRead(roff, Integer.BYTES);
        int value = getIntAtOffset_BE(seg, roff);
        roff += Integer.BYTES;
        return value;
    }

    @Override
    public int readInt(int roff) {
        checkRead(roff, Integer.BYTES);
        return getIntAtOffset_BE(seg, roff);
    }

    @Override
    public int readIntLE() {
        checkRead(roff, Integer.BYTES);
        int value = getIntAtOffset_LE(seg, roff);
        roff += Integer.BYTES;
        return value;
    }

    @Override
    public int readIntLE(int roff) {
        checkRead(roff, Integer.BYTES);
        return getIntAtOffset_LE(seg, roff);
    }

    @Override
    public long readUnsignedInt() {
        checkRead(roff, Integer.BYTES);
        long value = getIntAtOffset_BE(seg, roff) & 0xFFFFFFFFL;
        roff += Integer.BYTES;
        return value;
    }

    @Override
    public long readUnsignedInt(int roff) {
        checkRead(roff, Integer.BYTES);
        return getIntAtOffset_BE(seg, roff) & 0xFFFFFFFFL;
    }

    @Override
    public long readUnsignedIntLE() {
        checkRead(roff, Integer.BYTES);
        long value = getIntAtOffset_LE(seg, roff) & 0xFFFFFFFFL;
        roff += Integer.BYTES;
        return value;
    }

    @Override
    public long readUnsignedIntLE(int roff) {
        checkRead(roff, Integer.BYTES);
        return getIntAtOffset_LE(seg, roff) & 0xFFFFFFFFL;
    }

    @Override
    public Buf writeInt(int value) {
        setIntAtOffset_BE(seg, woff, value);
        woff += Integer.BYTES;
        return this;
    }

    @Override
    public Buf writeInt(int woff, int value) {
        setIntAtOffset_BE(seg, woff, value);
        return this;
    }

    @Override
    public Buf writeIntLE(int value) {
        setIntAtOffset_LE(seg, woff, value);
        woff += Integer.BYTES;
        return this;
    }

    @Override
    public Buf writeIntLE(int woff, int value) {
        setIntAtOffset_LE(seg, woff, value);
        return this;
    }

    @Override
    public Buf writeUnsignedInt(long value) {
        setIntAtOffset_BE(seg, woff, (int) (value & 0xFFFFFFFFL));
        woff += Integer.BYTES;
        return this;
    }

    @Override
    public Buf writeUnsignedInt(int woff, long value) {
        setIntAtOffset_BE(seg, woff, (int) (value & 0xFFFFFFFFL));
        return this;
    }

    @Override
    public Buf writeUnsignedIntLE(long value) {
        setIntAtOffset_LE(seg, woff, (int) (value & 0xFFFFFFFFL));
        woff += Integer.BYTES;
        return this;
    }

    @Override
    public Buf writeUnsignedIntLE(int woff, long value) {
        setIntAtOffset_LE(seg, woff, (int) (value & 0xFFFFFFFFL));
        return this;
    }

    @Override
    public float readFloat() {
        checkRead(roff, Float.BYTES);
        float value = getFloatAtOffset_BE(seg, roff);
        roff += Float.BYTES;
        return value;
    }

    @Override
    public float readFloat(int roff) {
        checkRead(roff, Float.BYTES);
        return getFloatAtOffset_BE(seg, roff);
    }

    @Override
    public float readFloatLE() {
        checkRead(roff, Float.BYTES);
        float value = getFloatAtOffset_LE(seg, roff);
        roff += Float.BYTES;
        return value;
    }

    @Override
    public float readFloatLE(int roff) {
        checkRead(roff, Float.BYTES);
        return getFloatAtOffset_LE(seg, roff);
    }

    @Override
    public Buf writeFloat(float value) {
        setFloatAtOffset_BE(seg, woff, value);
        woff += Float.BYTES;
        return this;
    }

    @Override
    public Buf writeFloat(int woff, float value) {
        setFloatAtOffset_BE(seg, woff, value);
        return this;
    }

    @Override
    public Buf writeFloatLE(float value) {
        setFloatAtOffset_LE(seg, woff, value);
        woff += Float.BYTES;
        return this;
    }

    @Override
    public Buf writeFloatLE(int woff, float value) {
        setFloatAtOffset_LE(seg, woff, value);
        return this;
    }

    @Override
    public long readLong() {
        checkRead(roff, Long.BYTES);
        long value = getLongAtOffset_BE(seg, roff);
        roff += Long.BYTES;
        return value;
    }

    @Override
    public long readLong(int roff) {
        checkRead(roff, Long.BYTES);
        return getLongAtOffset_BE(seg, roff);
    }

    @Override
    public long readLongLE() {
        checkRead(roff, Long.BYTES);
        long value = getLongAtOffset_LE(seg, roff);
        roff += Long.BYTES;
        return value;
    }

    @Override
    public long readLongLE(int roff) {
        checkRead(roff, Long.BYTES);
        return getLongAtOffset_LE(seg, roff);
    }

    @Override
    public Buf writeLong(long value) {
        setLongAtOffset_BE(seg, woff, value);
        woff += Long.BYTES;
        return this;
    }

    @Override
    public Buf writeLong(int woff, long value) {
        setLongAtOffset_BE(seg, woff, value);
        return this;
    }

    @Override
    public Buf writeLongLE(long value) {
        setLongAtOffset_LE(seg, woff, value);
        woff += Long.BYTES;
        return this;
    }

    @Override
    public Buf writeLongLE(int woff, long value) {
        setLongAtOffset_LE(seg, woff, value);
        return this;
    }

    @Override
    public double readDouble() {
        checkRead(roff, Double.BYTES);
        double value = getDoubleAtOffset_BE(seg, roff);
        roff += Double.BYTES;
        return value;
    }

    @Override
    public double readDouble(int roff) {
        checkRead(roff, Double.BYTES);
        return getDoubleAtOffset_BE(seg, roff);
    }

    @Override
    public double readDoubleLE() {
        checkRead(roff, Double.BYTES);
        double value = getDoubleAtOffset_LE(seg, roff);
        roff += Double.BYTES;
        return value;
    }

    @Override
    public double readDoubleLE(int roff) {
        checkRead(roff, Double.BYTES);
        return getDoubleAtOffset_LE(seg, roff);
    }

    @Override
    public Buf writeDouble(double value) {
        setDoubleAtOffset_BE(seg, woff, value);
        woff += Double.BYTES;
        return this;
    }

    @Override
    public Buf writeDouble(int woff, double value) {
        setDoubleAtOffset_BE(seg, woff, value);
        return this;
    }

    @Override
    public Buf writeDoubleLE(double value) {
        setDoubleAtOffset_LE(seg, woff, value);
        woff += Double.BYTES;
        return this;
    }

    @Override
    public Buf writeDoubleLE(int woff, double value) {
        setDoubleAtOffset_LE(seg, woff, value);
        return this;
    }
    // </editor-fold>
    // ### CODEGEN END primitive accessors implementation

    @Override
    protected Owned<BBuf> prepareSend() {
        BBuf outer = this;
        boolean isConfined = seg.ownerThread() == null;
        MemorySegment transferSegment = isConfined? seg : seg.withOwnerThread(null);
        return new Owned<BBuf>() {
            @Override
            public BBuf transferOwnership(Thread recipient, Drop<BBuf> drop) {
                var newSegment = isConfined? transferSegment.withOwnerThread(recipient) : transferSegment;
                BBuf copy = new BBuf(newSegment, drop);
                copy.isBigEndian = outer.isBigEndian;
                copy.roff = outer.roff;
                copy.woff = outer.woff;
                return copy;
            }
        };
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