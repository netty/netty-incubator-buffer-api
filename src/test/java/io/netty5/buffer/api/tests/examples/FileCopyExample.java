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
package io.netty5.buffer.api.tests.examples;

import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.BufferAllocator;
import io.netty.buffer.api.CompositeBuffer;
import io.netty.buffer.api.Send;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public final class FileCopyExample {
    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ArrayBlockingQueue<Send<Buffer>> queue = new ArrayBlockingQueue<>(8);
        try (BufferAllocator allocator = BufferAllocator.offHeapPooled();
             var input = FileChannel.open(Path.of("/dev/urandom"), READ);
             var output = FileChannel.open(Path.of("random.bin"), CREATE, TRUNCATE_EXISTING, WRITE)) {
            Send<Buffer> done = CompositeBuffer.compose(allocator).send();

            var reader = executor.submit(() -> {
                for (int i = 0; i < 1024; i++) {
                    try (Buffer in = allocator.allocate(1024)) {
                        System.out.println("in = " + in);
                        in.forEachWritable(0, (index, component) -> {
                            var bb = component.writableBuffer();
                            while (bb.hasRemaining()) {
                                input.read(bb);
                            }
                            return true;
                        });
                        System.out.println("Sending " + in.readableBytes() + " bytes.");
                        queue.put(in.send());
                    }
                }
                queue.put(done);
                return null;
            });

            var writer = executor.submit(() -> {
                Send<Buffer> send;
                while ((send = queue.take()) != done) {
                    try (Buffer out = send.receive()) {
                        System.out.println("Received " + out.readableBytes() + " bytes.");
                        out.forEachReadable(0, (index, component) -> {
                            var bb = component.readableBuffer();
                            while (bb.hasRemaining()) {
                                output.write(bb);
                            }
                            return true;
                        });
                    }
                }
                output.force(true);
                return null;
            });

            reader.get();
            writer.get();
        } finally {
            executor.shutdown();
        }
    }
}
