package io.netty.buffer.b2;

import org.junit.Test;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class DirectBufWithCleanerTest extends DirectBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.directWithCleaner();
    }

    @Test
    public void bufferMustBeClosedByCleaner() throws InterruptedException {
        var allocator = createAllocator();
        allocator.close();
        int iterations = 100;
        int allocationSize = 1024;
        for (int i = 0; i < iterations; i++) {
            allocateAndForget(allocator, allocationSize);
            System.gc();
            System.runFinalization();
        }
        var sum = Statics.MEM_USAGE_NATIVE.sum();
        var totalAllocated = (long) allocationSize * iterations;
        assertThat(sum, lessThan(totalAllocated));
    }

    protected void allocateAndForget(Allocator allocator, long size) {
        allocator.allocate(size);
    }
}
