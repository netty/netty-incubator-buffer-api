/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer.b2;

import org.junit.Test;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class PooledDirectBufWithCleanerTest extends DirectBufWithCleanerTest {
    @Override
    protected Allocator createAllocator() {
        return Allocator.pooledDirectWithCleaner();
    }

    @Test
    public void buffersMustBeReusedByPoolingAllocatorEvenWhenDroppedByCleanerInsteadOfExplicitly()
            throws InterruptedException {
        try (var allocator = createAllocator()) {
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
    }
}
