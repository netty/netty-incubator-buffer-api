package io.netty.buffer.b2;

public class DirectByteBufTest extends ByteBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.direct();
    }
}
