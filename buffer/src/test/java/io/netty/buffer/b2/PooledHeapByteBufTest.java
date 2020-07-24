package io.netty.buffer.b2;

public class PooledHeapByteBufTest extends ByteBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.pooledHeap();
    }
}
