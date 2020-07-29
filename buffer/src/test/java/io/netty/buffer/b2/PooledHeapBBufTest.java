package io.netty.buffer.b2;

public class PooledHeapBBufTest extends BBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.pooledHeap();
    }
}
