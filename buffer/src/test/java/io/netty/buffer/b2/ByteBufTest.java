package io.netty.buffer.b2;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public abstract class ByteBufTest {
    protected abstract Allocator createAllocator();

    @Test
    public void allocateAndAccessingBuffer() {
        try (Allocator allocator = createAllocator();
             ByteBuf buf = allocator.allocate(8)) {
            buf.put((byte) 1);
            buf.put((byte) 2);
            try (ByteBuf inner = buf.acquire()) {
                inner.put((byte) 3);
                inner.put((byte) 4);
                inner.put((byte) 5);
                inner.put((byte) 6);
                inner.put((byte) 7);
                inner.put((byte) 8);
                try {
                    inner.put((byte) 9);
                    fail("Expected to be out of bounds.");
                } catch (RuntimeException re) {
                    assertThat(re.getMessage(), containsString("bound"));
                }
                try {
                    buf.put((byte) 9);
                    fail("Expected to be out of bounds.");
                } catch (RuntimeException re) {
                    assertThat(re.getMessage(), containsString("bound"));
                }
            }
            assertEquals((byte) 1, buf.get());
            assertEquals((byte) 2, buf.get());
            assertEquals((byte) 3, buf.get());
            assertEquals((byte) 4, buf.get());
            assertEquals((byte) 5, buf.get());
            assertEquals((byte) 6, buf.get());
            assertEquals((byte) 7, buf.get());
            assertEquals((byte) 8, buf.get());
            try {
                assertEquals((byte) 9, buf.get());
                fail("Expected to be out of bounds.");
            } catch (RuntimeException re) {
                assertThat(re.getMessage(), containsString("bound"));
            }
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, buf.debugAsByteArray());
        }
    }

    @Test
    public void allocateAndRendesvousWithThread() throws Exception {
        try (Allocator allocator = createAllocator()) {
            ArrayBlockingQueue<Send<ByteBuf>> queue = new ArrayBlockingQueue<>(10);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Byte> future = executor.submit(() -> {
                try (ByteBuf byteBuf = queue.take().receive()) {
                    return byteBuf.get();
                }
            });
            executor.shutdown();

            try (ByteBuf buf = allocator.allocate(8)) {
                buf.put((byte) 42);
                buf.sendTo(queue::offer);
            }

            assertEquals((byte) 42, future.get().byteValue());
        }
    }

    @Test
    public void allocateAndRendesvousWithThreadViaSyncQueue() throws Exception {
        try (Allocator allocator = createAllocator()) {
            SynchronousQueue<Send<ByteBuf>> queue = new SynchronousQueue<>();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Byte> future = executor.submit(() -> {
                try (ByteBuf byteBuf = queue.take().receive()) {
                    return byteBuf.get();
                }
            });
            executor.shutdown();

            try (ByteBuf buf = allocator.allocate(8)) {
                buf.put((byte) 42);
                buf.sendTo(e -> {
                    try {
                        queue.put(e);
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(ie);
                    }
                });
            }

            assertEquals((byte) 42, future.get().byteValue());
        }
    }

    @Test
    public void allocateAndSendToThread() throws Exception {
        try (Allocator allocator = createAllocator()) {
            ArrayBlockingQueue<Send<ByteBuf>> queue = new ArrayBlockingQueue<>(10);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Byte> future = executor.submit(() -> {
                try (ByteBuf byteBuf = queue.take().receive()) {
                    return byteBuf.get();
                }
            });
            executor.shutdown();

            try (ByteBuf buf = allocator.allocate(8)) {
                buf.put((byte) 42);
                assertTrue(queue.offer(buf.send()));
            }

            assertEquals((byte) 42, future.get().byteValue());
        }
    }

    @Test
    public void allocateAndSendToThreadViaSyncQueue() throws Exception {
        try (Allocator allocator = createAllocator()) {
            SynchronousQueue<Send<ByteBuf>> queue = new SynchronousQueue<>();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Byte> future = executor.submit(() -> {
                try (ByteBuf byteBuf = queue.take().receive()) {
                    return byteBuf.get();
                }
            });
            executor.shutdown();

            try (ByteBuf buf = allocator.allocate(8)) {
                buf.put((byte) 42);
                queue.put(buf.send());
            }

            assertEquals((byte) 42, future.get().byteValue());
        }
    }
}

