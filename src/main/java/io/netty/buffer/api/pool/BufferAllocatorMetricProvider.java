package io.netty.buffer.api.pool;

import io.netty.buffer.api.BufferAllocator;

public interface BufferAllocatorMetricProvider {

    /**
     * Returns a {@link BufferAllocatorMetric} for a {@link BufferAllocator}.
     */
    BufferAllocatorMetric metric();
}
