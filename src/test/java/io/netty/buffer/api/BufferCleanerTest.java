/*
 * Copyright 2021 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer.api;

import io.netty.buffer.api.memseg.NativeMemorySegmentManager;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

public class BufferCleanerTest extends BufferTestSupport {
    @Disabled("Too slow, for now")
    @ParameterizedTest
    @MethodSource("directAllocators")
    public void bufferMustBeClosedByCleaner(Fixture fixture) throws InterruptedException {
        var initial = NativeMemorySegmentManager.MEM_USAGE_NATIVE.sum();
        var allocator = fixture.createAllocator();
        allocator.close();
        int iterations = 15;
        int allocationSize = 1024;
        for (int i = 0; i < iterations; i++) {
            allocateAndForget(allocator, allocationSize);
            System.gc();
        }
        System.runFinalization();
        var sum = NativeMemorySegmentManager.MEM_USAGE_NATIVE.sum() - initial;
        var totalAllocated = (long) allocationSize * iterations;
        assertThat(sum).isLessThan(totalAllocated);
    }

    private static void allocateAndForget(BufferAllocator allocator, int size) {
        allocator.allocate(size);
    }

    @Disabled("Too slow, for now")
    @ParameterizedTest
    @MethodSource("pooledDirectAllocators")
    public void buffersMustBeReusedByPoolingAllocatorEvenWhenDroppedByCleanerInsteadOfExplicitly(Fixture fixture)
            throws InterruptedException {
        var initial = NativeMemorySegmentManager.MEM_USAGE_NATIVE.sum();
        try (var allocator = fixture.createAllocator()) {
            int iterations = 15;
            int allocationSize = 1024;
            for (int i = 0; i < iterations; i++) {
                allocateAndForget(allocator, allocationSize);
                System.gc();
            }
            System.runFinalization();
            var sum = NativeMemorySegmentManager.MEM_USAGE_NATIVE.sum() - initial;
            var totalAllocated = (long) allocationSize * iterations;
            assertThat(sum).isLessThan(totalAllocated);
        }
    }
}
