package io.netty.buffer.b2;

import jdk.incubator.foreign.MemorySegment;

import static io.netty.buffer.b2.ByteBuf.*;

public interface Allocator extends AutoCloseable {

    ByteBuf allocate(long size);

    @Override
    default void close() {
    }

    static Allocator heap() {
        return new Allocator() {
            @Override
            public ByteBuf allocate(long size) {
                var segment = MemorySegment.ofArray(new byte[Math.toIntExact(size)]);
                return new ByteBuf(segment, SEGMENT_CLOSE);
            }
        };
    }

    static Allocator direct() {
        return new Allocator() {
            @Override
            public ByteBuf allocate(long size) {
                return new ByteBuf(MemorySegment.allocateNative(size), SEGMENT_CLOSE);
            }
        };
    }

    static Allocator pooledHeap() {
        return new SizeClassedMemoryPool() {
            @Override
            protected MemorySegment createMemorySegment(long size) {
                return MemorySegment.ofArray(new byte[Math.toIntExact(size)]);
            }
        };
    }

    static Allocator pooledDirect() {
        return new SizeClassedMemoryPool() {
            @Override
            protected MemorySegment createMemorySegment(long size) {
                return MemorySegment.allocateNative(size);
            }
        };
    }
}
