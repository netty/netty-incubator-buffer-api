package io.netty.buffer.b2;

public class DirectBBufTest extends BBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.direct();
    }
}
