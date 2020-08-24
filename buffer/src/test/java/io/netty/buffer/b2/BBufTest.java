package io.netty.buffer.b2;

import org.junit.AssumptionViolatedException;
import org.junit.Ignore;
import org.junit.Test;

import java.nio.BufferUnderflowException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public abstract class BBufTest {
    protected abstract Allocator createAllocator();

    @Test
    public void allocateAndAccessingBuffer() {
        try (Allocator allocator = createAllocator();
             BBuf buf = allocator.allocate(8)) {
            buf.writeByte((byte) 1);
            buf.writeByte((byte) 2);
            try (BBuf inner = buf.acquire()) {
                inner.writeByte((byte) 3);
                inner.writeByte((byte) 4);
                inner.writeByte((byte) 5);
                inner.writeByte((byte) 6);
                inner.writeByte((byte) 7);
                inner.writeByte((byte) 8);
                try {
                    inner.writeByte((byte) 9);
                    fail("Expected to be out of bounds.");
                } catch (RuntimeException re) {
                    assertThat(re.getMessage(), containsString("bound"));
                }
                try {
                    buf.writeByte((byte) 9);
                    fail("Expected to be out of bounds.");
                } catch (RuntimeException re) {
                    assertThat(re.getMessage(), containsString("bound"));
                }
            }
            assertEquals((byte) 1, buf.readByte());
            assertEquals((byte) 2, buf.readByte());
            assertEquals((byte) 3, buf.readByte());
            assertEquals((byte) 4, buf.readByte());
            assertEquals((byte) 5, buf.readByte());
            assertEquals((byte) 6, buf.readByte());
            assertEquals((byte) 7, buf.readByte());
            assertEquals((byte) 8, buf.readByte());
            try {
                assertEquals((byte) 9, buf.readByte());
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
            ArrayBlockingQueue<Send<BBuf>> queue = new ArrayBlockingQueue<>(10);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Byte> future = executor.submit(() -> {
                try (BBuf byteBuf = queue.take().receive()) {
                    return byteBuf.readByte();
                }
            });
            executor.shutdown();

            try (BBuf buf = allocator.allocate(8)) {
                buf.writeByte((byte) 42);
                buf.sendTo(queue::offer);
            }

            assertEquals((byte) 42, future.get().byteValue());
        }
    }

    @Test
    public void allocateAndRendesvousWithThreadViaSyncQueue() throws Exception {
        try (Allocator allocator = createAllocator()) {
            SynchronousQueue<Send<BBuf>> queue = new SynchronousQueue<>();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Byte> future = executor.submit(() -> {
                try (BBuf byteBuf = queue.take().receive()) {
                    return byteBuf.readByte();
                }
            });
            executor.shutdown();

            try (BBuf buf = allocator.allocate(8)) {
                buf.writeByte((byte) 42);
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
            ArrayBlockingQueue<Send<BBuf>> queue = new ArrayBlockingQueue<>(10);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Byte> future = executor.submit(() -> {
                try (BBuf byteBuf = queue.take().receive()) {
                    return byteBuf.readByte();
                }
            });
            executor.shutdown();

            try (BBuf buf = allocator.allocate(8)) {
                buf.writeByte((byte) 42);
                assertTrue(queue.offer(buf.send()));
            }

            assertEquals((byte) 42, future.get().byteValue());
        }
    }

    @Test
    public void allocateAndSendToThreadViaSyncQueue() throws Exception {
        try (Allocator allocator = createAllocator()) {
            SynchronousQueue<Send<BBuf>> queue = new SynchronousQueue<>();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Byte> future = executor.submit(() -> {
                try (BBuf byteBuf = queue.take().receive()) {
                    return byteBuf.readByte();
                }
            });
            executor.shutdown();

            try (BBuf buf = allocator.allocate(8)) {
                buf.writeByte((byte) 42);
                queue.put(buf.send());
            }

            assertEquals((byte) 42, future.get().byteValue());
        }
    }

    @Test
    public void mustThrowWhenAllocatingZeroSizedBuffer() {
        try (Allocator allocator = createAllocator()) {
            try {
                allocator.allocate(0);
                fail("Expected to throw an IllegalArgumentException.");
            } catch (IllegalArgumentException ignore) {
            }
        }
    }

    @Test
    public void mustThrowWhenAllocatingNegativeSizedBuffer() {
        try (Allocator allocator = createAllocator()) {
            try {
                allocator.allocate(-1);
                fail("Expected to throw an IllegalArgumentException.");
            } catch (IllegalArgumentException ignore) {
            }
        }
    }

    @Test
    public void mustThrowWhenAllocatingOverSizedBuffer() {
        try (Allocator allocator = createAllocator()) {
            try {
                allocator.allocate(Integer.MAX_VALUE);
                fail("Expected to throw an IllegalArgumentException.");
            } catch (IllegalArgumentException ignore) {
            }
        }
    }

    @Ignore
    @Test
    public void mustAllowAllocatingMaxArraySizedBuffer() {
        try (Allocator allocator = createAllocator()) {
            try {
                allocator.allocate(Integer.MAX_VALUE - 8).close();
            } catch (OutOfMemoryError oome) {
                // Mark test as ignored if this happens.
                throw new AssumptionViolatedException("JVM does not have enough memory for this test.", oome);
            }
        }
    }

    @Test
    public void setReaderIndexMustThrowOnNegativeIndex() {
        try (Allocator allocator = createAllocator();
             BBuf buf = allocator.allocate(8)) {
            try {
                buf.readerIndex(-1);
                fail("Expected an exception to be thrown.");
            } catch (IndexOutOfBoundsException e) {
                // Good.
            }
        }
    }

    @Test
    public void setReaderIndexMustThrowOnOversizedIndex() {
        try (Allocator allocator = createAllocator();
             BBuf buf = allocator.allocate(8)) {
            try {
                buf.readerIndex(8);
                fail("Expected an exception to be thrown.");
            } catch (IndexOutOfBoundsException e) {
                // Good.
            }
        }
    }

    @Test
    public void setReaderIndexMustNotThrowWithinBounds() {
        try (Allocator allocator = createAllocator();
             BBuf buf = allocator.allocate(8)) {
            buf.readerIndex(0);
            buf.readerIndex(7);
        }
    }
}

