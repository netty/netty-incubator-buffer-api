package io.netty.buffer.b2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.internal.PlatformDependent;
import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;

import java.lang.invoke.VarHandle;

public class BBuf extends Rc<BBuf> {
    static final Drop<BBuf> NO_DROP = buf -> {};
    static final Drop<BBuf> SEGMENT_CLOSE = buf -> buf.segment.close();
    private final MemorySegment segment;
    private long read;
    private long write;

    BBuf(MemorySegment segment, Drop<BBuf> drop) {
        super(drop);
        this.segment = segment;
    }

    public BBuf readerIndex(long index) {
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

    public BBuf setLong(long offset, long value) {
        MemoryAccess.setLongAtOffset(segment, offset, value);
        return this;
    }

    public long getLong(long offset) {
        return MemoryAccess.getLongAtOffset(segment, offset);
    }

    public void fill(byte value) {
        segment.fill(value);
    }

    public long getNativeAddress() {
        try {
            return segment.address().toRawLongValue();
        } catch (UnsupportedOperationException e) {
            return 0; // This is a heap segment. Probably.
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
    protected BBuf copy(Thread recipient, Drop<BBuf> drop) {
        BBuf copy = new BBuf(segment.withOwnerThread(recipient), drop);
        copy.read = read;
        copy.write = write;
        return copy;
    }

    @Override
    protected BBuf prepareSend() {
        BBuf outer = this;
        MemorySegment transferSegment = segment.withOwnerThread(Lazy.TRANSFER_OWNER);
        return new BBuf(transferSegment, NO_DROP) {
            @Override
            protected BBuf copy(Thread recipient, Drop<BBuf> drop) {
                Object scope = PlatformDependent.getObject(transferSegment, Lazy.SCOPE);
                PlatformDependent.putObject(scope, Lazy.OWNER, recipient);
                VarHandle.fullFence();
                BBuf copy = new BBuf(transferSegment, drop);
                copy.read = outer.read;
                copy.write = outer.write;
                return copy;
            }
        };
    }

    private static class Lazy {
        @SuppressWarnings("InstantiatingAThreadWithDefaultRunMethod")
        private static final Thread TRANSFER_OWNER = new Thread("ByteBuf Transfer Owner");
        private static final long SCOPE = Statics.fieldOffset("jdk.internal.foreign.AbstractMemorySegmentImpl", "scope");
        private static final long OWNER = Statics.fieldOffset("jdk.internal.foreign.MemoryScope", "owner");
    }
}
