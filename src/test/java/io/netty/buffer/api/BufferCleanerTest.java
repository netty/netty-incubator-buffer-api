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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

public class BufferCleanerTest extends BufferTestSupport {
    @ParameterizedTest
    @MethodSource("directAllocators")
    public void bufferMustBeClosedByCleaner(Fixture fixture) throws InterruptedException {
        var initial = NativeMemorySegmentManager.MEM_USAGE_NATIVE.sum();
        int allocationSize = 1024;
        allocateAndForget(fixture, allocationSize);
        long sum = 0;
        for (int i = 0; i < 15; i++) {
            System.gc();
            System.runFinalization();
            sum = NativeMemorySegmentManager.MEM_USAGE_NATIVE.sum() - initial;
            if (sum < allocationSize) {
                return;
            }
        }
        assertThat(sum).isLessThan(allocationSize);
    }

    private static void allocateAndForget(Fixture fixture, int size) {
        var allocator = fixture.createAllocator();
        allocator.close();
        allocator.allocate(size);
    }
}
