package io.netty.buffer.api.pool;

import io.netty.util.internal.StringUtil;

import java.util.List;

/**
 * Exposed metric for {@link PooledBufferAllocator}.
 */
@SuppressWarnings("deprecation")
public final class PooledBufferAllocatorMetric implements BufferAllocatorMetric {

    private final PooledBufferAllocator allocator;

    PooledBufferAllocatorMetric(PooledBufferAllocator allocator) {
        this.allocator = allocator;
    }

    /**
     * Return the number of arenas.
     */
    public int numArenas() {
        return allocator.numArenas();
    }

    /**
     * Return a {@link List} of all {@link PoolArenaMetric}s that are provided by this pool.
     */
    public List<PoolArenaMetric> arenaMetrics() {
        return allocator.arenaMetrics();
    }

    /**
     * Return the number of thread local caches used by this {@link PooledBufferAllocator}.
     */
    public int numThreadLocalCaches() {
        return allocator.numThreadLocalCaches();
    }

    /**
     * Return the size of the small cache.
     */
    public int smallCacheSize() {
        return allocator.smallCacheSize();
    }

    /**
     * Return the size of the normal cache.
     */
    public int normalCacheSize() {
        return allocator.normalCacheSize();
    }

    /**
     * Return the chunk size for an arena.
     */
    public int chunkSize() {
        return allocator.chunkSize();
    }

    @Override
    public long usedMemory() {
        return allocator.usedMemory();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(256);
        sb.append(StringUtil.simpleClassName(this))
                .append("(usedMemory: ").append(usedMemory())
                .append("; numArenas: ").append(numArenas())
                .append("; smallCacheSize: ").append(smallCacheSize())
                .append("; normalCacheSize: ").append(normalCacheSize())
                .append("; numThreadLocalCaches: ").append(numThreadLocalCaches())
                .append("; chunkSize: ").append(chunkSize()).append(')');
        return sb.toString();
    }
}
