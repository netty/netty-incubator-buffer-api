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
package io.netty.buffer.api.examples;

import io.netty.buffer.api.BufferAllocator;
import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.Send;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.Executors.newSingleThreadExecutor;

public class SendExample {

    static final class Ex1 {
        public static void main(String[] args) throws Exception {
            ExecutorService executor =
                    newSingleThreadExecutor();
            BufferAllocator allocator = BufferAllocator.heap();

            var future = beginTask(executor, allocator);
            future.get();

            allocator.close();
            executor.shutdown();
        }

        private static Future<?> beginTask(
                ExecutorService executor, BufferAllocator allocator) {
            try (Buffer buf = allocator.allocate(32)) {
                // !!! pit-fall: buffer life-time ends before task completes
                return executor.submit(new Task(buf));
            }
        }

        private static class Task implements Runnable {
            private final Buffer buf;

            Task(Buffer buf) {
                this.buf = buf;
            }

            @Override
            public void run() {
                // !!! danger: access out-side owning thread.
                while (buf.writableBytes() > 0) {
                    buf.writeByte((byte) 42);
                }
            }
        }
    }

    static final class Ex2 {
        public static void main(String[] args) throws Exception {
            ExecutorService executor = newSingleThreadExecutor();
            BufferAllocator allocator = BufferAllocator.heap();

            var future = beginTask(executor, allocator);
            future.get();

            allocator.close();
            executor.shutdown();
        }

        private static Future<?> beginTask(
                ExecutorService executor, BufferAllocator allocator) {
            try (Buffer buf = allocator.allocate(32)) {
                // !!! pit-fall: Rc decrement in other thread.
                return executor.submit(new Task(buf.acquire()));
            }
        }

        private static class Task implements Runnable {
            private final Buffer buf;

            Task(Buffer buf) {
                this.buf = buf;
            }

            @Override
            public void run() {
                try (buf) {
                    // !!! danger: access out-side owning thread.
                    while (buf.writableBytes() > 0) {
                        buf.writeByte((byte) 42);
                    }
                }
            }
        }
    }

    static final class Ex3 {
        public static void main(String[] args) throws Exception {
            ExecutorService executor = newSingleThreadExecutor();
            BufferAllocator allocator = BufferAllocator.heap();

            var future = beginTask(executor, allocator);
            future.get();

            allocator.close();
            executor.shutdown();
        }

        private static Future<?> beginTask(
                ExecutorService executor, BufferAllocator allocator) {
            try (Buffer buf = allocator.allocate(32)) {
                return executor.submit(new Task(buf.send()));
            }
        }

        private static class Task implements Runnable {
            private final Send<Buffer> send;

            Task(Send<Buffer> send) {
                this.send = send;
            }

            @Override
            public void run() {
                try (Buffer buf = send.receive()) {
                    while (buf.writableBytes() > 0) {
                        buf.writeByte((byte) 42);
                    }
                }
            }
        }
    }

    static final class Ex4 {
        public static void main(String[] args) throws Exception {
            ExecutorService executor = newFixedThreadPool(4);
            BufferAllocator allocator = BufferAllocator.heap();

            try (Buffer buf = allocator.allocate(4096)) {
                // !!! pit-fall: Rc decrement in other thread.
                var futA = executor.submit(new Task(buf.slice(0, 1024)));
                var futB = executor.submit(new Task(buf.slice(1024, 1024)));
                var futC = executor.submit(new Task(buf.slice(2048, 1024)));
                var futD = executor.submit(new Task(buf.slice(3072, 1024)));
                futA.get();
                futB.get();
                futC.get();
                futD.get();
            }

            allocator.close();
            executor.shutdown();
        }

        private static class Task implements Runnable {
            private final Buffer slice;

            Task(Buffer slice) {
                this.slice = slice;
            }

            @Override
            public void run() {
                try (slice) {
                    while (slice.writableBytes() > 0) {
                        slice.writeByte((byte) 42);
                    }
                }
            }
        }
    }

    static final class Ex5 {
        public static void main(String[] args) throws Exception {
            ExecutorService executor = newFixedThreadPool(4);
            BufferAllocator allocator = BufferAllocator.heap();

            try (Buffer buf = allocator.allocate(4096);
                 Buffer sliceA = buf.slice(0, 1024);
                 Buffer sliceB = buf.slice(1024, 1024);
                 Buffer sliceC = buf.slice(2048, 1024);
                 Buffer sliceD = buf.slice(3072, 1024)) {
                var futA = executor.submit(new Task(sliceA));
                var futB = executor.submit(new Task(sliceB));
                var futC = executor.submit(new Task(sliceC));
                var futD = executor.submit(new Task(sliceD));
                futA.get();
                futB.get();
                futC.get();
                futD.get();
            }

            allocator.close();
            executor.shutdown();
        }

        private static class Task implements Runnable {
            private final Buffer slice;

            Task(Buffer slice) {
                this.slice = slice;
            }

            @Override
            public void run() {
                while (slice.writableBytes() > 0) {
                    slice.writeByte((byte) 42);
                }
            }
        }
    }

    static final class Ex6 {
        public static void main(String[] args) throws Exception {
            ExecutorService executor = newFixedThreadPool(4);
            BufferAllocator allocator = BufferAllocator.heap();

            try (Buffer buf = allocator.allocate(4096)) {
                var futA = executor.submit(new Task(buf.writerOffset(1024).bifurcate().send()));
                var futB = executor.submit(new Task(buf.writerOffset(1024).bifurcate().send()));
                var futC = executor.submit(new Task(buf.writerOffset(1024).bifurcate().send()));
                var futD = executor.submit(new Task(buf.send()));
                futA.get();
                futB.get();
                futC.get();
                futD.get();
            }

            allocator.close();
            executor.shutdown();
        }

        private static class Task implements Runnable {
            private final Send<Buffer> send;

            Task(Send<Buffer> send) {
                this.send = send;
            }

            @Override
            public void run() {
                try (Buffer buf = send.receive().writerOffset(0)) {
                    while (buf.writableBytes() > 0) {
                        buf.writeByte((byte) 42);
                    }
                }
            }
        }
    }
}
