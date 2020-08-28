package io.netty.buffer.b2;

import org.junit.Test;

import static org.junit.Assert.*;

public class HeapBBufTest extends BBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.heap();
    }

    @Test
    public void heapBufferMustHaveZeroAddress() {
        try (Allocator allocator = createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.getNativeAddress());
        }
    }
}
