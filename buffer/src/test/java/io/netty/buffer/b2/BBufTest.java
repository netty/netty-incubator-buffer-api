/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer.b2;

import org.junit.After;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public abstract class BBufTest {
    protected abstract Allocator createAllocator();

    private Allocator allocator;
    private Buf buf;

    @Before
    public void setUp() {
        allocator = createAllocator();
        buf = allocator.allocate(8);
    }

    @After
    public void tearDown() {
        buf.close();
        allocator.close();
    }

    @Test
    public void allocateAndAccessingBuffer() {
        buf.writeByte((byte) 1);
        buf.writeByte((byte) 2);
        try (Buf inner = buf.acquire()) {
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
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, buf.copy());
    }

    @Test
    public void allocateAndSendToThread() throws Exception {
        ArrayBlockingQueue<Send<Buf>> queue = new ArrayBlockingQueue<>(10);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Byte> future = executor.submit(() -> {
            try (Buf byteBuf = queue.take().receive()) {
                return byteBuf.readByte();
            }
        });
        executor.shutdown();

        try (Buf buf = allocator.allocate(8)) {
            buf.writeByte((byte) 42);
            assertTrue(queue.offer(buf.send()));
        }

        assertEquals((byte) 42, future.get().byteValue());
    }

    @Test
    public void allocateAndSendToThreadViaSyncQueue() throws Exception {
        SynchronousQueue<Send<Buf>> queue = new SynchronousQueue<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Byte> future = executor.submit(() -> {
            try (Buf byteBuf = queue.take().receive()) {
                return byteBuf.readByte();
            }
        });
        executor.shutdown();

        try (Buf buf = allocator.allocate(8)) {
            assertSame(buf, buf.writeByte((byte) 42));
            queue.put(buf.send());
        }

        assertEquals((byte) 42, future.get().byteValue());
    }

    @Test
    public void mustThrowWhenAllocatingZeroSizedBuffer() {
        try {
            allocator.allocate(0);
            fail("Expected to throw an IllegalArgumentException.");
        } catch (IllegalArgumentException ignore) {
        }
    }

    @Test
    public void mustThrowWhenAllocatingNegativeSizedBuffer() {
        try {
            allocator.allocate(-1);
            fail("Expected to throw an IllegalArgumentException.");
        } catch (IllegalArgumentException ignore) {
        }
    }

    @Test
    public void mustThrowWhenAllocatingOverSizedBuffer() {
        try {
            allocator.allocate(Integer.MAX_VALUE);
            fail("Expected to throw an IllegalArgumentException.");
        } catch (IllegalArgumentException ignore) {
        }
    }

    @Test
    public void mustAllowAllocatingMaxArraySizedBuffer() {
        try {
            allocator.allocate(Integer.MAX_VALUE - 8).close();
        } catch (OutOfMemoryError oome) {
            // Mark test as ignored if this happens.
            throw new AssumptionViolatedException("JVM does not have enough memory for this test.", oome);
        }
    }

    @Test
    public void setReaderIndexMustThrowOnNegativeIndex() {
        try {
            buf.readerIndex(-1);
            fail("Expected an exception to be thrown.");
        } catch (IndexOutOfBoundsException e) {
            // Good.
        }
    }

    @Test
    public void setReaderIndexMustThrowOnOversizedIndex() {
        try {
            buf.readerIndex(8);
            fail("Expected an exception to be thrown.");
        } catch (IndexOutOfBoundsException e) {
            // Good.
        }
    }

    @Test
    public void setReaderIndexMustNotThrowWithinBounds() {
        assertSame(buf, buf.readerIndex(0));
        assertSame(buf, buf.readerIndex(7));
    }

    @Test
    public void capacityMustBeAllocatedSize() {
        assertEquals(8, buf.capacity());
        try (Buf b = allocator.allocate(13)) {
            assertEquals(13, b.capacity());
        }
    }

    @Test
    public void readerWriterIndexUpdates() {
        try (Buf buf = allocator.allocate(42)) {
            assertEquals(0, buf.writerIndex());
            assertSame(buf, buf.writerIndex(1));
            assertEquals(1, buf.writerIndex());
            assertSame(buf, buf.writeByte((byte) 7));
            assertEquals(2, buf.writerIndex());
            assertSame(buf, buf.writeShort((short) 3003));
            assertEquals(4, buf.writerIndex());
            assertSame(buf, buf.writeInt(0x5A55_BA55));
            assertEquals(8, buf.writerIndex());
            assertSame(buf, buf.writeLong(0x123456789ABCDEF0L));
            assertEquals(16, buf.writerIndex());
            assertEquals(26, buf.writableBytes());
            assertEquals(16, buf.readableBytes());

            assertEquals(0, buf.readerIndex());
            assertSame(buf, buf.readerIndex(1));
            assertEquals(1, buf.readerIndex());
            assertEquals((byte) 7, buf.readByte());
            assertEquals(2, buf.readerIndex());
            assertEquals((short) 3003, buf.readShort());
            assertEquals(4, buf.readerIndex());
            assertEquals(0x5A55_BA55, buf.readInt());
            assertEquals(8, buf.readerIndex());
            assertEquals(0x123456789ABCDEF0L, buf.readLong());
            assertEquals(16, buf.readerIndex());
            assertEquals(0, buf.readableBytes());
        }
    }

    @Test
    public void fill() {
        try (Buf buf = allocator.allocate(16)) {
            assertSame(buf, buf.fill((byte) 0xA5));
            assertEquals(0xA5A5A5A5_A5A5A5A5L, buf.readLong());
            assertEquals(0xA5A5A5A5_A5A5A5A5L, buf.readLong());
        }
    }

    @Test
    public void readAndWriteAtOffset() {
        try (Buf buf = allocator.allocate(16)) {
            buf.fill((byte) 0xA5);
            assertSame(buf, buf.writeLong(3, 0xBEEF_CA4E_1234_5678L));
            assertEquals(0xBEEF_CA4E_1234_5678L, buf.readLong(3));
            assertEquals(0, buf.readerIndex());
            assertEquals(0, buf.writerIndex());
            assertSame(buf, buf.writeInt(3, 0x1234_5678));
            assertEquals(0x1234_5678, buf.readInt(3));
            assertEquals(0, buf.readerIndex());
            assertEquals(0, buf.writerIndex());
            assertSame(buf, buf.writeShort(3, (short) 0x5678));
            assertEquals((short) 0x5678, buf.readShort(3));
            assertEquals(0, buf.readerIndex());
            assertEquals(0, buf.writerIndex());
            assertSame(buf, buf.writeByte(3, (byte) 0x78));
            assertEquals((byte) 0x78, buf.readByte(3));
            assertEquals(0, buf.readerIndex());
            assertEquals(0, buf.writerIndex());
        }
    }
}

