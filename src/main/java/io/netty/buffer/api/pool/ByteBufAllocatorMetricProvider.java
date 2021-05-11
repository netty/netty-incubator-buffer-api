package io.netty.buffer.api.pool;

import io.netty.buffer.api.BufferAllocator;

public interface ByteBufAllocatorMetricProvider {

    /**
     * Returns a {@link ByteBufAllocatorMetric} for a {@link BufferAllocator}.
     */
    ByteBufAllocatorMetric metric();
}
