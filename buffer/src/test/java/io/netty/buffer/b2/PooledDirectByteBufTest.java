package io.netty.buffer.b2;

public class PooledDirectByteBufTest extends ByteBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.pooledDirect();
    }
}
