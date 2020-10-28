/*
 * Copyright 2020 The Netty Project
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
package io.netty.buffer.b2;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;

@RunWith(Parameterized.class)
public class CompositeBufTest extends BufTest {
    @Parameters(name = "{0}")
    public static Iterable<Object[]> parameters() {
        Map<String, Supplier<Allocator>> subAllocators = Map.of(
                "heap", Allocator::heap,
                "direct", Allocator::direct,
                "directWithCleaner", Allocator::directWithCleaner,
                "pooledHeap", Allocator::pooledHeap,
                "pooledDirect", Allocator::pooledDirect,
                "pooledDirectWithCleaner", Allocator::pooledDirectWithCleaner);
        List<Object[]> combinations = new ArrayList<>(subAllocators.size() * subAllocators.size());
        for (Entry<String, Supplier<Allocator>> first : subAllocators.entrySet()) {
            for (Entry<String, Supplier<Allocator>> second : subAllocators.entrySet()) {
                String name = "compose(" + first.getKey() + ", " + second.getKey() + ')';
                var firstAllocator = first.getValue();
                var secondAllocator = second.getValue();
                Supplier<Allocator> allocator = () -> {
                    var a = firstAllocator.get();
                    var b = secondAllocator.get();
                    return new Allocator() {
                        @Override
                        public Buf allocate(long size) {
                            long half = size / 2;
                            try (Buf firstHalf = a.allocate(half);
                                 Buf secondHalf = b.allocate(size - half)) {
                                return Buf.compose(firstHalf, secondHalf);
                            }
                        }

                        @Override
                        public void close() {
                            a.close();
                            b.close();
                        }
                    };
                };
                combinations.add(new Object[]{name, allocator});
            }
        }
        return combinations;
    }

    private final Supplier<Allocator> allocatorFactory;

    public CompositeBufTest(String testName, Supplier<Allocator> allocatorFactory) {
        this.allocatorFactory = allocatorFactory;
    }

    @Override
    protected Allocator createAllocator() {
        return allocatorFactory.get();
    }
}
