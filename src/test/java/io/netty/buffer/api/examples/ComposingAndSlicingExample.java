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
package io.netty.buffer.api.examples;

import io.netty.buffer.api.BufferAllocator;
import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.Scope;

import java.util.concurrent.ThreadLocalRandom;

public final class ComposingAndSlicingExample {
    public static void main(String[] args) {
        try (BufferAllocator allocator = BufferAllocator.pooledDirect();
             Buffer buf = createBigBuffer(allocator)) {

            ThreadLocalRandom tlr = ThreadLocalRandom.current();
            for (int i = 0; i < tlr.nextInt(4, 200); i++) {
                buf.writeByte((byte) tlr.nextInt());
            }

            try (Buffer slice = buf.slice()) {
                slice.send();
                System.out.println("buf.capacity() = " + buf.capacity());
                System.out.println("buf.readableBytes() = " + buf.readableBytes());
                System.out.println("---");
                System.out.println("slice.capacity() = " + slice.capacity());
                System.out.println("slice.readableBytes() = " + slice.readableBytes());
            }
        }
    }

    private static Buffer createBigBuffer(BufferAllocator allocator) {
        try (Scope scope = new Scope()) {
            return Buffer.compose(allocator,
                    scope.add(allocator.allocate(64)),
                    scope.add(allocator.allocate(64)),
                    scope.add(allocator.allocate(64)),
                    scope.add(allocator.allocate(64)));
        }
    }
}
