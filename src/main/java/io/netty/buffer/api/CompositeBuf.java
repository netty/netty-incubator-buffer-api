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
import java.util.Arrays;
import java.util.Objects;

final class CompositeBuf extends RcSupport<Buf, CompositeBuf> implements Buf {
    /**
     * The max array size is JVM implementation dependant, but most seem to settle on {@code Integer.MAX_VALUE - 8}.
     * We set the max composite buffer capacity to the same, since it would otherwise be impossible to create a
     * non-composite copy of the buffer.
     */
    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;
    private static final Drop<CompositeBuf> COMPOSITE_DROP = buf -> {
        for (Buf b : buf.bufs) {
            b.close();
        }
    };

    private final Allocator allocator;
    private final TornBufAccessors tornBufAccessors;
    private final boolean isSendable;
    private Buf[] bufs;
    private int[] offsets; // The offset, for the composite buffer, where each constituent buffer starts.
    private int capacity;
    private int roff;
    private int woff;
    private int subOffset; // The next offset *within* a consituent buffer to read from or write to.
    private ByteOrder order;

    CompositeBuf(Allocator allocator, Buf[] bufs) {
        this(allocator, true, bufs.clone(), COMPOSITE_DROP); // Clone prevents external modification of array.
    }

    private CompositeBuf(Allocator allocator, boolean isSendable, Buf[] bufs, Drop<CompositeBuf> drop) {
        super(drop);
        this.allocator = allocator;
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
            order = bufs[0].order();
        } else {
            order = ByteOrder.nativeOrder();
        }
        this.bufs = bufs;
        computeBufferOffsets();
        tornBufAccessors = new TornBufAccessors(this);
    }

    private void computeBufferOffsets() {
        if (bufs.length > 0) {
            int woff = 0;
            int roff = 0;
            boolean woffMidpoint = false;
            for (Buf buf : bufs) {
                if (buf.writableBytes() == 0) {
                    woff += buf.capacity();
                } else if (!woffMidpoint) {
                    woff += buf.writerOffset();
                    woffMidpoint = true;
                } else if (buf.writerOffset() != 0) {
                    throw new IllegalArgumentException(
                            "The given buffers cannot be composed because they leave an unwritten gap: " +
                            Arrays.toString(bufs) + '.');
                }
            }
            boolean roffMidpoint = false;
            for (Buf buf : bufs) {
                if (buf.readableBytes() == 0 && buf.writableBytes() == 0) {
                    roff += buf.capacity();
                } else if (!roffMidpoint) {
                    roff += buf.readerOffset();
                    roffMidpoint = true;
                } else if (buf.readerOffset() != 0) {
                    throw new IllegalArgumentException(
                            "The given buffers cannot be composed because they leave an unread gap: " +
                            Arrays.toString(bufs) + '.');
                }
            }
            assert roff <= woff:
                    "The given buffers place the read offset ahead of the write offset: " + Arrays.toString(bufs) + '.';
            // Commit computed offsets.
            this.woff = woff;
            this.roff = roff;
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
    }

    @Override
    public String toString() {
        return "Buf[roff:" + roff + ", woff:" + woff + ", cap:" + capacity + ']';
    }

    @Override
    public Buf order(ByteOrder order) {
        this.order = order;
        for (Buf buf : bufs) {
            buf.order(order);
        }
        return this;
    }

    @Override
    public ByteOrder order() {
        return order;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public int readerOffset() {
        return roff;
    }

    @Override
    public Buf readerOffset(int index) {
        prepRead(index, 0);
        int indexLeft = index;
        for (Buf buf : bufs) {
            buf.readerOffset(Math.min(indexLeft, buf.capacity()));
            indexLeft = Math.max(0, indexLeft - buf.capacity());
        }
        roff = index;
        return this;
    }

    @Override
    public int writerOffset() {
        return woff;
    }

    @Override
    public Buf writerOffset(int index) {
        checkWriteBounds(index, 0);
        int indexLeft = index;
        for (Buf buf : bufs) {
            buf.writerOffset(Math.min(indexLeft, buf.capacity()));
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
    public long getNativeAddress() {
        return 0;
    }

    @Override
    public Buf slice(int offset, int length) {
        checkWriteBounds(offset, length);
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException(
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

            return new CompositeBuf(allocator, false, slices, drop).writerOffset(length);
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
            int toCopy = Math.min(buf.capacity() - subOffset, length);
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

        // Iterate in reverse to account for src and dest buffer overlap.
        // todo optimise by delegating to constituent buffers.
        var cursor = openReverseCursor(srcPos + length - 1, length);
        ByteOrder prevOrder = dest.order();
        // We read longs in BE, in reverse, so they need to be flipped for writing.
        dest.order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (cursor.readLong()) {
                length -= Long.BYTES;
                dest.setLong(destPos + length, cursor.getLong());
            }
            while (cursor.readByte()) {
                dest.setByte(destPos + --length, cursor.getByte());
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
        if (capacity < fromOffset + length) {
            throw new IllegalArgumentException("The fromOffset+length is beyond the end of the buffer: " +
                                               "fromOffset=" + fromOffset + ", length=" + length + '.');
        }
        int startBufferIndex = searchOffsets(fromOffset);
        int off = fromOffset - offsets[startBufferIndex];
        Buf startBuf = bufs[startBufferIndex];
        ByteCursor startCursor = startBuf.openCursor(off, Math.min(startBuf.capacity() - off, length));
        return new ByteCursor() {
            int index = fromOffset;
            final int end = fromOffset + length;
            int bufferIndex = startBufferIndex;
            int initOffset = startCursor.currentOffset();
            ByteCursor cursor = startCursor;
            long longValue = -1;
            byte byteValue = -1;

            @Override
            public boolean readLong() {
                if (cursor.readLong()) {
                    longValue = cursor.getLong();
                    return true;
                }
                if (bytesLeft() >= Long.BYTES) {
                    longValue = nextLongFromBytes();
                    return true;
                }
                return false;
            }

            private long nextLongFromBytes() {
                if (cursor.bytesLeft() == 0) {
                    nextCursor();
                    if (cursor.readLong()) {
                        return cursor.getLong();
                    }
                }
                long val = 0;
                for (int i = 0; i < 8; i++) {
                    readByte();
                    val <<= 8;
                    val |= getByte();
                }
                return val;
            }

            @Override
            public long getLong() {
                return longValue;
            }

            @Override
            public boolean readByte() {
                if (cursor.readByte()) {
                    byteValue = cursor.getByte();
                    return true;
                }
                if (bytesLeft() > 0) {
                    nextCursor();
                    cursor.readByte();
                    byteValue = cursor.getByte();
                    return true;
                }
                return false;
            }

            private void nextCursor() {
                bufferIndex++;
                Buf nextBuf = bufs[bufferIndex];
                cursor = nextBuf.openCursor(0, Math.min(nextBuf.capacity(), bytesLeft()));
                initOffset = 0;
            }

            @Override
            public byte getByte() {
                return byteValue;
            }

            @Override
            public int currentOffset() {
                int currOff = cursor.currentOffset();
                index += currOff - initOffset;
                initOffset = currOff;
                return index;
            }

            @Override
            public int bytesLeft() {
                return end - currentOffset();
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
        if (fromOffset - length < -1) {
            throw new IllegalArgumentException("The fromOffset-length would underflow the buffer: " +
                                               "fromOffset=" + fromOffset + ", length=" + length + '.');
        }
        int startBufferIndex = searchOffsets(fromOffset);
        int off = fromOffset - offsets[startBufferIndex];
        Buf startBuf = bufs[startBufferIndex];
        ByteCursor startCursor = startBuf.openReverseCursor(off, Math.min(off + 1, length));
        return new ByteCursor() {
            int index = fromOffset;
            final int end = fromOffset - length;
            int bufferIndex = startBufferIndex;
            int initOffset = startCursor.currentOffset();
            ByteCursor cursor = startCursor;
            long longValue = -1;
            byte byteValue = -1;

            @Override
            public boolean readLong() {
                if (cursor.readLong()) {
                    longValue = cursor.getLong();
                    return true;
                }
                if (bytesLeft() >= Long.BYTES) {
                    longValue = nextLongFromBytes();
                    return true;
                }
                return false;
            }

            private long nextLongFromBytes() {
                if (cursor.bytesLeft() == 0) {
                    nextCursor();
                    if (cursor.readLong()) {
                        return cursor.getLong();
                    }
                }
                long val = 0;
                for (int i = 0; i < 8; i++) {
                    readByte();
                    val <<= 8;
                    val |= getByte();
                }
                return val;
            }

            @Override
            public long getLong() {
                return longValue;
            }

            @Override
            public boolean readByte() {
                if (cursor.readByte()) {
                    byteValue = cursor.getByte();
                    return true;
                }
                if (bytesLeft() > 0) {
                    nextCursor();
                    cursor.readByte();
                    byteValue = cursor.getByte();
                    return true;
                }
                return false;
            }

            private void nextCursor() {
                bufferIndex--;
                Buf nextBuf = bufs[bufferIndex];
                int length = Math.min(nextBuf.capacity(), bytesLeft());
                int offset = nextBuf.capacity() - 1;
                cursor = nextBuf.openReverseCursor(offset, length);
                initOffset = offset;
            }

            @Override
            public byte getByte() {
                return byteValue;
            }

            @Override
            public int currentOffset() {
                int currOff = cursor.currentOffset();
                index -= initOffset - currOff;
                initOffset = currOff;
                return index;
            }

            @Override
            public int bytesLeft() {
                return currentOffset() - end;
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
            long newSize = capacity() + (long) size;
            Allocator.checkSize(newSize);
            int growth = size - writableBytes();
            Buf extension = bufs.length == 0? allocator.allocate(growth) : allocator.allocate(growth, order());
            unsafeExtendWith(extension);
        }
    }

    void extendWith(Buf extension) {
        Objects.requireNonNull(extension, "Extension buffer cannot be null.");
        if (!isOwned()) {
            throw new IllegalStateException("This buffer cannot be extended because it is not in an owned state.");
        }
        if (extension == this) {
            throw new IllegalArgumentException("This buffer cannot be extended with itself.");
        }
        if (bufs.length > 0 && extension.order() != order()) {
            throw new IllegalArgumentException(
                    "This buffer uses " + order() + " byte order, and cannot be extended with " +
                    "a buffer that uses " + extension.order() + " byte order.");
        }
        if (bufs.length == 0) {
            order = extension.order();
        }
        long newSize = capacity() + (long) extension.capacity();
        Allocator.checkSize(newSize);

        Buf[] restoreTemp = bufs; // We need this to restore our buffer array, in case offset computations fail.
        try {
            unsafeExtendWith(extension.acquire());
        } catch (Exception e) {
            bufs = restoreTemp;
            throw e;
        }
    }

    private void unsafeExtendWith(Buf extension) {
        bufs = Arrays.copyOf(bufs, bufs.length + 1);
        bufs[bufs.length - 1] = extension;
        computeBufferOffsets();
    }

    @Override
    public Buf bifurcate() {
        if (!isOwned()) {
            throw new IllegalStateException("Cannot bifurcate a buffer that is not owned.");
        }
        if (bufs.length == 0) {
            // Bifurcating a zero-length buffer is trivial.
            return new CompositeBuf(allocator, true, bufs, unsafeGetDrop()).order(order);
        }

        int i = searchOffsets(woff);
        int off = woff - offsets[i];
        Buf[] bifs = Arrays.copyOf(bufs, off == 0? i : 1 + i);
        bufs = Arrays.copyOfRange(bufs, off == bufs[i].capacity()? 1 + i : i, bufs.length);
        if (off > 0 && bifs.length > 0 && off < bifs[bifs.length - 1].capacity()) {
            bifs[bifs.length - 1] = bufs[0].bifurcate();
        }
        computeBufferOffsets();
        try {
            var compositeBuf = new CompositeBuf(allocator, true, bifs, unsafeGetDrop());
            compositeBuf.order = order; // Preserve byte order even if bifs array is empty.
            return compositeBuf;
        } finally {
            // Drop our references to the buffers in the bifs array. They belong to the new composite buffer now.
            for (Buf bif : bifs) {
                bif.close();
            }
        }
    }

    // <editor-fold defaultstate="collapsed" desc="Primitive accessors.">
    @Override
    public byte readByte() {
        return prepRead(Byte.BYTES).readByte();
    }

    @Override
    public byte getByte(int roff) {
        return prepRead(roff, Byte.BYTES).getByte(subOffset);
    }

    @Override
    public int readUnsignedByte() {
        return prepRead(Byte.BYTES).readUnsignedByte();
    }

    @Override
    public int getUnsignedByte(int roff) {
        return prepRead(roff, Byte.BYTES).getUnsignedByte(subOffset);
    }

    @Override
    public Buf writeByte(byte value) {
        prepWrite(Byte.BYTES).writeByte(value);
        return this;
    }

    @Override
    public Buf setByte(int woff, byte value) {
        prepWrite(woff, Byte.BYTES).setByte(subOffset, value);
        return this;
    }

    @Override
    public Buf writeUnsignedByte(int value) {
        prepWrite(Byte.BYTES).writeUnsignedByte(value);
        return this;
    }

    @Override
    public Buf setUnsignedByte(int woff, int value) {
        prepWrite(woff, Byte.BYTES).setUnsignedByte(subOffset, value);
        return this;
    }

    @Override
    public char readChar() {
        return prepRead(2).readChar();
    }

    @Override
    public char getChar(int roff) {
        return prepRead(roff, 2).getChar(subOffset);
    }

    @Override
    public Buf writeChar(char value) {
        prepWrite(2).writeChar(value);
        return this;
    }

    @Override
    public Buf setChar(int woff, char value) {
        prepWrite(woff, 2).setChar(subOffset, value);
        return this;
    }

    @Override
    public short readShort() {
        return prepRead(Short.BYTES).readShort();
    }

    @Override
    public short getShort(int roff) {
        return prepRead(roff, Short.BYTES).getShort(subOffset);
    }

    @Override
    public int readUnsignedShort() {
        return prepRead(Short.BYTES).readShort();
    }

    @Override
    public int getUnsignedShort(int roff) {
        return prepRead(roff, Short.BYTES).getUnsignedShort(subOffset);
    }

    @Override
    public Buf writeShort(short value) {
        prepWrite(Short.BYTES).writeShort(value);
        return this;
    }

    @Override
    public Buf setShort(int woff, short value) {
        prepWrite(woff, Short.BYTES).setShort(subOffset, value);
        return this;
    }

    @Override
    public Buf writeUnsignedShort(int value) {
        prepWrite(Short.BYTES).writeUnsignedShort(value);
        return this;
    }

    @Override
    public Buf setUnsignedShort(int woff, int value) {
        prepWrite(woff, Short.BYTES).setUnsignedShort(subOffset, value);
        return this;
    }

    @Override
    public int readMedium() {
        return prepRead(3).readMedium();
    }

    @Override
    public int getMedium(int roff) {
        return prepRead(roff, 3).getMedium(subOffset);
    }

    @Override
    public int readUnsignedMedium() {
        return prepRead(3).readMedium();
    }

    @Override
    public int getUnsignedMedium(int roff) {
        return prepRead(roff, 3).getMedium(subOffset);
    }

    @Override
    public Buf writeMedium(int value) {
        prepWrite(3).writeMedium(value);
        return this;
    }

    @Override
    public Buf setMedium(int woff, int value) {
        prepWrite(woff, 3).setMedium(subOffset, value);
        return this;
    }

    @Override
    public Buf writeUnsignedMedium(int value) {
        prepWrite(3).writeUnsignedMedium(value);
        return this;
    }

    @Override
    public Buf setUnsignedMedium(int woff, int value) {
        prepWrite(woff, 3).setUnsignedMedium(subOffset, value);
        return this;
    }

    @Override
    public int readInt() {
        return prepRead(Integer.BYTES).readInt();
    }

    @Override
    public int getInt(int roff) {
        return prepRead(roff, Integer.BYTES).getInt(subOffset);
    }

    @Override
    public long readUnsignedInt() {
        return prepRead(Integer.BYTES).readUnsignedInt();
    }

    @Override
    public long getUnsignedInt(int roff) {
        return prepRead(roff, Integer.BYTES).getUnsignedInt(subOffset);
    }

    @Override
    public Buf writeInt(int value) {
        prepWrite(Integer.BYTES).writeInt(value);
        return this;
    }

    @Override
    public Buf setInt(int woff, int value) {
        prepWrite(woff, Integer.BYTES).setInt(subOffset, value);
        return this;
    }

    @Override
    public Buf writeUnsignedInt(long value) {
        prepWrite(Integer.BYTES).writeUnsignedInt(value);
        return this;
    }

    @Override
    public Buf setUnsignedInt(int woff, long value) {
        prepWrite(woff, Integer.BYTES).setUnsignedInt(subOffset, value);
        return this;
    }

    @Override
    public float readFloat() {
        return prepRead(Float.BYTES).readFloat();
    }

    @Override
    public float getFloat(int roff) {
        return prepRead(roff, Float.BYTES).getFloat(subOffset);
    }

    @Override
    public Buf writeFloat(float value) {
        prepWrite(Float.BYTES).writeFloat(value);
        return this;
    }

    @Override
    public Buf setFloat(int woff, float value) {
        prepWrite(woff, Float.BYTES).setFloat(subOffset, value);
        return this;
    }

    @Override
    public long readLong() {
        return prepRead(Long.BYTES).readLong();
    }

    @Override
    public long getLong(int roff) {
        return prepRead(roff, Long.BYTES).getLong(subOffset);
    }

    @Override
    public Buf writeLong(long value) {
        prepWrite(Long.BYTES).writeLong(value);
        return this;
    }

    @Override
    public Buf setLong(int woff, long value) {
        prepWrite(woff, Long.BYTES).setLong(subOffset, value);
        return this;
    }

    @Override
    public double readDouble() {
        return prepRead(Double.BYTES).readDouble();
    }

    @Override
    public double getDouble(int roff) {
        return prepRead(roff, Double.BYTES).getDouble(subOffset);
    }

    @Override
    public Buf writeDouble(double value) {
        prepWrite(Double.BYTES).writeDouble(value);
        return this;
    }

    @Override
    public Buf setDouble(int woff, double value) {
        prepWrite(woff, Double.BYTES).setDouble(subOffset, value);
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
                var composite = new CompositeBuf(allocator, true, received, drop);
                drop.reconnect(composite);
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
    public boolean isOwned() {
        return isSendable && super.isOwned() && allConstituentsAreOwned();
    }

    private boolean allConstituentsAreOwned() {
        boolean result = true;
        for (Buf buf : bufs) {
            result &= buf.isOwned();
        }
        return result;
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

    long getPassThrough(int roff) {
        var buf = chooseBuffer(roff, 1);
        assert buf != tornBufAccessors: "Recursive call to torn buffer.";
        return buf.getUnsignedByte(subOffset);
    }

    void setPassThrough(int woff, int value) {
        var buf = chooseBuffer(woff, 1);
        assert buf != tornBufAccessors: "Recursive call to torn buffer.";
        buf.setUnsignedByte(subOffset, value);
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
        return i < 0? -(i + 2) : i;
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
        public byte getByte(int roff) {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public int readUnsignedByte() {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public int getUnsignedByte(int roff) {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public Buf writeByte(byte value) {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public Buf setByte(int woff, byte value) {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public Buf writeUnsignedByte(int value) {
            throw new AssertionError("Method should not be used.");
        }

        @Override
        public Buf setUnsignedByte(int woff, int value) {
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
        public char getChar(int roff) {
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
        public Buf setChar(int woff, char value) {
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
        public short getShort(int roff) {
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
        public int getUnsignedShort(int roff) {
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
        public Buf setShort(int woff, short value) {
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
        public Buf setUnsignedShort(int woff, int value) {
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
        public int getMedium(int roff) {
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
        public int getUnsignedMedium(int roff) {
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
        public Buf setMedium(int woff, int value) {
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
        public Buf setUnsignedMedium(int woff, int value) {
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
        public int getInt(int roff) {
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
        public long getUnsignedInt(int roff) {
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
        public Buf setInt(int woff, int value) {
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
        public Buf setUnsignedInt(int woff, long value) {
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
        public float getFloat(int roff) {
            return Float.intBitsToFloat(getInt(roff));
        }

        @Override
        public Buf writeFloat(float value) {
            return writeUnsignedInt(Float.floatToRawIntBits(value));
        }

        @Override
        public Buf setFloat(int woff, float value) {
            return setUnsignedInt(woff, Float.floatToRawIntBits(value));
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
        public long getLong(int roff) {
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
        public Buf setLong(int woff, long value) {
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
        public double getDouble(int roff) {
            return Double.longBitsToDouble(getLong(roff));
        }

        @Override
        public Buf writeDouble(double value) {
            return writeLong(Double.doubleToRawLongBits(value));
        }

        @Override
        public Buf setDouble(int woff, double value) {
            return setLong(woff, Double.doubleToRawLongBits(value));
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
            return buf.getPassThrough(roff);
        }

        private void write(int woff, int value) {
            buf.setPassThrough(woff, value);
        }
    }
    // </editor-fold>
}
