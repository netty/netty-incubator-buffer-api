package io.netty.buffer.b2;

public class PooledHeapBBufTest extends HeapBBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.pooledHeap();
    }
}
