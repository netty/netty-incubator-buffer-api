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
import io.netty.buffer.api.Send;

import java.nio.ByteBuffer;
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
        ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(8);
        Object done = new Object();
        try (Allocator allocator = Allocator.pooledDirect();
             var input = FileChannel.open(Path.of("/dev/urandom"), READ);
             var output = FileChannel.open(Path.of("random.bin"), CREATE, TRUNCATE_EXISTING, WRITE)) {

            var reader = executor.submit(() -> {
                var buf = ByteBuffer.allocateDirect(1024);
                for (int i = 0; i < 1024; i++) {
                    buf.clear();
                    while (buf.hasRemaining()) {
                        int r = input.read(buf);
                        System.out.println("r = " + r);
                        System.out.println("buf = " + buf);
                    }
                    buf.flip();
                    try (Buf in = allocator.allocate(1024)) {
                        System.out.println("in = " + in);
                        while (buf.hasRemaining()) {
                            in.writeByte(buf.get());
                        }
                        System.out.println("Sending " + in.readableBytes() + " bytes.");
                        queue.put(in.send());
                    }
                }
                queue.put(done);
                return null;
            });

            var writer = executor.submit(() -> {
                var buf = ByteBuffer.allocateDirect(1024);
                Object msg;
                while ((msg = queue.take()) != done) {
                    buf.clear();
                    @SuppressWarnings("unchecked")
                    Send<Buf> send = (Send<Buf>) msg;
                    try (Buf out = send.receive()) {
                        System.out.println("Received " + out.readableBytes() + " bytes.");
                        out.copyInto(0, buf, 0, out.readableBytes());
                        buf.position(0).limit(out.readableBytes());
                    }
                    while (buf.hasRemaining()) {
                        output.write(buf);
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
