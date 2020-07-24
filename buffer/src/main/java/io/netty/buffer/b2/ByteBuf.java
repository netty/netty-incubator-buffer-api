package io.netty.buffer.b2;

import io.netty.util.internal.PlatformDependent;
import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;

import java.lang.invoke.VarHandle;

public class ByteBuf extends Rc<ByteBuf> {
    static final Drop<ByteBuf> NO_DROP = buf -> {};
    static final Drop<ByteBuf> SEGMENT_CLOSE = buf -> buf.segment.close();
    private final MemorySegment segment;
    private final MemoryAddress address;
    private long read;
    private long write;

    ByteBuf(MemorySegment segment, Drop<ByteBuf> drop) {
        super(drop);
        this.segment = segment;
        address = segment.address();
    }

    public byte get() {
        return MemoryAccess.getByteAtOffset(address, read++);
    }

    public void put(byte value) {
        MemoryAccess.setByteAtOffset(address, write++, value);
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
        return address.segment().toByteArray();
    }

    @Override
    protected ByteBuf copy(Thread recipient, Drop<ByteBuf> drop) {
        ByteBuf copy = new ByteBuf(segment.withOwnerThread(recipient), drop);
        copy.read = read;
        copy.write = write;
        return copy;
    }

    @Override
    protected ByteBuf prepareSend() {
        ByteBuf outer = this;
        MemorySegment transferSegment = segment.withOwnerThread(Lazy.TRANSFER_OWNER);
        return new ByteBuf(transferSegment, NO_DROP) {
            @Override
            protected ByteBuf copy(Thread recipient, Drop<ByteBuf> drop) {
                Object scope = PlatformDependent.getObject(transferSegment, Lazy.SCOPE);
                PlatformDependent.putObject(scope, Lazy.OWNER, recipient);
                VarHandle.fullFence();
                ByteBuf copy = new ByteBuf(transferSegment, drop);
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
