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

import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemorySegment;

import static io.netty.buffer.b2.Statics.*;

class BBuf extends RcSupport<Buf, BBuf> implements Buf {
    static final Drop<BBuf> SEGMENT_CLOSE = buf -> buf.segment.close();
    static final Drop<BBuf> SEGMENT_CLOSE_NATIVE = buf -> {
        buf.segment.close();
        MEM_USAGE_NATIVE.add(-buf.segment.byteSize());
    };
    final MemorySegment segment;
    private int read;
    private int write;

    BBuf(MemorySegment segment, Drop<BBuf> drop) {
        super(drop);
        this.segment = segment;
    }

    @Override
    public int capacity() {
        return (int) segment.byteSize();
    }

    @Override
    public int readerIndex() {
        return read;
    }

    @Override
    public BBuf readerIndex(int index) {
        checkIndexBounds(index);
        read = index;
        return this;
    }

    @Override
    public int writerIndex() {
        return write;
    }

    @Override
    public BBuf writerIndex(int index) {
        checkIndexBounds(index);
        write = index;
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
        segment.fill(value);
        return this;
    }

    @Override
    public byte[] copy() {
        return segment.toByteArray();
    }

    @Override
    public long getNativeAddress() {
        try {
            return segment.address().toRawLongValue();
        } catch (UnsupportedOperationException e) {
            return 0; // This is a heap segment.
        }
    }

    @Override
    public byte readByte() {
        byte value = MemoryAccess.getByteAtOffset(segment, read);
        read += 1;
        return value;
    }

    @Override
    public byte readByte(int index) {
        return MemoryAccess.getByteAtOffset(segment, index);
    }

    @Override
    public Buf writeByte(byte value) {
        MemoryAccess.setByteAtOffset(segment, write, value);
        write += 1;
        return this;
    }

    @Override
    public Buf writeByte(int index, byte value) {
        MemoryAccess.setByteAtOffset(segment, index, value);
        return this;
    }

    @Override
    public long readLong() {
        long value = MemoryAccess.getLongAtOffset(segment, read);
        read += Long.BYTES;
        return value;
    }

    @Override
    public long readLong(int offset) {
        return MemoryAccess.getLongAtOffset(segment, offset);
    }

    @Override
    public Buf writeLong(long value) {
        MemoryAccess.setLongAtOffset(segment, write, value);
        write += Long.BYTES;
        return this;
    }

    @Override
    public Buf writeLong(int offset, long value) {
        MemoryAccess.setLongAtOffset(segment, offset, value);
        return this;
    }

    @Override
    public int readInt() {
        int value = MemoryAccess.getIntAtOffset(segment, read);
        read += Integer.BYTES;
        return value;
    }

    @Override
    public int readInt(int offset) {
        return MemoryAccess.getIntAtOffset(segment, offset);
    }

    @Override
    public Buf writeInt(int value) {
        MemoryAccess.setIntAtOffset(segment, write, value);
        write += Integer.BYTES;
        return this;
    }

    @Override
    public Buf writeInt(int offset, int value) {
        MemoryAccess.setIntAtOffset(segment, offset, value);
        return this;
    }

    @Override
    public short readShort() {
        short value = MemoryAccess.getShortAtOffset(segment, read);
        read += Short.BYTES;
        return value;
    }

    @Override
    public short readShort(int offset) {
        return MemoryAccess.getShortAtOffset(segment, offset);
    }

    @Override
    public Buf writeShort(short value) {
        MemoryAccess.setShortAtOffset(segment, write, value);
        write += Short.BYTES;
        return this;
    }

    @Override
    public Buf writeShort(int offset, short value) {
        MemoryAccess.setShortAtOffset(segment, offset, value);
        return this;
    }

    @Override
    protected Owned<BBuf> prepareSend() {
        BBuf outer = this;
        boolean isConfined = segment.ownerThread() == null;
        MemorySegment transferSegment = isConfined? segment : segment.withOwnerThread(null);
        return new Owned<BBuf>() {
            @Override
            public BBuf transferOwnership(Thread recipient, Drop<BBuf> drop) {
                var newSegment = isConfined? transferSegment.withOwnerThread(recipient) : transferSegment;
                BBuf copy = new BBuf(newSegment, drop);
                copy.read = outer.read;
                copy.write = outer.write;
                return copy;
            }
        };
    }

    private void checkIndexBounds(int index) {
        if (index < 0 || segment.byteSize() <= index) {
            throw indexOutOfBounds(index);
        }
    }

    private IndexOutOfBoundsException indexOutOfBounds(int index) {
        return new IndexOutOfBoundsException(
                "Index " + index + " is out of bounds: [0 to " + segment.byteSize() + "].");
    }
}
