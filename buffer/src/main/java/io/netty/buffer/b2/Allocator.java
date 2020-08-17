package io.netty.buffer.b2;

import jdk.incubator.foreign.MemorySegment;

import static io.netty.buffer.b2.BBuf.*;

@SuppressWarnings("InterfaceMayBeAnnotatedFunctional")
public interface Allocator extends AutoCloseable {
    static void checkSize(long size) {
        if (size < 1) {
            throw new IllegalArgumentException("Buffer size must be positive, but was " + size + '.');
        }
        // We use max array size because on-heap buffers will be backed by byte-arrays.
        int maxArraySize = Integer.MAX_VALUE - 8;
        if (size > maxArraySize) {
            throw new IllegalArgumentException(
                    "Buffer size cannot be greater than " + maxArraySize + ", but was " + size + '.');
        }
    }

    BBuf allocate(long size);

    @Override
    default void close() {
    }

    static Allocator heap() {
        return new Allocator() {
            @Override
            public BBuf allocate(long size) {
                checkSize(size);
                var segment = MemorySegment.ofArray(new byte[Math.toIntExact(size)]);
                return new BBuf(segment, SEGMENT_CLOSE);
            }
        };
    }

    static Allocator direct() {
        return new Allocator() {
            @Override
            public BBuf allocate(long size) {
                checkSize(size);
                var segment = MemorySegment.allocateNative(size);
                Statics.MEM_USAGE_NATIVE.add(size);
                return new BBuf(segment, SEGMENT_CLOSE_NATIVE);
            }
        };
    }

    static Allocator pooledHeap() {
        return new SizeClassedMemoryPool(false) {
            @Override
            protected MemorySegment createMemorySegment(long size) {
                checkSize(size);
                return MemorySegment.ofArray(new byte[Math.toIntExact(size)]);
            }
        };
    }

    static Allocator pooledDirect() {
        return new SizeClassedMemoryPool(true) {
            @Override
            protected MemorySegment createMemorySegment(long size) {
                checkSize(size);
                return MemorySegment.allocateNative(size);
            }
        };
    }

    static Allocator pooledDirectWithCleaner() {
        return new SizeClassedMemoryPool(true) {
            @Override
            protected MemorySegment createMemorySegment(long size) {
                checkSize(size);
                return MemorySegment.allocateNative(size);
            }

            @Override
            protected BBuf createBBuf(MemorySegment segment) {
                var drop = new NativeMemoryCleanerDrop();
                var buf = new BBuf(segment, drop);
                drop.accept(buf);
                return buf;
            }
        };
    }
}
