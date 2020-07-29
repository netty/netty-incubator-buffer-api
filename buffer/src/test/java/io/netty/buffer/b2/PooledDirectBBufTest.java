package io.netty.buffer.b2;

public class PooledDirectBBufTest extends BBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.pooledDirect();
    }
}
