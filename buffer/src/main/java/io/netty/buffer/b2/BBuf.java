package io.netty.buffer.b2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemorySegment;

import static io.netty.buffer.b2.Statics.*;

public class BBuf extends Rc<BBuf> {
    static final Drop<BBuf> NO_DROP = buf -> {};
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

    public BBuf readerIndex(int index) {
        if (index < 0 || segment.byteSize() <= index) {
            throw new IndexOutOfBoundsException(
                    "Index " + index + " is out of bounds: [0 to " + segment.byteSize() + "].");
        }
        read = index;
        return this;
    }

    public BBuf touch() {
        return this;
    }

    public byte readByte() {
        return MemoryAccess.getByteAtOffset(segment, read++);
    }

    public void writeByte(byte value) {
        MemoryAccess.setByteAtOffset(segment, write++, value);
    }

    public BBuf setLong(int offset, long value) {
        MemoryAccess.setLongAtOffset(segment, offset, value);
        return this;
    }

    public long getLong(int offset) {
        return MemoryAccess.getLongAtOffset(segment, offset);
    }

    public void fill(byte value) {
        segment.fill(value);
    }

    long getNativeAddress() {
        try {
            return segment.address().toRawLongValue();
        } catch (UnsupportedOperationException e) {
            return 0; // This is a heap segment.
        }
    }

    public long size() {
        return segment.byteSize();
    }

    public byte[] debugAsByteArray() {
        return segment.toByteArray();
    }

    public ByteBuf view() {
        return Unpooled.wrappedBuffer(getNativeAddress(), Math.toIntExact(size()), false);
    }

    @Override
    protected BBuf transferOwnership(Thread recipient, Drop<BBuf> drop) {
        BBuf copy = new BBuf(segment.withOwnerThread(recipient), drop);
        copy.read = read;
        copy.write = write;
        return copy;
    }

    @Override
    protected BBuf prepareSend() {
        BBuf outer = this;
        MemorySegment transferSegment = segment.withOwnerThread(TRANSFER_OWNER);
        return new BBuf(transferSegment, NO_DROP) {
            @Override
            protected BBuf transferOwnership(Thread recipient, Drop<BBuf> drop) {
                overwriteMemorySegmentOwner(transferSegment, recipient);
                BBuf copy = new BBuf(transferSegment, drop);
                copy.read = outer.read;
                copy.write = outer.write;
                return copy;
            }
        };
    }
}
