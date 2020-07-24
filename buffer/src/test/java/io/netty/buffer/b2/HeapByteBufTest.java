package io.netty.buffer.b2;

public class HeapByteBufTest extends ByteBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.heap();
    }
}
