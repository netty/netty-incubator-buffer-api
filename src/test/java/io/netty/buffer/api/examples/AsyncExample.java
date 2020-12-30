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

import io.netty.buffer.api.Allocator;
import io.netty.buffer.api.Buf;

import static java.util.concurrent.CompletableFuture.completedFuture;

public final class AsyncExample {
    public static void main(String[] args) throws Exception {
        try (Allocator allocator = Allocator.pooledDirect();
             Buf startBuf = allocator.allocate(16)) {
            startBuf.writeLong(threadId());

            completedFuture(startBuf.send()).thenApplyAsync(send -> {
                try (Buf buf = send.receive()) {
                    buf.writeLong(threadId());
                    return buf.send();
                }
            }).thenAcceptAsync(send -> {
                try (Buf buf = send.receive()) {
                    System.out.println("First thread id was " + buf.readLong());
                    System.out.println("Then sent to " + buf.readLong());
                    System.out.println("And now in thread " + threadId());
                }
            }).get();
        }
    }

    private static long threadId() {
        return Thread.currentThread().getId();
    }
}
