package io.netty.buffer.b2;

public class PooledDirectBBufTest extends DirectBBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.pooledDirect();
    }
}
