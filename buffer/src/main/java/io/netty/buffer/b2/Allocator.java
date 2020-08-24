package io.netty.buffer.b2;

import jdk.incubator.foreign.MemorySegment;

import static io.netty.buffer.b2.BBuf.*;

/**
 * Interface for {@link Buf} allocators.
 */
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

    /**
     * Allocate a {@link Buf} of the given size in bytes. This method may throw an {@link OutOfMemoryError} if there is
     * not enough free memory available to allocate a {@link Buf} of the requested size.
     *
     * @param size The size of {@link Buf} to allocate.
     * @return The newly allocated {@link Buf}.
     */
    <T extends Buf<T>> T allocate(long size);

    /**
     * Close this allocator, freeing all of its internal resources. It is not specified if the allocator can still be
     * used after this method has been called on it.
     */
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
            public Buf allocate(long size) {
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
