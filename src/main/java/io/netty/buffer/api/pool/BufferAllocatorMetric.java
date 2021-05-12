package io.netty.buffer.api.pool;

import io.netty.buffer.api.BufferAllocator;

public interface BufferAllocatorMetric {
    /**
     * Returns the number of bytes of heap memory used by a {@link BufferAllocator} or {@code -1} if unknown.
     */
    long usedMemory();
}
