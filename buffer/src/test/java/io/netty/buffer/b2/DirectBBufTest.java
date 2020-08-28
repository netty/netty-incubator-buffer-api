package io.netty.buffer.b2;

import org.junit.Test;

import static org.junit.Assert.*;

public class DirectBBufTest extends BBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.direct();
    }

    @Test
    public void directBufferMustHaveNonZeroAddress() {
        try (Allocator allocator = createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertNotEquals(0, buf.getNativeAddress());
        }
    }
}
