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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.NoSuchElementException;

final class CompositeBuf extends RcSupport<Buf, CompositeBuf> implements Buf {
    /**
     * The max array size is JVM implementation dependant, but most seem to settle on {@code Integer.MAX_VALUE - 8}.
     * We set the max composite buffer capacity to the same, since it would otherwise be impossible to create a
     * non-composite copy of the buffer.
     */
    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;
    private static final Drop<CompositeBuf> COMPOSITE_DROP = new Drop<CompositeBuf>() {
        @Override
        public void drop(CompositeBuf obj) {
            for (Buf buf : obj.bufs) {
                buf.close();
            }
        }
    };

    private final TornBufAccessors tornBufAccessors;
    private final boolean isSendable;
    private final Buf[] bufs;
    private final int[] offsets; // The offset, for the composite buffer, where each constituent buffer starts.
    private final int capacity;
    private int roff;
    private int woff;
    private int subOffset; // The next offset *within* a consituent buffer to read from or write to.

    CompositeBuf(Buf[] bufs) {
        this(true, bufs.clone(), COMPOSITE_DROP); // Clone prevents external modification of array.
    }

    private CompositeBuf(boolean isSendable, Buf[] bufs, Drop<CompositeBuf> drop) {
        super(drop);
        this.isSendable = isSendable;
        for (Buf buf : bufs) {
            buf.acquire();
        }
        if (bufs.length > 0) {
            ByteOrder targetOrder = bufs[0].order();
            for (Buf buf : bufs) {
                if (buf.order() != targetOrder) {
                    throw new IllegalArgumentException("Constituent buffers have inconsistent byte order.");
                }
            }
            boolean woffMidpoint = false;
            for (Buf buf : bufs) {
                if (buf.writableBytes() == 0) {
                    woff += buf.capacity();
                } else if (!woffMidpoint) {
                    woff += buf.writerIndex();
                    woffMidpoint = true;
                } else if (buf.writerIndex() != 0) {
                    throw new IllegalArgumentException(
                            "The given buffers cannot be composed because they have an unwritten gap: " +
                            Arrays.toString(bufs) + '.');
                }
            }
            boolean roffMidpoint = false;
            for (Buf buf : bufs) {
                if (buf.readableBytes() == 0 && buf.writableBytes() == 0) {
                    roff += buf.capacity();
                } else if (!roffMidpoint) {
                    roff += buf.readerIndex();
                    roffMidpoint = true;
                } else if (buf.readerIndex() != 0) {
                    throw new IllegalArgumentException(
                            "The given buffers cannot be composed because they have an unread gap: " +
                            Arrays.toString(bufs) + '.');
                }
            }
            assert roff <= woff:
                    "The given buffers place the read offset ahead of the write offset: " + Arrays.toString(bufs) + '.';
        }
        offsets = new int[bufs.length];
        long cap = 0;
        for (int i = 0; i < bufs.length; i++) {
            offsets[i] = (int) cap;
            cap += bufs[i].capacity();
        }
        if (cap > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                    "Combined size of the constituent buffers is too big. " +
                    "The maximum buffer capacity is " + MAX_CAPACITY + " (Interger.MAX_VALUE - 8), " +
                    "but the sum of the constituent buffer capacities was " + cap + '.');
        }
        capacity = (int) cap;
        this.bufs = bufs;
        tornBufAccessors = new TornBufAccessors(this);
    }

    @Override
    public Buf order(ByteOrder order) {
        for (Buf buf : bufs) {
            buf.order(order);
        }
        return this;
    }

    @Override
    public ByteOrder order() {
        return bufs[0].order();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public int readerIndex() {
        return roff;
    }

    @Override
    public Buf readerIndex(int index) {
        prepRead(index, 0);
        int indexLeft = index;
        for (Buf buf : bufs) {
            buf.readerIndex(Math.min(indexLeft, buf.capacity()));
            indexLeft = Math.max(0, indexLeft - buf.capacity());
        }
        roff = index;
        return this;
    }

    @Override
    public int writerIndex() {
        return woff;
    }

    @Override
    public Buf writerIndex(int index) {
        checkWriteBounds(index, 0);
        int indexLeft = index;
        for (Buf buf : bufs) {
            buf.writerIndex(Math.min(indexLeft, buf.capacity()));
            indexLeft = Math.max(0, indexLeft - buf.capacity());
        }
        woff = index;
        return this;
    }

    @Override
    public Buf fill(byte value) {
        for (Buf buf : bufs) {
            buf.fill(value);
        }
        return this;
    }

    @Override
    public byte[] copy() {
        var bytes = new byte[capacity];
        int base = 0;
        for (Buf buf : bufs) {
            var src = buf.copy();
            System.arraycopy(src, 0, bytes, base, src.length);
            base += src.length;
        }
        return bytes;
    }

    @Override
    public long getNativeAddress() {
        return 0;
    }

    @Override
    public Buf slice(int offset, int length) {
        checkWriteBounds(offset, length);
        if (offset < 0 || length < 0) {
            throw new IndexOutOfBoundsException(
                    "Offset and length cannot be negative, but offset was " +
                    offset + ", and length was " + length + '.');
        }
        Buf choice = (Buf) chooseBuffer(offset, 0);
        Buf[] slices = null;
        acquire(); // Increase reference count of the original composite buffer.
        Drop<CompositeBuf> drop = obj -> {
            close(); // Decrement the reference count of the original composite buffer.
            COMPOSITE_DROP.drop(obj);
        };

        try {
            if (length > 0) {
                slices = new Buf[bufs.length];
                int off = subOffset;
                int cap = length;
                int i;
                for (i = searchOffsets(offset); cap > 0; i++) {
                    var buf = bufs[i];
                    int avail = buf.capacity() - off;
                    slices[i] = buf.slice(off, Math.min(cap, avail));
                    cap -= avail;
                    off = 0;
                }
                slices = Arrays.copyOf(slices, i);
            } else {
                // Specialize for length == 0, since we must slice from at least one constituent buffer.
                slices = new Buf[] { choice.slice(subOffset, 0) };
            }

            return new CompositeBuf(false, slices, drop).writerIndex(length);
        } catch (Throwable throwable) {
            // We called acquire prior to the try-clause. We need to undo that if we're not creating a composite buffer:
            close();
            throw throwable;
        } finally {
            if (slices != null) {
                for (Buf slice : slices) {
                    if (slice != null) {
                        slice.close(); // Ownership now transfers to the composite buffer.
                    }
                }
            }
        }
    }

    @Override
    public void copyInto(int srcPos, byte[] dest, int destPos, int length) {
        copyInto(srcPos, (b, s, d, l) -> b.copyInto(s, dest, d, l), destPos, length);
    }

    @Override
    public void copyInto(int srcPos, ByteBuffer dest, int destPos, int length) {
        copyInto(srcPos, (b, s, d, l) -> b.copyInto(s, dest, d, l), destPos, length);
    }

    @Override
    public void copyInto(int srcPos, Buf dest, int destPos, int length) {
        if (length < 0) {
            throw new IndexOutOfBoundsException("Length cannot be negative: " + length + '.');
        }
        if (srcPos < 0) {
            throw indexOutOfBounds(srcPos);
        }
        if (srcPos + length > capacity) {
            throw indexOutOfBounds(srcPos + length);
        }
        // todo optimise by doing bulk copy via consituent buffers
        for (int i = length - 1; i >= 0; i--) { // Iterate in reverse to account for src and dest buffer overlap.
            dest.writeByte(destPos + i, readByte(srcPos + i));
        }
    }

    @Override
    public ByteIterator iterate(int fromOffset, int length) {
        int startBufferIndex = searchOffsets(fromOffset);
        int off = fromOffset - offsets[startBufferIndex];
        Buf startBuf = bufs[startBufferIndex];
        ByteIterator startIterator = startBuf.iterate(off, Math.min(startBuf.capacity() - off, length));
        return new ByteIterator() {
            int index = fromOffset;
            final int end = fromOffset + length;
            int bufferIndex = startBufferIndex;
            ByteIterator itr = startIterator;

            @Override
            public boolean hasNextLong() {
                return bytesLeft() >= Long.BYTES;
            }

            @Override
            public long nextLong() {
                if (itr.hasNextLong()) {
                    long val = itr.nextLong();
                    index += Long.BYTES;
                    return val;
                }
                if (!hasNextLong()) {
                    throw new NoSuchElementException();
                }
                return nextLongFromBytes(); // Leave index increments to 'nextByte'
            }

            private long nextLongFromBytes() {
                long val = 0;
                for (int i = 0; i < 8; i++) {
                    val <<= 8;
                    val |= nextByte();
                }
                return val;
            }

            @Override
            public boolean hasNextByte() {
                return index < end;
            }

            @Override
            public byte nextByte() {
                if (itr.hasNextByte()) {
                    byte val = itr.nextByte();
                    index++;
                    return val;
                }
                if (!hasNextByte()) {
                    throw new NoSuchElementException();
                }
                bufferIndex++;
                Buf nextBuf = bufs[bufferIndex];
                itr = nextBuf.iterate(0, Math.min(nextBuf.capacity(), bytesLeft()));
                byte val = itr.nextByte();
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

    private void copyInto(int srcPos, CopyInto dest, int destPos, int length) {
        if (length < 0) {
            throw new IndexOutOfBoundsException("Length cannot be negative: " + length + '.');
        }
        if (srcPos < 0) {
            throw indexOutOfBounds(srcPos);
        }
        if (srcPos + length > capacity) {
            throw indexOutOfBounds(srcPos + length);
        }
        while (length > 0) {
            var buf = (Buf) chooseBuffer(srcPos, 0);
            int toCopy = buf.capacity() - subOffset;
            dest.copyInto(buf, subOffset, destPos, toCopy);
            srcPos += toCopy;
            destPos += toCopy;
            length -= toCopy;
        }
    }

    @FunctionalInterface
    private interface CopyInto {
        void copyInto(Buf src, int srcPos, int destPos, int length);
    }

    // <editor-fold defaultstate="collapsed" desc="Primitive accessors.">
    @Override
    public byte readByte() {
        return prepRead(Byte.BYTES).readByte();
    }

    @Override
    public byte readByte(int roff) {
        return prepRead(roff, Byte.BYTES).readByte(subOffset);
    }

    @Override
    public int readUnsignedByte() {
        return prepRead(Byte.BYTES).readUnsignedByte();
    }

    @Override
    public int readUnsignedByte(int roff) {
        return prepRead(roff, Byte.BYTES).readUnsignedByte(subOffset);
    }

    @Override
    public Buf writeByte(byte value) {
        prepWrite(Byte.BYTES).writeByte(value);
        return this;
    }

    @Override
    public Buf writeByte(int woff, byte value) {
        prepWrite(woff, Byte.BYTES).writeByte(subOffset, value);
        return this;
    }

    @Override
    public Buf writeUnsignedByte(int value) {
        prepWrite(Byte.BYTES).writeUnsignedByte(value);
        return this;
    }

    @Override
    public Buf writeUnsignedByte(int woff, int value) {
        prepWrite(woff, Byte.BYTES).writeUnsignedByte(subOffset, value);
        return this;
    }

    @Override
    public char readChar() {
        return prepRead(2).readChar();
    }

    @Override
    public char readChar(int roff) {
        return prepRead(roff, 2).readChar(subOffset);
    }

    @Override
    public Buf writeChar(char value) {
        prepWrite(2).writeChar(value);
        return this;
    }

    @Override
    public Buf writeChar(int woff, char value) {
        prepWrite(woff, 2).writeChar(subOffset, value);
        return this;
    }

    @Override
    public short readShort() {
        return prepRead(Short.BYTES).readShort();
    }

    @Override
    public short readShort(int roff) {
        return prepRead(roff, Short.BYTES).readShort(subOffset);
    }

    @Override
    public int readUnsignedShort() {
        return prepRead(Short.BYTES).readShort();
    }

    @Override
    public int readUnsignedShort(int roff) {
        return prepRead(roff, Short.BYTES).readUnsignedShort(subOffset);
    }

    @Override
    public Buf writeShort(short value) {
        prepWrite(Short.BYTES).writeShort(value);
        return this;
    }

    @Override
    public Buf writeShort(int woff, short value) {
        prepWrite(woff, Short.BYTES).writeShort(subOffset, value);
        return this;
    }

    @Override
    public Buf writeUnsignedShort(int value) {
        prepWrite(Short.BYTES).writeUnsignedShort(value);
        return this;
    }

    @Override
    public Buf writeUnsignedShort(int woff, int value) {
        prepWrite(woff, Short.BYTES).writeUnsignedShort(subOffset, value);
        return this;
    }

    @Override
    public int readMedium() {
        return prepRead(3).readMedium();
    }

    @Override
    public int readMedium(int roff) {
        return prepRead(roff, 3).readMedium(subOffset);
    }

    @Override
    public int readUnsignedMedium() {
        return prepRead(3).readMedium();
    }

    @Override
    public int readUnsignedMedium(int roff) {
        return prepRead(roff, 3).readMedium(subOffset);
    }

    @Override
    public Buf writeMedium(int value) {
        prepWrite(3).writeMedium(value);
        return this;
    }

    @Override
    public Buf writeMedium(int woff, int value) {
        prepWrite(woff, 3).writeMedium(subOffset, value);
        return this;
    }

    @Override
    public Buf writeUnsignedMedium(int value) {
        prepWrite(3).writeUnsignedMedium(value);
        return this;
    }

    @Override
    public Buf writeUnsignedMedium(int woff, int value) {
        prepWrite(woff, 3).writeUnsignedMedium(subOffset, value);
        return this;
    }

    @Override
    public int readInt() {
        return prepRead(Integer.BYTES).readInt();
    }

    @Override
    public int readInt(int roff) {
        return prepRead(roff, Integer.BYTES).readInt(subOffset);
    }

    @Override
    public long readUnsignedInt() {
        return prepRead(Integer.BYTES).readUnsignedInt();
    }

    @Override
    public long readUnsignedInt(int roff) {
        return prepRead(roff, Integer.BYTES).readUnsignedInt(subOffset);
    }

    @Override
    public Buf writeInt(int value) {
        prepWrite(Integer.BYTES).writeInt(value);
        return this;
    }

    @Override
    public Buf writeInt(int woff, int value) {
        prepWrite(woff, Integer.BYTES).writeInt(subOffset, value);
        return this;
    }

    @Override
    public Buf writeUnsignedInt(long value) {
        prepWrite(Integer.BYTES).writeUnsignedInt(value);
        return this;
    }

    @Override
    public Buf writeUnsignedInt(int woff, long value) {
        prepWrite(woff, Integer.BYTES).writeUnsignedInt(subOffset, value);
        return this;
    }

    @Override
    public float readFloat() {
        return prepRead(Float.BYTES).readFloat();
    }

    @Override
    public float readFloat(int roff) {
        return prepRead(roff, Float.BYTES).readFloat(subOffset);
    }

    @Override
    public Buf writeFloat(float value) {
        prepWrite(Float.BYTES).writeFloat(value);
        return this;
    }

    @Override
    public Buf writeFloat(int woff, float value) {
        prepWrite(woff, Float.BYTES).writeFloat(subOffset, value);
        return this;
    }

    @Override
    public long readLong() {
        return prepRead(Long.BYTES).readLong();
    }

    @Override
    public long readLong(int roff) {
        return prepRead(roff, Long.BYTES).readLong(subOffset);
    }

    @Override
    public Buf writeLong(long value) {
        prepWrite(Long.BYTES).writeLong(value);
        return this;
    }

    @Override
    public Buf writeLong(int woff, long value) {
        prepWrite(woff, Long.BYTES).writeLong(subOffset, value);
        return this;
    }

    @Override
    public double readDouble() {
        return prepRead(Double.BYTES).readDouble();
    }

    @Override
    public double readDouble(int roff) {
        return prepRead(roff, Double.BYTES).readDouble(subOffset);
    }

    @Override
    public Buf writeDouble(double value) {
        prepWrite(Double.BYTES).writeDouble(value);
        return this;
    }

    @Override
    public Buf writeDouble(int woff, double value) {
        prepWrite(woff, Double.BYTES).writeDouble(subOffset, value);
        return this;
    }
    // </editor-fold>

    @Override
    protected Owned<CompositeBuf> prepareSend() {
        @SuppressWarnings("unchecked")
        Send<Buf>[] sends = new Send[bufs.length];
        try {
            for (int i = 0; i < bufs.length; i++) {
                sends[i] = bufs[i].send();
            }
        } catch (Throwable throwable) {
            // Repair our bufs array.
            for (int i = 0; i < sends.length; i++) {
                if (sends[i] != null) {
                    try {
                        bufs[i] = sends[i].receive();
                    } catch (Exception e) {
                        throwable.addSuppressed(e);
                    }
                }
            }
            throw throwable;
        }
        return new Owned<CompositeBuf>() {
            @Override
            public CompositeBuf transferOwnership(Drop<CompositeBuf> drop) {
                Buf[] received = new Buf[sends.length];
                for (int i = 0; i < sends.length; i++) {
                    received[i] = sends[i].receive();
                }
                var composite = new CompositeBuf(true, received, drop);
                drop.accept(composite);
                return composite;
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

    long readPassThrough() {
        var buf = choosePassThroughBuffer(subOffset++);
        assert buf != tornBufAccessors: "Recursive call to torn buffer.";
        return buf.readUnsignedByte();
    }

    void writePassThrough(int value) {
        var buf = choosePassThroughBuffer(subOffset++);
        assert buf != tornBufAccessors: "Recursive call to torn buffer.";
        buf.writeUnsignedByte(value);
    }

    long readPassThrough(int roff) {
        var buf = chooseBuffer(roff, 1);
        assert buf != tornBufAccessors: "Recursive call to torn buffer.";
        return buf.readUnsignedByte(subOffset);
    }

    void writePassThrough(int woff, int value) {
        var buf = chooseBuffer(woff, 1);
        assert buf != tornBufAccessors: "Recursive call to torn buffer.";
        buf.writeUnsignedByte(subOffset, value);
    }

    private BufAccessors prepRead(int size) {
        var buf = prepRead(roff, size);
        roff += size;
        return buf;
    }

    private BufAccessors prepRead(int index, int size) {
        checkReadBounds(index, size);
        return chooseBuffer(index, size);
    }

    private void checkReadBounds(int index, int size) {
        if (index < 0 || woff < index + size) {
            throw indexOutOfBounds(index);
        }
    }

    private BufAccessors prepWrite(int size) {
        var buf = prepWrite(woff, size);
        woff += size;
        return buf;
    }

    private BufAccessors prepWrite(int index, int size) {
        checkWriteBounds(index, size);
        return chooseBuffer(index, size);
    }

    private void checkWriteBounds(int index, int size) {
        if (index < 0 || capacity < index + size) {
            throw indexOutOfBounds(index);
        }
    }

    private IndexOutOfBoundsException indexOutOfBounds(int index) {
        return new IndexOutOfBoundsException(
                "Index " + index + " is out of bounds: [read 0 to " + woff + ", write 0 to " +
                (capacity - 1) + "].");
    }

    private BufAccessors chooseBuffer(int index, int size) {
        int i = searchOffsets(index);
        if (i == bufs.length) {
            // This happens when the read/write offsets are parked 1 byte beyond the end of the buffer.
            // In that case it should not matter what buffer is returned, because it shouldn't be used anyway.
            return null;
        }
        int off = index - offsets[i];
        Buf candidate = bufs[i];
        if (off + size <= candidate.capacity()) {
            subOffset = off;
            return candidate;
        }
        subOffset = index;
        return tornBufAccessors;
    }

    private BufAccessors choosePassThroughBuffer(int index) {
        int i = searchOffsets(index);
        return bufs[i];
    }

    private int searchOffsets(int index) {
        int i = Arrays.binarySearch(offsets, index);
        return i < 0? -(i+2) : i;
    }

    // <editor-fold defaultstate="collapsed" desc="Torn buffer access.">
    private static final class TornBufAccessors implements BufAccessors {
        private final CompositeBuf buf;

        private TornBufAccessors(CompositeBuf buf) {
            this.buf = buf;
        }

        @Override
        public byte readByte() {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public byte readByte(int roff) {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public int readUnsignedByte() {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public int readUnsignedByte(int roff) {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public Buf writeByte(byte value) {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public Buf writeByte(int woff, byte value) {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public Buf writeUnsignedByte(int value) {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public Buf writeUnsignedByte(int woff, int value) {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public char readChar() {
            if (bigEndian()) {
                return (char) (read() << 8 | read());
            } else {
                return (char) (read() | read() << 8);
            }
        }

        @Override
        public char readChar(int roff) {
            if (bigEndian()) {
                return (char) (read(roff) << 8 | read(roff + 1));
            } else {
                return (char) (read(roff) | read(roff + 1) << 8);
            }
        }

        @Override
        public Buf writeChar(char value) {
            if (bigEndian()) {
                write(value >>> 8);
                write(value & 0xFF);
            } else {
                write(value & 0xFF);
                write(value >>> 8);
            }
            return buf;
        }

        @Override
        public Buf writeChar(int woff, char value) {
            if (bigEndian()) {
                write(woff, value >>> 8);
                write(woff + 1, value & 0xFF);
            } else {
                write(woff, value & 0xFF);
                write(woff + 1, value >>> 8);
            }
            return buf;
        }

        @Override
        public short readShort() {
            if (bigEndian()) {
                return (short) (read() << 8 | read());
            } else {
                return (short) (read() | read() << 8);
            }
        }

        @Override
        public short readShort(int roff) {
            if (bigEndian()) {
                return (short) (read(roff) << 8 | read(roff + 1));
            } else {
                return (short) (read(roff) | read(roff + 1) << 8);
            }
        }

        @Override
        public int readUnsignedShort() {
            if (bigEndian()) {
                return (int) (read() << 8 | read()) & 0xFFFF;
            } else {
                return (int) (read() | read() << 8) & 0xFFFF;
            }
        }

        @Override
        public int readUnsignedShort(int roff) {
            if (bigEndian()) {
                return (int) (read(roff) << 8 | read(roff + 1)) & 0xFFFF;
            } else {
                return (int) (read(roff) | read(roff + 1) << 8) & 0xFFFF;
            }
        }

        @Override
        public Buf writeShort(short value) {
            if (bigEndian()) {
                write(value >>> 8);
                write(value & 0xFF);
            } else {
                write(value & 0xFF);
                write(value >>> 8);
            }
            return buf;
        }

        @Override
        public Buf writeShort(int woff, short value) {
            if (bigEndian()) {
                write(woff, value >>> 8);
                write(woff + 1, value & 0xFF);
            } else {
                write(woff, value & 0xFF);
                write(woff + 1, value >>> 8);
            }
            return buf;
        }

        @Override
        public Buf writeUnsignedShort(int value) {
            if (bigEndian()) {
                write(value >>> 8);
                write(value & 0xFF);
            } else {
                write(value & 0xFF);
                write(value >>> 8);
            }
            return buf;
        }

        @Override
        public Buf writeUnsignedShort(int woff, int value) {
            if (bigEndian()) {
                write(woff, value >>> 8);
                write(woff + 1, value & 0xFF);
            } else {
                write(woff, value & 0xFF);
                write(woff + 1, value >>> 8);
            }
            return buf;
        }

        @Override
        public int readMedium() {
            if (bigEndian()) {
                return (int) (read() << 16 | read() << 8 | read());
            } else {
                return (int) (read() | read() << 8 | read() << 16);
            }
        }

        @Override
        public int readMedium(int roff) {
            if (bigEndian()) {
                return (int) (read(roff) << 16 | read(roff + 1) << 8 | read(roff + 2));
            } else {
                return (int) (read(roff) | read(roff + 1) << 8 | read(roff + 2) << 16);
            }
        }

        @Override
        public int readUnsignedMedium() {
            if (bigEndian()) {
                return (int) (read() << 16 | read() << 8 | read()) & 0xFFFFFF;
            } else {
                return (int) (read() | read() << 8 | read() << 16) & 0xFFFFFF;
            }
        }

        @Override
        public int readUnsignedMedium(int roff) {
            if (bigEndian()) {
                return (int) (read(roff) << 16 | read(roff + 1) << 8 | read(roff + 2)) & 0xFFFFFF;
            } else {
                return (int) (read(roff) | read(roff + 1) << 8 | read(roff + 2) << 16) & 0xFFFFFF;
            }
        }

        @Override
        public Buf writeMedium(int value) {
            if (bigEndian()) {
                write(value >>> 16);
                write(value >>> 8 & 0xFF);
                write(value & 0xFF);
            } else {
                write(value & 0xFF);
                write(value >>> 8 & 0xFF);
                write(value >>> 16);
            }
            return buf;
        }

        @Override
        public Buf writeMedium(int woff, int value) {
            if (bigEndian()) {
                write(woff, value >>> 16);
                write(woff + 1, value >>> 8 & 0xFF);
                write(woff + 2, value & 0xFF);
            } else {
                write(woff, value & 0xFF);
                write(woff + 1, value >>> 8 & 0xFF);
                write(woff + 2, value >>> 16);
            }
            return buf;
        }

        @Override
        public Buf writeUnsignedMedium(int value) {
            if (bigEndian()) {
                write(value >>> 16);
                write(value >>> 8 & 0xFF);
                write(value & 0xFF);
            } else {
                write(value & 0xFF);
                write(value >>> 8 & 0xFF);
                write(value >>> 16);
            }
            return buf;
        }

        @Override
        public Buf writeUnsignedMedium(int woff, int value) {
            if (bigEndian()) {
                write(woff, value >>> 16);
                write(woff + 1, value >>> 8 & 0xFF);
                write(woff + 2, value & 0xFF);
            } else {
                write(woff, value & 0xFF);
                write(woff + 1, value >>> 8 & 0xFF);
                write(woff + 2, value >>> 16);
            }
            return buf;
        }

        @Override
        public int readInt() {
            if (bigEndian()) {
                return (int) (read() << 24 | read() << 16 | read() << 8 | read());
            } else {
                return (int) (read() | read() << 8 | read() << 16 | read() << 24);
            }
        }

        @Override
        public int readInt(int roff) {
            if (bigEndian()) {
                return (int) (read(roff) << 24 | read(roff + 1) << 16 | read(roff + 2) << 8 | read(roff + 3));
            } else {
                return (int) (read(roff) | read(roff + 1) << 8 | read(roff + 2) << 16 | read(roff + 3) << 24);
            }
        }

        @Override
        public long readUnsignedInt() {
            if (bigEndian()) {
                return (read() << 24 | read() << 16 | read() << 8 | read()) & 0xFFFFFFFFL;
            } else {
                return (read() | read() << 8 | read() << 16 | read() << 24) & 0xFFFFFFFFL;
            }
        }

        @Override
        public long readUnsignedInt(int roff) {
            if (bigEndian()) {
                return (read(roff) << 24 | read(roff + 1) << 16 | read(roff + 2) << 8 | read(roff + 3)) & 0xFFFFFFFFL;
            } else {
                return (read(roff) | read(roff + 1) << 8 | read(roff + 2) << 16 | read(roff + 3) << 24) & 0xFFFFFFFFL;
            }
        }

        @Override
        public Buf writeInt(int value) {
            if (bigEndian()) {
                write(value >>> 24);
                write(value >>> 16 & 0xFF);
                write(value >>> 8 & 0xFF);
                write(value & 0xFF);
            } else {
                write(value & 0xFF);
                write(value >>> 8 & 0xFF);
                write(value >>> 16 & 0xFF);
                write(value >>> 24);
            }
            return buf;
        }

        @Override
        public Buf writeInt(int woff, int value) {
            if (bigEndian()) {
                write(woff, value >>> 24);
                write(woff + 1, value >>> 16 & 0xFF);
                write(woff + 2, value >>> 8 & 0xFF);
                write(woff + 3, value & 0xFF);
            } else {
                write(woff, value & 0xFF);
                write(woff + 1, value >>> 8 & 0xFF);
                write(woff + 2, value >>> 16 & 0xFF);
                write(woff + 3, value >>> 24);
            }
            return buf;
        }

        @Override
        public Buf writeUnsignedInt(long value) {
            if (bigEndian()) {
                write((int) (value >>> 24));
                write((int) (value >>> 16 & 0xFF));
                write((int) (value >>> 8 & 0xFF));
                write((int) (value & 0xFF));
            } else {
                write((int) (value & 0xFF));
                write((int) (value >>> 8 & 0xFF));
                write((int) (value >>> 16 & 0xFF));
                write((int) (value >>> 24));
            }
            return buf;
        }

        @Override
        public Buf writeUnsignedInt(int woff, long value) {
            if (bigEndian()) {
                write(woff, (int) (value >>> 24));
                write(woff + 1, (int) (value >>> 16 & 0xFF));
                write(woff + 2, (int) (value >>> 8 & 0xFF));
                write(woff + 3, (int) (value & 0xFF));
            } else {
                write(woff, (int) (value & 0xFF));
                write(woff + 1, (int) (value >>> 8 & 0xFF));
                write(woff + 2, (int) (value >>> 16 & 0xFF));
                write(woff + 3, (int) (value >>> 24));
            }
            return buf;
        }

        @Override
        public float readFloat() {
            return Float.intBitsToFloat(readInt());
        }

        @Override
        public float readFloat(int roff) {
            return Float.intBitsToFloat(readInt(roff));
        }

        @Override
        public Buf writeFloat(float value) {
            return writeUnsignedInt(Float.floatToRawIntBits(value));
        }

        @Override
        public Buf writeFloat(int woff, float value) {
            return writeUnsignedInt(woff, Float.floatToRawIntBits(value));
        }

        @Override
        public long readLong() {
            if (bigEndian()) {
                return read() << 56 | read() << 48 | read() << 40 | read() << 32 |
                       read() << 24 | read() << 16 | read() << 8 | read();
            } else {
                return read() | read() << 8 | read() << 16 | read() << 24 |
                       read() << 32 | read() << 40 | read() << 48 | read() << 56;
            }
        }

        @Override
        public long readLong(int roff) {
            if (bigEndian()) {
                return read(roff) << 56 | read(roff + 1) << 48 | read(roff + 2) << 40 | read(roff + 3) << 32 |
                       read(roff + 4) << 24 | read(roff + 5) << 16 | read(roff + 6) << 8 | read(roff + 7);
            } else {
                return read(roff) | read(roff + 1) << 8 | read(roff + 2) << 16 | read(roff + 3) << 24 |
                       read(roff + 4) << 32 | read(roff + 5) << 40 | read(roff + 6) << 48 | read(roff + 7) << 56;
            }
        }

        @Override
        public Buf writeLong(long value) {
            if (bigEndian()) {
                write((int) (value >>> 56));
                write((int) (value >>> 48 & 0xFF));
                write((int) (value >>> 40 & 0xFF));
                write((int) (value >>> 32 & 0xFF));
                write((int) (value >>> 24 & 0xFF));
                write((int) (value >>> 16 & 0xFF));
                write((int) (value >>> 8 & 0xFF));
                write((int) (value & 0xFF));
            } else {
                write((int) (value & 0xFF));
                write((int) (value >>> 8 & 0xFF));
                write((int) (value >>> 16 & 0xFF));
                write((int) (value >>> 24));
                write((int) (value >>> 32));
                write((int) (value >>> 40));
                write((int) (value >>> 48));
                write((int) (value >>> 56));
            }
            return buf;
        }

        @Override
        public Buf writeLong(int woff, long value) {
            if (bigEndian()) {
                write(woff, (int) (value >>> 56));
                write(woff + 1, (int) (value >>> 48 & 0xFF));
                write(woff + 2, (int) (value >>> 40 & 0xFF));
                write(woff + 3, (int) (value >>> 32 & 0xFF));
                write(woff + 4, (int) (value >>> 24 & 0xFF));
                write(woff + 5, (int) (value >>> 16 & 0xFF));
                write(woff + 6, (int) (value >>> 8 & 0xFF));
                write(woff + 7, (int) (value & 0xFF));
            } else {
                write(woff, (int) (value & 0xFF));
                write(woff + 1, (int) (value >>> 8 & 0xFF));
                write(woff + 2, (int) (value >>> 16 & 0xFF));
                write(woff + 3, (int) (value >>> 24));
                write(woff + 4, (int) (value >>> 32));
                write(woff + 5, (int) (value >>> 40));
                write(woff + 6, (int) (value >>> 48));
                write(woff + 7, (int) (value >>> 56));
            }
            return buf;
        }

        @Override
        public double readDouble() {
            return Double.longBitsToDouble(readLong());
        }

        @Override
        public double readDouble(int roff) {
            return Double.longBitsToDouble(readLong(roff));
        }

        @Override
        public Buf writeDouble(double value) {
            return writeLong(Double.doubleToRawLongBits(value));
        }

        @Override
        public Buf writeDouble(int woff, double value) {
            return writeLong(woff, Double.doubleToRawLongBits(value));
        }

        private boolean bigEndian() {
            return buf.order() == ByteOrder.BIG_ENDIAN;
        }

        private long read() {
            return buf.readPassThrough();
        }

        private void write(int value) {
            buf.writePassThrough(value);
        }

        private long read(int roff) {
            return buf.readPassThrough(roff);
        }

        private void write(int woff, int value) {
            buf.writePassThrough(woff, value);
        }
    }
    // </editor-fold>
}
