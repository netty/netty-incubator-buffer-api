package io.netty.buffer.b2;

public class HeapBBufTest extends BBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.heap();
    }
}
