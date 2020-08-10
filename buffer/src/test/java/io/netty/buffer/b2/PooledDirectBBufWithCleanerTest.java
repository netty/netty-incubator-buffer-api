package io.netty.buffer.b2;

import org.junit.Test;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class PooledDirectBBufWithCleanerTest extends BBufTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.pooledDirectWithCleaner();
    }

    @Test
    public void bufferMustBeClosedByCleaner() throws InterruptedException {
        var allocator = createAllocator();
        double sumOfMemoryDataPoints = 0;
        allocator.close();
        int iterations = 100;
        int allocationSize = 1024;
        for (int i = 0; i < iterations; i++) {
            allocateAndForget(allocator, allocationSize);
            System.gc();
            sumOfMemoryDataPoints += Statics.MEM_USAGE_NATIVE.sum();
        }
        double meanMemoryUsage = sumOfMemoryDataPoints / iterations;
        assertThat(meanMemoryUsage, lessThan(allocationSize * 5.0));
    }

    protected void allocateAndForget(Allocator allocator, long size) {
        allocator.allocate(size);
    }
}
