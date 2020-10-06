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

import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public abstract class BufTest {
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
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, buf.copy());
    }

    @Test
    public void acquireOnClosedBufferMustThrow() {
        var buf = allocator.allocate(8);
        buf.close();
        try {
            buf.acquire();
            fail("Expected acquire on closed buffer to throw.");
        } catch (IllegalStateException ignore) {
            // Good.
        }
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
            buf.readerIndex(1);
            fail("Expected an exception to be thrown.");
        } catch (IndexOutOfBoundsException e) {
            // Good.
        }
        buf.writeLong(0);
        try {
            buf.readerIndex(9);
            fail("Expected an exception to be thrown.");
        } catch (IndexOutOfBoundsException e) {
            // Good.
        }

        buf.readerIndex(8);
        try {
            buf.readByte();
            fail("Expected and exception to be thrown.");
        } catch (Exception ignore) {
            // Good.
        }
    }

    @Test
    public void setReaderIndexMustNotThrowWithinBounds() {
        assertSame(buf, buf.readerIndex(0));
        buf.writeLong(0);
        assertSame(buf, buf.readerIndex(7));
        assertSame(buf, buf.readerIndex(8));
    }

    @Test
    public void capacityMustBeAllocatedSize() {
        assertEquals(8, buf.capacity());
        try (Buf b = allocator.allocate(13)) {
            assertEquals(13, b.capacity());
        }
    }

    @Test
    public void fill() {
        try (Buf buf = allocator.allocate(16)) {
            assertSame(buf, buf.fill((byte) 0xA5));
            buf.writerIndex(16);
            assertEquals(0xA5A5A5A5_A5A5A5A5L, buf.readLong());
            assertEquals(0xA5A5A5A5_A5A5A5A5L, buf.readLong());
        }
    }

    @Test
    public void readerWriterIndexUpdates() {
        try (Buf buf = allocator.allocate(22)) {
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
            assertEquals(6, buf.writableBytes());
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
    public void readAndWriteBoundsChecksWithIndexUpdates() {
        try (Buf buf = allocator.allocate(8)) {
            buf.writeLong(0);

            buf.readLong(); // Fine.
            buf.readerIndex(1);
            try {
                buf.readLong();
                fail("Should have bounds checked.");
            } catch (IndexOutOfBoundsException ignore) {
                // Good.
            }

            buf.readerIndex(4);
            buf.readInt(); // Fine.
            buf.readerIndex(5);

            try {
                buf.readInt();
                fail("Should have bounds checked.");
            } catch (IndexOutOfBoundsException ignore) {
                // Good.
            }
        }
    }

    // ### CODEGEN START primitive accessors tests
    // <editor-fold defaultstate="collapsed" desc="Generated primitive accessors tests.">

    @Test
    public void relativeReadOfByteMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        byte value = 0x01;
        buf.writeByte(value);
        assertEquals(1, buf.readableBytes());
        assertEquals(7, buf.writableBytes());
        assertEquals(value, buf.readByte());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfByteMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        byte value = 0x01;
        buf.writeByte(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(1, buf.readableBytes());
        assertEquals(7, buf.writableBytes());
        assertEquals(0x10, buf.readByte());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfByteMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        byte value = 0x01;
        buf.writeByte(value);
        buf.readerIndex(1);
        assertEquals(0, buf.readableBytes());
        assertEquals(7, buf.writableBytes());
        try {
            buf.readByte();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfByteMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readByte(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfByteMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        byte value = 0x01;
        buf.writeByte(value);
        assertEquals(value, buf.readByte(0));
    }

    @Test
    public void offsettedReadOfByteMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        byte value = 0x01;
        buf.writeByte(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x10, buf.readByte(0));
    }

    @Test
    public void offsettedReadOfByteMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        byte value = 0x01;
        buf.writeByte(value);
        try {
            buf.readByte(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfByteMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readByte(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfUnsignedByteMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x01;
        buf.writeUnsignedByte(value);
        assertEquals(1, buf.readableBytes());
        assertEquals(7, buf.writableBytes());
        assertEquals(value, buf.readUnsignedByte());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedByteMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x01;
        buf.writeUnsignedByte(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(1, buf.readableBytes());
        assertEquals(7, buf.writableBytes());
        assertEquals(0x10, buf.readUnsignedByte());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedByteMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x01;
        buf.writeUnsignedByte(value);
        buf.readerIndex(1);
        assertEquals(0, buf.readableBytes());
        assertEquals(7, buf.writableBytes());
        try {
            buf.readUnsignedByte();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfUnsignedByteMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readUnsignedByte(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedByteMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x01;
        buf.writeUnsignedByte(value);
        assertEquals(value, buf.readUnsignedByte(0));
    }

    @Test
    public void offsettedReadOfUnsignedByteMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x01;
        buf.writeUnsignedByte(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x10, buf.readUnsignedByte(0));
    }

    @Test
    public void offsettedReadOfUnsignedByteMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x01;
        buf.writeUnsignedByte(value);
        try {
            buf.readUnsignedByte(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedByteMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readUnsignedByte(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeWriteOfByteMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(8);
        try {
            byte value = 0x01;
            buf.writeByte(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfByteMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        byte value = 0x01;
        buf.writeByte(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfByteMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            byte value = 0x01;
            buf.writeByte(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfByteMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            byte value = 0x01;
            buf.writeByte(8, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfByteMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        byte value = 0x01;
        buf.writeByte(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfUnsignedByteMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(8);
        try {
            int value = 0x01;
            buf.writeUnsignedByte(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfUnsignedByteMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x01;
        buf.writeUnsignedByte(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfUnsignedByteMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x01;
            buf.writeUnsignedByte(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedByteMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x01;
            buf.writeUnsignedByte(8, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedByteMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x01;
        buf.writeUnsignedByte(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeReadOfCharMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        char value = 0x0102;
        buf.writeChar(value);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(value, buf.readChar());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfCharMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        char value = 0x0102;
        buf.writeChar(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(0x1002, buf.readChar());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfCharMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        char value = 0x0102;
        buf.writeChar(value);
        buf.readerIndex(1);
        assertEquals(1, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        try {
            buf.readChar();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(1, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfCharMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readChar(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfCharMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        char value = 0x0102;
        buf.writeChar(value);
        assertEquals(value, buf.readChar(0));
    }

    @Test
    public void offsettedReadOfCharMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        char value = 0x0102;
        buf.writeChar(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x1002, buf.readChar(0));
    }

    @Test
    public void offsettedReadOfCharMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        char value = 0x0102;
        buf.writeChar(value);
        try {
            buf.readChar(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfCharMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readChar(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfCharLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        char value = 0x0102;
        buf.writeCharLE(value);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(value, buf.readCharLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfCharLEMustReadWithLittleEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        char value = 0x0102;
        buf.writeCharLE(value);
        buf.writeByte(1, (byte) 0x10);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(0x1002, buf.readCharLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfCharLEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        char value = 0x0102;
        buf.writeCharLE(value);
        buf.readerIndex(1);
        assertEquals(1, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        try {
            buf.readCharLE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(1, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfCharLEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readCharLE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfCharLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        char value = 0x0102;
        buf.writeCharLE(value);
        assertEquals(value, buf.readCharLE(0));
    }

    @Test
    public void offsettedReadOfCharLEMustReadWithLittleEndianByteOrder() {
        char value = 0x0102;
        buf.writeCharLE(value);
        buf.writeByte(1, (byte) 0x10);
        assertEquals(0x1002, buf.readCharLE(0));
    }

    @Test
    public void offsettedReadOfCharLEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        char value = 0x0102;
        buf.writeCharLE(value);
        try {
            buf.readCharLE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfCharLEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readCharLE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfCharBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        char value = 0x0102;
        buf.writeCharBE(value);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(value, buf.readCharBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfCharBEMustReadWithBigEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        char value = 0x0102;
        buf.writeCharBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(0x1002, buf.readCharBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfCharBEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        char value = 0x0102;
        buf.writeCharBE(value);
        buf.readerIndex(1);
        assertEquals(1, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        try {
            buf.readCharBE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(1, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfCharBEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readCharBE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfCharBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        char value = 0x0102;
        buf.writeCharBE(value);
        assertEquals(value, buf.readCharBE(0));
    }

    @Test
    public void offsettedReadOfCharBEMustReadWithBigEndianByteOrder() {
        char value = 0x0102;
        buf.writeCharBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x1002, buf.readCharBE(0));
    }

    @Test
    public void offsettedReadOfCharBEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        char value = 0x0102;
        buf.writeCharBE(value);
        try {
            buf.readCharBE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfCharBEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readCharBE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeWriteOfCharMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(7);
        try {
            char value = 0x0102;
            buf.writeChar(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfCharMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        char value = 0x0102;
        buf.writeChar(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfCharMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            char value = 0x0102;
            buf.writeChar(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfCharMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            char value = 0x0102;
            buf.writeChar(7, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfCharMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        char value = 0x0102;
        buf.writeChar(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfCharLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(7);
        try {
            char value = 0x0102;
            buf.writeCharLE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfCharLEMustHaveLittleEndianByteOrder() {
        char value = 0x0102;
        buf.writeCharLE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfCharLEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            char value = 0x0102;
            buf.writeCharLE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfCharLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            char value = 0x0102;
            buf.writeCharLE(7, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfCharLEMustHaveLittleEndianByteOrder() {
        char value = 0x0102;
        buf.writeCharLE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfCharBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(7);
        try {
            char value = 0x0102;
            buf.writeCharBE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfCharBEMustHaveBigEndianByteOrder() {
        char value = 0x0102;
        buf.writeCharBE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfCharBEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            char value = 0x0102;
            buf.writeCharBE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfCharBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            char value = 0x0102;
            buf.writeCharBE(7, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfCharBEMustHaveBigEndianByteOrder() {
        char value = 0x0102;
        buf.writeCharBE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeReadOfShortMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        short value = 0x0102;
        buf.writeShort(value);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(value, buf.readShort());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfShortMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        short value = 0x0102;
        buf.writeShort(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(0x1002, buf.readShort());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfShortMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        short value = 0x0102;
        buf.writeShort(value);
        buf.readerIndex(1);
        assertEquals(1, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        try {
            buf.readShort();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(1, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfShortMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readShort(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfShortMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        short value = 0x0102;
        buf.writeShort(value);
        assertEquals(value, buf.readShort(0));
    }

    @Test
    public void offsettedReadOfShortMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        short value = 0x0102;
        buf.writeShort(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x1002, buf.readShort(0));
    }

    @Test
    public void offsettedReadOfShortMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        short value = 0x0102;
        buf.writeShort(value);
        try {
            buf.readShort(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfShortMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readShort(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfShortLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        short value = 0x0102;
        buf.writeShortLE(value);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(value, buf.readShortLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfShortLEMustReadWithLittleEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        short value = 0x0102;
        buf.writeShortLE(value);
        buf.writeByte(1, (byte) 0x10);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(0x1002, buf.readShortLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfShortLEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        short value = 0x0102;
        buf.writeShortLE(value);
        buf.readerIndex(1);
        assertEquals(1, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        try {
            buf.readShortLE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(1, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfShortLEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readShortLE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfShortLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        short value = 0x0102;
        buf.writeShortLE(value);
        assertEquals(value, buf.readShortLE(0));
    }

    @Test
    public void offsettedReadOfShortLEMustReadWithLittleEndianByteOrder() {
        short value = 0x0102;
        buf.writeShortLE(value);
        buf.writeByte(1, (byte) 0x10);
        assertEquals(0x1002, buf.readShortLE(0));
    }

    @Test
    public void offsettedReadOfShortLEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        short value = 0x0102;
        buf.writeShortLE(value);
        try {
            buf.readShortLE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfShortLEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readShortLE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfShortBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        short value = 0x0102;
        buf.writeShortBE(value);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(value, buf.readShortBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfShortBEMustReadWithBigEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        short value = 0x0102;
        buf.writeShortBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(0x1002, buf.readShortBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfShortBEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        short value = 0x0102;
        buf.writeShortBE(value);
        buf.readerIndex(1);
        assertEquals(1, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        try {
            buf.readShortBE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(1, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfShortBEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readShortBE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfShortBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        short value = 0x0102;
        buf.writeShortBE(value);
        assertEquals(value, buf.readShortBE(0));
    }

    @Test
    public void offsettedReadOfShortBEMustReadWithBigEndianByteOrder() {
        short value = 0x0102;
        buf.writeShortBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x1002, buf.readShortBE(0));
    }

    @Test
    public void offsettedReadOfShortBEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        short value = 0x0102;
        buf.writeShortBE(value);
        try {
            buf.readShortBE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfShortBEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readShortBE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfUnsignedShortMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x0102;
        buf.writeUnsignedShort(value);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(value, buf.readUnsignedShort());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedShortMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x0102;
        buf.writeUnsignedShort(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(0x1002, buf.readUnsignedShort());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedShortMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x0102;
        buf.writeUnsignedShort(value);
        buf.readerIndex(1);
        assertEquals(1, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        try {
            buf.readUnsignedShort();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(1, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfUnsignedShortMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readUnsignedShort(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedShortMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x0102;
        buf.writeUnsignedShort(value);
        assertEquals(value, buf.readUnsignedShort(0));
    }

    @Test
    public void offsettedReadOfUnsignedShortMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x0102;
        buf.writeUnsignedShort(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x1002, buf.readUnsignedShort(0));
    }

    @Test
    public void offsettedReadOfUnsignedShortMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x0102;
        buf.writeUnsignedShort(value);
        try {
            buf.readUnsignedShort(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedShortMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readUnsignedShort(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfUnsignedShortLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x0102;
        buf.writeUnsignedShortLE(value);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(value, buf.readUnsignedShortLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedShortLEMustReadWithLittleEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x0102;
        buf.writeUnsignedShortLE(value);
        buf.writeByte(1, (byte) 0x10);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(0x1002, buf.readUnsignedShortLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedShortLEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x0102;
        buf.writeUnsignedShortLE(value);
        buf.readerIndex(1);
        assertEquals(1, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        try {
            buf.readUnsignedShortLE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(1, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfUnsignedShortLEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readUnsignedShortLE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedShortLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x0102;
        buf.writeUnsignedShortLE(value);
        assertEquals(value, buf.readUnsignedShortLE(0));
    }

    @Test
    public void offsettedReadOfUnsignedShortLEMustReadWithLittleEndianByteOrder() {
        int value = 0x0102;
        buf.writeUnsignedShortLE(value);
        buf.writeByte(1, (byte) 0x10);
        assertEquals(0x1002, buf.readUnsignedShortLE(0));
    }

    @Test
    public void offsettedReadOfUnsignedShortLEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x0102;
        buf.writeUnsignedShortLE(value);
        try {
            buf.readUnsignedShortLE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedShortLEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readUnsignedShortLE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfUnsignedShortBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x0102;
        buf.writeUnsignedShortBE(value);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(value, buf.readUnsignedShortBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedShortBEMustReadWithBigEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x0102;
        buf.writeUnsignedShortBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(2, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        assertEquals(0x1002, buf.readUnsignedShortBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedShortBEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x0102;
        buf.writeUnsignedShortBE(value);
        buf.readerIndex(1);
        assertEquals(1, buf.readableBytes());
        assertEquals(6, buf.writableBytes());
        try {
            buf.readUnsignedShortBE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(1, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfUnsignedShortBEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readUnsignedShortBE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedShortBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x0102;
        buf.writeUnsignedShortBE(value);
        assertEquals(value, buf.readUnsignedShortBE(0));
    }

    @Test
    public void offsettedReadOfUnsignedShortBEMustReadWithBigEndianByteOrder() {
        int value = 0x0102;
        buf.writeUnsignedShortBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x1002, buf.readUnsignedShortBE(0));
    }

    @Test
    public void offsettedReadOfUnsignedShortBEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x0102;
        buf.writeUnsignedShortBE(value);
        try {
            buf.readUnsignedShortBE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedShortBEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readUnsignedShortBE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeWriteOfShortMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(7);
        try {
            short value = 0x0102;
            buf.writeShort(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfShortMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        short value = 0x0102;
        buf.writeShort(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfShortMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            short value = 0x0102;
            buf.writeShort(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfShortMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            short value = 0x0102;
            buf.writeShort(7, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfShortMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        short value = 0x0102;
        buf.writeShort(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfShortLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(7);
        try {
            short value = 0x0102;
            buf.writeShortLE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfShortLEMustHaveLittleEndianByteOrder() {
        short value = 0x0102;
        buf.writeShortLE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfShortLEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            short value = 0x0102;
            buf.writeShortLE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfShortLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            short value = 0x0102;
            buf.writeShortLE(7, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfShortLEMustHaveLittleEndianByteOrder() {
        short value = 0x0102;
        buf.writeShortLE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfShortBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(7);
        try {
            short value = 0x0102;
            buf.writeShortBE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfShortBEMustHaveBigEndianByteOrder() {
        short value = 0x0102;
        buf.writeShortBE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfShortBEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            short value = 0x0102;
            buf.writeShortBE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfShortBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            short value = 0x0102;
            buf.writeShortBE(7, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfShortBEMustHaveBigEndianByteOrder() {
        short value = 0x0102;
        buf.writeShortBE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfUnsignedShortMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(7);
        try {
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfUnsignedShortMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x0102;
        buf.writeUnsignedShort(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfUnsignedShortMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x0102;
            buf.writeUnsignedShort(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedShortMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x0102;
            buf.writeUnsignedShort(7, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedShortMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x0102;
        buf.writeUnsignedShort(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfUnsignedShortLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(7);
        try {
            int value = 0x0102;
            buf.writeUnsignedShortLE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfUnsignedShortLEMustHaveLittleEndianByteOrder() {
        int value = 0x0102;
        buf.writeUnsignedShortLE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfUnsignedShortLEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x0102;
            buf.writeUnsignedShortLE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedShortLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x0102;
            buf.writeUnsignedShortLE(7, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedShortLEMustHaveLittleEndianByteOrder() {
        int value = 0x0102;
        buf.writeUnsignedShortLE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfUnsignedShortBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(7);
        try {
            int value = 0x0102;
            buf.writeUnsignedShortBE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfUnsignedShortBEMustHaveBigEndianByteOrder() {
        int value = 0x0102;
        buf.writeUnsignedShortBE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfUnsignedShortBEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x0102;
            buf.writeUnsignedShortBE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedShortBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x0102;
            buf.writeUnsignedShortBE(7, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedShortBEMustHaveBigEndianByteOrder() {
        int value = 0x0102;
        buf.writeUnsignedShortBE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeReadOfMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeMedium(value);
        assertEquals(3, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        assertEquals(value, buf.readMedium());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfMediumMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeMedium(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(3, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        assertEquals(0x100203, buf.readMedium());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfMediumMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeMedium(value);
        buf.readerIndex(1);
        assertEquals(2, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        try {
            buf.readMedium();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(2, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfMediumMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readMedium(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x010203;
        buf.writeMedium(value);
        assertEquals(value, buf.readMedium(0));
    }

    @Test
    public void offsettedReadOfMediumMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x010203;
        buf.writeMedium(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x100203, buf.readMedium(0));
    }

    @Test
    public void offsettedReadOfMediumMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x010203;
        buf.writeMedium(value);
        try {
            buf.readMedium(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfMediumMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readMedium(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfMediumLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeMediumLE(value);
        assertEquals(3, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        assertEquals(value, buf.readMediumLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfMediumLEMustReadWithLittleEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeMediumLE(value);
        buf.writeByte(2, (byte) 0x10);
        assertEquals(3, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        assertEquals(0x100203, buf.readMediumLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfMediumLEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeMediumLE(value);
        buf.readerIndex(1);
        assertEquals(2, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        try {
            buf.readMediumLE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(2, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfMediumLEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readMediumLE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfMediumLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x010203;
        buf.writeMediumLE(value);
        assertEquals(value, buf.readMediumLE(0));
    }

    @Test
    public void offsettedReadOfMediumLEMustReadWithLittleEndianByteOrder() {
        int value = 0x010203;
        buf.writeMediumLE(value);
        buf.writeByte(2, (byte) 0x10);
        assertEquals(0x100203, buf.readMediumLE(0));
    }

    @Test
    public void offsettedReadOfMediumLEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x010203;
        buf.writeMediumLE(value);
        try {
            buf.readMediumLE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfMediumLEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readMediumLE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfMediumBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeMediumBE(value);
        assertEquals(3, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        assertEquals(value, buf.readMediumBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfMediumBEMustReadWithBigEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeMediumBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(3, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        assertEquals(0x100203, buf.readMediumBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfMediumBEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeMediumBE(value);
        buf.readerIndex(1);
        assertEquals(2, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        try {
            buf.readMediumBE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(2, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfMediumBEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readMediumBE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfMediumBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x010203;
        buf.writeMediumBE(value);
        assertEquals(value, buf.readMediumBE(0));
    }

    @Test
    public void offsettedReadOfMediumBEMustReadWithBigEndianByteOrder() {
        int value = 0x010203;
        buf.writeMediumBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x100203, buf.readMediumBE(0));
    }

    @Test
    public void offsettedReadOfMediumBEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x010203;
        buf.writeMediumBE(value);
        try {
            buf.readMediumBE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfMediumBEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readMediumBE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfUnsignedMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeUnsignedMedium(value);
        assertEquals(3, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        assertEquals(value, buf.readUnsignedMedium());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedMediumMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeUnsignedMedium(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(3, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        assertEquals(0x100203, buf.readUnsignedMedium());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedMediumMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeUnsignedMedium(value);
        buf.readerIndex(1);
        assertEquals(2, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        try {
            buf.readUnsignedMedium();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(2, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfUnsignedMediumMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readUnsignedMedium(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x010203;
        buf.writeUnsignedMedium(value);
        assertEquals(value, buf.readUnsignedMedium(0));
    }

    @Test
    public void offsettedReadOfUnsignedMediumMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x010203;
        buf.writeUnsignedMedium(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x100203, buf.readUnsignedMedium(0));
    }

    @Test
    public void offsettedReadOfUnsignedMediumMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x010203;
        buf.writeUnsignedMedium(value);
        try {
            buf.readUnsignedMedium(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedMediumMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readUnsignedMedium(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfUnsignedMediumLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeUnsignedMediumLE(value);
        assertEquals(3, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        assertEquals(value, buf.readUnsignedMediumLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedMediumLEMustReadWithLittleEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeUnsignedMediumLE(value);
        buf.writeByte(2, (byte) 0x10);
        assertEquals(3, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        assertEquals(0x100203, buf.readUnsignedMediumLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedMediumLEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeUnsignedMediumLE(value);
        buf.readerIndex(1);
        assertEquals(2, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        try {
            buf.readUnsignedMediumLE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(2, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfUnsignedMediumLEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readUnsignedMediumLE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedMediumLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x010203;
        buf.writeUnsignedMediumLE(value);
        assertEquals(value, buf.readUnsignedMediumLE(0));
    }

    @Test
    public void offsettedReadOfUnsignedMediumLEMustReadWithLittleEndianByteOrder() {
        int value = 0x010203;
        buf.writeUnsignedMediumLE(value);
        buf.writeByte(2, (byte) 0x10);
        assertEquals(0x100203, buf.readUnsignedMediumLE(0));
    }

    @Test
    public void offsettedReadOfUnsignedMediumLEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x010203;
        buf.writeUnsignedMediumLE(value);
        try {
            buf.readUnsignedMediumLE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedMediumLEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readUnsignedMediumLE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfUnsignedMediumBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeUnsignedMediumBE(value);
        assertEquals(3, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        assertEquals(value, buf.readUnsignedMediumBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedMediumBEMustReadWithBigEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeUnsignedMediumBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(3, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        assertEquals(0x100203, buf.readUnsignedMediumBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedMediumBEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x010203;
        buf.writeUnsignedMediumBE(value);
        buf.readerIndex(1);
        assertEquals(2, buf.readableBytes());
        assertEquals(5, buf.writableBytes());
        try {
            buf.readUnsignedMediumBE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(2, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfUnsignedMediumBEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readUnsignedMediumBE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedMediumBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x010203;
        buf.writeUnsignedMediumBE(value);
        assertEquals(value, buf.readUnsignedMediumBE(0));
    }

    @Test
    public void offsettedReadOfUnsignedMediumBEMustReadWithBigEndianByteOrder() {
        int value = 0x010203;
        buf.writeUnsignedMediumBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x100203, buf.readUnsignedMediumBE(0));
    }

    @Test
    public void offsettedReadOfUnsignedMediumBEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x010203;
        buf.writeUnsignedMediumBE(value);
        try {
            buf.readUnsignedMediumBE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedMediumBEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readUnsignedMediumBE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeWriteOfMediumMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(6);
        try {
            int value = 0x010203;
            buf.writeMedium(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfMediumMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x010203;
        buf.writeMedium(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfMediumMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x010203;
            buf.writeMedium(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfMediumMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x010203;
            buf.writeMedium(6, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfMediumMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x010203;
        buf.writeMedium(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfMediumLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(6);
        try {
            int value = 0x010203;
            buf.writeMediumLE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfMediumLEMustHaveLittleEndianByteOrder() {
        int value = 0x010203;
        buf.writeMediumLE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfMediumLEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x010203;
            buf.writeMediumLE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfMediumLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x010203;
            buf.writeMediumLE(6, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfMediumLEMustHaveLittleEndianByteOrder() {
        int value = 0x010203;
        buf.writeMediumLE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfMediumBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(6);
        try {
            int value = 0x010203;
            buf.writeMediumBE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfMediumBEMustHaveBigEndianByteOrder() {
        int value = 0x010203;
        buf.writeMediumBE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfMediumBEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x010203;
            buf.writeMediumBE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfMediumBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x010203;
            buf.writeMediumBE(6, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfMediumBEMustHaveBigEndianByteOrder() {
        int value = 0x010203;
        buf.writeMediumBE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfUnsignedMediumMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(6);
        try {
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfUnsignedMediumMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x010203;
        buf.writeUnsignedMedium(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfUnsignedMediumMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x010203;
            buf.writeUnsignedMedium(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedMediumMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x010203;
            buf.writeUnsignedMedium(6, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedMediumMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x010203;
        buf.writeUnsignedMedium(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfUnsignedMediumLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(6);
        try {
            int value = 0x010203;
            buf.writeUnsignedMediumLE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfUnsignedMediumLEMustHaveLittleEndianByteOrder() {
        int value = 0x010203;
        buf.writeUnsignedMediumLE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfUnsignedMediumLEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x010203;
            buf.writeUnsignedMediumLE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedMediumLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x010203;
            buf.writeUnsignedMediumLE(6, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedMediumLEMustHaveLittleEndianByteOrder() {
        int value = 0x010203;
        buf.writeUnsignedMediumLE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfUnsignedMediumBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(6);
        try {
            int value = 0x010203;
            buf.writeUnsignedMediumBE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfUnsignedMediumBEMustHaveBigEndianByteOrder() {
        int value = 0x010203;
        buf.writeUnsignedMediumBE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfUnsignedMediumBEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x010203;
            buf.writeUnsignedMediumBE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedMediumBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x010203;
            buf.writeUnsignedMediumBE(6, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedMediumBEMustHaveBigEndianByteOrder() {
        int value = 0x010203;
        buf.writeUnsignedMediumBE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeReadOfIntMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x01020304;
        buf.writeInt(value);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(value, buf.readInt());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfIntMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x01020304;
        buf.writeInt(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(0x10020304, buf.readInt());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfIntMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x01020304;
        buf.writeInt(value);
        buf.readerIndex(1);
        assertEquals(3, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        try {
            buf.readInt();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(3, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfIntMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readInt(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfIntMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x01020304;
        buf.writeInt(value);
        assertEquals(value, buf.readInt(0));
    }

    @Test
    public void offsettedReadOfIntMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x01020304;
        buf.writeInt(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x10020304, buf.readInt(0));
    }

    @Test
    public void offsettedReadOfIntMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x01020304;
        buf.writeInt(value);
        try {
            buf.readInt(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfIntMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readInt(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfIntLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x01020304;
        buf.writeIntLE(value);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(value, buf.readIntLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfIntLEMustReadWithLittleEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x01020304;
        buf.writeIntLE(value);
        buf.writeByte(3, (byte) 0x10);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(0x10020304, buf.readIntLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfIntLEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x01020304;
        buf.writeIntLE(value);
        buf.readerIndex(1);
        assertEquals(3, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        try {
            buf.readIntLE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(3, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfIntLEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readIntLE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfIntLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x01020304;
        buf.writeIntLE(value);
        assertEquals(value, buf.readIntLE(0));
    }

    @Test
    public void offsettedReadOfIntLEMustReadWithLittleEndianByteOrder() {
        int value = 0x01020304;
        buf.writeIntLE(value);
        buf.writeByte(3, (byte) 0x10);
        assertEquals(0x10020304, buf.readIntLE(0));
    }

    @Test
    public void offsettedReadOfIntLEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x01020304;
        buf.writeIntLE(value);
        try {
            buf.readIntLE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfIntLEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readIntLE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfIntBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x01020304;
        buf.writeIntBE(value);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(value, buf.readIntBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfIntBEMustReadWithBigEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x01020304;
        buf.writeIntBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(0x10020304, buf.readIntBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfIntBEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        int value = 0x01020304;
        buf.writeIntBE(value);
        buf.readerIndex(1);
        assertEquals(3, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        try {
            buf.readIntBE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(3, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfIntBEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readIntBE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfIntBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        int value = 0x01020304;
        buf.writeIntBE(value);
        assertEquals(value, buf.readIntBE(0));
    }

    @Test
    public void offsettedReadOfIntBEMustReadWithBigEndianByteOrder() {
        int value = 0x01020304;
        buf.writeIntBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x10020304, buf.readIntBE(0));
    }

    @Test
    public void offsettedReadOfIntBEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        int value = 0x01020304;
        buf.writeIntBE(value);
        try {
            buf.readIntBE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfIntBEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readIntBE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfUnsignedIntMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x01020304;
        buf.writeUnsignedInt(value);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(value, buf.readUnsignedInt());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedIntMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x01020304;
        buf.writeUnsignedInt(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(0x10020304, buf.readUnsignedInt());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedIntMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x01020304;
        buf.writeUnsignedInt(value);
        buf.readerIndex(1);
        assertEquals(3, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        try {
            buf.readUnsignedInt();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(3, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfUnsignedIntMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readUnsignedInt(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedIntMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        long value = 0x01020304;
        buf.writeUnsignedInt(value);
        assertEquals(value, buf.readUnsignedInt(0));
    }

    @Test
    public void offsettedReadOfUnsignedIntMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        long value = 0x01020304;
        buf.writeUnsignedInt(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x10020304, buf.readUnsignedInt(0));
    }

    @Test
    public void offsettedReadOfUnsignedIntMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        long value = 0x01020304;
        buf.writeUnsignedInt(value);
        try {
            buf.readUnsignedInt(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedIntMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readUnsignedInt(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfUnsignedIntLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x01020304;
        buf.writeUnsignedIntLE(value);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(value, buf.readUnsignedIntLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedIntLEMustReadWithLittleEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x01020304;
        buf.writeUnsignedIntLE(value);
        buf.writeByte(3, (byte) 0x10);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(0x10020304, buf.readUnsignedIntLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedIntLEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x01020304;
        buf.writeUnsignedIntLE(value);
        buf.readerIndex(1);
        assertEquals(3, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        try {
            buf.readUnsignedIntLE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(3, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfUnsignedIntLEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readUnsignedIntLE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedIntLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        long value = 0x01020304;
        buf.writeUnsignedIntLE(value);
        assertEquals(value, buf.readUnsignedIntLE(0));
    }

    @Test
    public void offsettedReadOfUnsignedIntLEMustReadWithLittleEndianByteOrder() {
        long value = 0x01020304;
        buf.writeUnsignedIntLE(value);
        buf.writeByte(3, (byte) 0x10);
        assertEquals(0x10020304, buf.readUnsignedIntLE(0));
    }

    @Test
    public void offsettedReadOfUnsignedIntLEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        long value = 0x01020304;
        buf.writeUnsignedIntLE(value);
        try {
            buf.readUnsignedIntLE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedIntLEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readUnsignedIntLE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfUnsignedIntBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x01020304;
        buf.writeUnsignedIntBE(value);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(value, buf.readUnsignedIntBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedIntBEMustReadWithBigEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x01020304;
        buf.writeUnsignedIntBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(0x10020304, buf.readUnsignedIntBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfUnsignedIntBEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x01020304;
        buf.writeUnsignedIntBE(value);
        buf.readerIndex(1);
        assertEquals(3, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        try {
            buf.readUnsignedIntBE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(3, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfUnsignedIntBEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readUnsignedIntBE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedIntBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        long value = 0x01020304;
        buf.writeUnsignedIntBE(value);
        assertEquals(value, buf.readUnsignedIntBE(0));
    }

    @Test
    public void offsettedReadOfUnsignedIntBEMustReadWithBigEndianByteOrder() {
        long value = 0x01020304;
        buf.writeUnsignedIntBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x10020304, buf.readUnsignedIntBE(0));
    }

    @Test
    public void offsettedReadOfUnsignedIntBEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        long value = 0x01020304;
        buf.writeUnsignedIntBE(value);
        try {
            buf.readUnsignedIntBE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfUnsignedIntBEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readUnsignedIntBE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeWriteOfIntMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(5);
        try {
            int value = 0x01020304;
            buf.writeInt(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfIntMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x01020304;
        buf.writeInt(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfIntMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x01020304;
            buf.writeInt(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfIntMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x01020304;
            buf.writeInt(5, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfIntMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        int value = 0x01020304;
        buf.writeInt(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfIntLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(5);
        try {
            int value = 0x01020304;
            buf.writeIntLE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfIntLEMustHaveLittleEndianByteOrder() {
        int value = 0x01020304;
        buf.writeIntLE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfIntLEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x01020304;
            buf.writeIntLE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfIntLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x01020304;
            buf.writeIntLE(5, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfIntLEMustHaveLittleEndianByteOrder() {
        int value = 0x01020304;
        buf.writeIntLE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfIntBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(5);
        try {
            int value = 0x01020304;
            buf.writeIntBE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfIntBEMustHaveBigEndianByteOrder() {
        int value = 0x01020304;
        buf.writeIntBE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfIntBEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x01020304;
            buf.writeIntBE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfIntBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            int value = 0x01020304;
            buf.writeIntBE(5, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfIntBEMustHaveBigEndianByteOrder() {
        int value = 0x01020304;
        buf.writeIntBE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfUnsignedIntMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(5);
        try {
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfUnsignedIntMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        long value = 0x01020304;
        buf.writeUnsignedInt(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfUnsignedIntMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            long value = 0x01020304;
            buf.writeUnsignedInt(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedIntMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            long value = 0x01020304;
            buf.writeUnsignedInt(5, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedIntMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        long value = 0x01020304;
        buf.writeUnsignedInt(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfUnsignedIntLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(5);
        try {
            long value = 0x01020304;
            buf.writeUnsignedIntLE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfUnsignedIntLEMustHaveLittleEndianByteOrder() {
        long value = 0x01020304;
        buf.writeUnsignedIntLE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfUnsignedIntLEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            long value = 0x01020304;
            buf.writeUnsignedIntLE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedIntLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            long value = 0x01020304;
            buf.writeUnsignedIntLE(5, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedIntLEMustHaveLittleEndianByteOrder() {
        long value = 0x01020304;
        buf.writeUnsignedIntLE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfUnsignedIntBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(5);
        try {
            long value = 0x01020304;
            buf.writeUnsignedIntBE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfUnsignedIntBEMustHaveBigEndianByteOrder() {
        long value = 0x01020304;
        buf.writeUnsignedIntBE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfUnsignedIntBEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            long value = 0x01020304;
            buf.writeUnsignedIntBE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedIntBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            long value = 0x01020304;
            buf.writeUnsignedIntBE(5, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfUnsignedIntBEMustHaveBigEndianByteOrder() {
        long value = 0x01020304;
        buf.writeUnsignedIntBE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeReadOfFloatMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloat(value);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(value, buf.readFloat());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfFloatMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloat(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(Float.intBitsToFloat(0x10020304), buf.readFloat());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfFloatMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloat(value);
        buf.readerIndex(1);
        assertEquals(3, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        try {
            buf.readFloat();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(3, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfFloatMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readFloat(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfFloatMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloat(value);
        assertEquals(value, buf.readFloat(0));
    }

    @Test
    public void offsettedReadOfFloatMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloat(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(Float.intBitsToFloat(0x10020304), buf.readFloat(0));
    }

    @Test
    public void offsettedReadOfFloatMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloat(value);
        try {
            buf.readFloat(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfFloatMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readFloat(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfFloatLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatLE(value);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(value, buf.readFloatLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfFloatLEMustReadWithLittleEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatLE(value);
        buf.writeByte(3, (byte) 0x10);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(Float.intBitsToFloat(0x10020304), buf.readFloatLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfFloatLEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatLE(value);
        buf.readerIndex(1);
        assertEquals(3, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        try {
            buf.readFloatLE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(3, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfFloatLEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readFloatLE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfFloatLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatLE(value);
        assertEquals(value, buf.readFloatLE(0));
    }

    @Test
    public void offsettedReadOfFloatLEMustReadWithLittleEndianByteOrder() {
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatLE(value);
        buf.writeByte(3, (byte) 0x10);
        assertEquals(Float.intBitsToFloat(0x10020304), buf.readFloatLE(0));
    }

    @Test
    public void offsettedReadOfFloatLEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatLE(value);
        try {
            buf.readFloatLE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfFloatLEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readFloatLE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfFloatBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatBE(value);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(value, buf.readFloatBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfFloatBEMustReadWithBigEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(4, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        assertEquals(Float.intBitsToFloat(0x10020304), buf.readFloatBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfFloatBEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatBE(value);
        buf.readerIndex(1);
        assertEquals(3, buf.readableBytes());
        assertEquals(4, buf.writableBytes());
        try {
            buf.readFloatBE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(3, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfFloatBEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readFloatBE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfFloatBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatBE(value);
        assertEquals(value, buf.readFloatBE(0));
    }

    @Test
    public void offsettedReadOfFloatBEMustReadWithBigEndianByteOrder() {
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(Float.intBitsToFloat(0x10020304), buf.readFloatBE(0));
    }

    @Test
    public void offsettedReadOfFloatBEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatBE(value);
        try {
            buf.readFloatBE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfFloatBEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readFloatBE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeWriteOfFloatMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(5);
        try {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfFloatMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloat(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfFloatMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfFloatMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(5, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfFloatMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloat(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfFloatLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(5);
        try {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloatLE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfFloatLEMustHaveLittleEndianByteOrder() {
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatLE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfFloatLEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloatLE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfFloatLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloatLE(5, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfFloatLEMustHaveLittleEndianByteOrder() {
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatLE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeWriteOfFloatBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(5);
        try {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloatBE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfFloatBEMustHaveBigEndianByteOrder() {
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatBE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void offsettedWriteOfFloatBEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloatBE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfFloatBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloatBE(5, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfFloatBEMustHaveBigEndianByteOrder() {
        float value = Float.intBitsToFloat(0x01020304);
        buf.writeFloatBE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
        assertEquals((byte) 0x00, buf.readByte());
    }

    @Test
    public void relativeReadOfLongMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x0102030405060708L;
        buf.writeLong(value);
        assertEquals(8, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        assertEquals(value, buf.readLong());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfLongMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x0102030405060708L;
        buf.writeLong(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(8, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        assertEquals(0x1002030405060708L, buf.readLong());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfLongMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x0102030405060708L;
        buf.writeLong(value);
        buf.readerIndex(1);
        assertEquals(7, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        try {
            buf.readLong();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(7, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfLongMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readLong(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfLongMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        long value = 0x0102030405060708L;
        buf.writeLong(value);
        assertEquals(value, buf.readLong(0));
    }

    @Test
    public void offsettedReadOfLongMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        long value = 0x0102030405060708L;
        buf.writeLong(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x1002030405060708L, buf.readLong(0));
    }

    @Test
    public void offsettedReadOfLongMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        long value = 0x0102030405060708L;
        buf.writeLong(value);
        try {
            buf.readLong(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfLongMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readLong(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfLongLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x0102030405060708L;
        buf.writeLongLE(value);
        assertEquals(8, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        assertEquals(value, buf.readLongLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfLongLEMustReadWithLittleEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x0102030405060708L;
        buf.writeLongLE(value);
        buf.writeByte(7, (byte) 0x10);
        assertEquals(8, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        assertEquals(0x1002030405060708L, buf.readLongLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfLongLEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x0102030405060708L;
        buf.writeLongLE(value);
        buf.readerIndex(1);
        assertEquals(7, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        try {
            buf.readLongLE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(7, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfLongLEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readLongLE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfLongLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        long value = 0x0102030405060708L;
        buf.writeLongLE(value);
        assertEquals(value, buf.readLongLE(0));
    }

    @Test
    public void offsettedReadOfLongLEMustReadWithLittleEndianByteOrder() {
        long value = 0x0102030405060708L;
        buf.writeLongLE(value);
        buf.writeByte(7, (byte) 0x10);
        assertEquals(0x1002030405060708L, buf.readLongLE(0));
    }

    @Test
    public void offsettedReadOfLongLEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        long value = 0x0102030405060708L;
        buf.writeLongLE(value);
        try {
            buf.readLongLE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfLongLEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readLongLE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfLongBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x0102030405060708L;
        buf.writeLongBE(value);
        assertEquals(8, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        assertEquals(value, buf.readLongBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfLongBEMustReadWithBigEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x0102030405060708L;
        buf.writeLongBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(8, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        assertEquals(0x1002030405060708L, buf.readLongBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfLongBEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        long value = 0x0102030405060708L;
        buf.writeLongBE(value);
        buf.readerIndex(1);
        assertEquals(7, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        try {
            buf.readLongBE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(7, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfLongBEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readLongBE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfLongBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        long value = 0x0102030405060708L;
        buf.writeLongBE(value);
        assertEquals(value, buf.readLongBE(0));
    }

    @Test
    public void offsettedReadOfLongBEMustReadWithBigEndianByteOrder() {
        long value = 0x0102030405060708L;
        buf.writeLongBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(0x1002030405060708L, buf.readLongBE(0));
    }

    @Test
    public void offsettedReadOfLongBEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        long value = 0x0102030405060708L;
        buf.writeLongBE(value);
        try {
            buf.readLongBE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfLongBEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readLongBE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeWriteOfLongMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(1);
        try {
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfLongMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        long value = 0x0102030405060708L;
        buf.writeLong(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x05, buf.readByte());
        assertEquals((byte) 0x06, buf.readByte());
        assertEquals((byte) 0x07, buf.readByte());
        assertEquals((byte) 0x08, buf.readByte());
    }

    @Test
    public void offsettedWriteOfLongMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            long value = 0x0102030405060708L;
            buf.writeLong(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfLongMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            long value = 0x0102030405060708L;
            buf.writeLong(1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfLongMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        long value = 0x0102030405060708L;
        buf.writeLong(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x05, buf.readByte());
        assertEquals((byte) 0x06, buf.readByte());
        assertEquals((byte) 0x07, buf.readByte());
        assertEquals((byte) 0x08, buf.readByte());
    }

    @Test
    public void relativeWriteOfLongLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(1);
        try {
            long value = 0x0102030405060708L;
            buf.writeLongLE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfLongLEMustHaveLittleEndianByteOrder() {
        long value = 0x0102030405060708L;
        buf.writeLongLE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x08, buf.readByte());
        assertEquals((byte) 0x07, buf.readByte());
        assertEquals((byte) 0x06, buf.readByte());
        assertEquals((byte) 0x05, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
    }

    @Test
    public void offsettedWriteOfLongLEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            long value = 0x0102030405060708L;
            buf.writeLongLE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfLongLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            long value = 0x0102030405060708L;
            buf.writeLongLE(1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfLongLEMustHaveLittleEndianByteOrder() {
        long value = 0x0102030405060708L;
        buf.writeLongLE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x08, buf.readByte());
        assertEquals((byte) 0x07, buf.readByte());
        assertEquals((byte) 0x06, buf.readByte());
        assertEquals((byte) 0x05, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
    }

    @Test
    public void relativeWriteOfLongBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(1);
        try {
            long value = 0x0102030405060708L;
            buf.writeLongBE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfLongBEMustHaveBigEndianByteOrder() {
        long value = 0x0102030405060708L;
        buf.writeLongBE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x05, buf.readByte());
        assertEquals((byte) 0x06, buf.readByte());
        assertEquals((byte) 0x07, buf.readByte());
        assertEquals((byte) 0x08, buf.readByte());
    }

    @Test
    public void offsettedWriteOfLongBEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            long value = 0x0102030405060708L;
            buf.writeLongBE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfLongBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            long value = 0x0102030405060708L;
            buf.writeLongBE(1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfLongBEMustHaveBigEndianByteOrder() {
        long value = 0x0102030405060708L;
        buf.writeLongBE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x05, buf.readByte());
        assertEquals((byte) 0x06, buf.readByte());
        assertEquals((byte) 0x07, buf.readByte());
        assertEquals((byte) 0x08, buf.readByte());
    }

    @Test
    public void relativeReadOfDoubleMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDouble(value);
        assertEquals(8, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        assertEquals(value, buf.readDouble());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfDoubleMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDouble(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(8, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        assertEquals(Double.longBitsToDouble(0x1002030405060708L), buf.readDouble());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfDoubleMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDouble(value);
        buf.readerIndex(1);
        assertEquals(7, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        try {
            buf.readDouble();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(7, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfDoubleMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readDouble(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfDoubleMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDouble(value);
        assertEquals(value, buf.readDouble(0));
    }

    @Test
    public void offsettedReadOfDoubleMustReadWithDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDouble(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(Double.longBitsToDouble(0x1002030405060708L), buf.readDouble(0));
    }

    @Test
    public void offsettedReadOfDoubleMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDouble(value);
        try {
            buf.readDouble(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfDoubleMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readDouble(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfDoubleLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleLE(value);
        assertEquals(8, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        assertEquals(value, buf.readDoubleLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfDoubleLEMustReadWithLittleEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleLE(value);
        buf.writeByte(7, (byte) 0x10);
        assertEquals(8, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        assertEquals(Double.longBitsToDouble(0x1002030405060708L), buf.readDoubleLE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfDoubleLEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleLE(value);
        buf.readerIndex(1);
        assertEquals(7, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        try {
            buf.readDoubleLE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(7, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfDoubleLEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readDoubleLE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfDoubleLEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleLE(value);
        assertEquals(value, buf.readDoubleLE(0));
    }

    @Test
    public void offsettedReadOfDoubleLEMustReadWithLittleEndianByteOrder() {
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleLE(value);
        buf.writeByte(7, (byte) 0x10);
        assertEquals(Double.longBitsToDouble(0x1002030405060708L), buf.readDoubleLE(0));
    }

    @Test
    public void offsettedReadOfDoubleLEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleLE(value);
        try {
            buf.readDoubleLE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfDoubleLEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readDoubleLE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeReadOfDoubleBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleBE(value);
        assertEquals(8, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        assertEquals(value, buf.readDoubleBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfDoubleBEMustReadWithBigEndianByteOrder() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(8, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        assertEquals(Double.longBitsToDouble(0x1002030405060708L), buf.readDoubleBE());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void relativeReadOfDoubleBEMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {
        assertEquals(0, buf.readableBytes());
        assertEquals(Long.BYTES, buf.writableBytes());
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleBE(value);
        buf.readerIndex(1);
        assertEquals(7, buf.readableBytes());
        assertEquals(0, buf.writableBytes());
        try {
            buf.readDoubleBE();
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        assertEquals(7, buf.readableBytes());
    }

    @Test
    public void offsettedReadOfDoubleBEMustBoundsCheckOnNegativeOffset() {
        try {
            buf.readDoubleBE(-1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfDoubleBEMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleBE(value);
        assertEquals(value, buf.readDoubleBE(0));
    }

    @Test
    public void offsettedReadOfDoubleBEMustReadWithBigEndianByteOrder() {
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleBE(value);
        buf.writeByte(0, (byte) 0x10);
        assertEquals(Double.longBitsToDouble(0x1002030405060708L), buf.readDoubleBE(0));
    }

    @Test
    public void offsettedReadOfDoubleBEMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleBE(value);
        try {
            buf.readDoubleBE(1);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void offsettedReadOfDoubleBEMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {
        try {
            buf.readDoubleBE(0);
            fail("Expected a bounds check.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
    }

    @Test
    public void relativeWriteOfDoubleMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(1);
        try {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfDoubleMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDouble(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x05, buf.readByte());
        assertEquals((byte) 0x06, buf.readByte());
        assertEquals((byte) 0x07, buf.readByte());
        assertEquals((byte) 0x08, buf.readByte());
    }

    @Test
    public void offsettedWriteOfDoubleMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfDoubleMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfDoubleMustHaveDefaultEndianByteOrder() {
        buf.order(ByteOrder.BIG_ENDIAN);
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDouble(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x05, buf.readByte());
        assertEquals((byte) 0x06, buf.readByte());
        assertEquals((byte) 0x07, buf.readByte());
        assertEquals((byte) 0x08, buf.readByte());
    }

    @Test
    public void relativeWriteOfDoubleLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(1);
        try {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDoubleLE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfDoubleLEMustHaveLittleEndianByteOrder() {
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleLE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x08, buf.readByte());
        assertEquals((byte) 0x07, buf.readByte());
        assertEquals((byte) 0x06, buf.readByte());
        assertEquals((byte) 0x05, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
    }

    @Test
    public void offsettedWriteOfDoubleLEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDoubleLE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfDoubleLEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDoubleLE(1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfDoubleLEMustHaveLittleEndianByteOrder() {
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleLE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x08, buf.readByte());
        assertEquals((byte) 0x07, buf.readByte());
        assertEquals((byte) 0x06, buf.readByte());
        assertEquals((byte) 0x05, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x01, buf.readByte());
    }

    @Test
    public void relativeWriteOfDoubleBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        buf.writerIndex(1);
        try {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDoubleBE(value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void relativeWriteOfDoubleBEMustHaveBigEndianByteOrder() {
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleBE(value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x05, buf.readByte());
        assertEquals((byte) 0x06, buf.readByte());
        assertEquals((byte) 0x07, buf.readByte());
        assertEquals((byte) 0x08, buf.readByte());
    }

    @Test
    public void offsettedWriteOfDoubleBEMustBoundsCheckWhenWriteOffsetIsNegative() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDoubleBE(-1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfDoubleBEMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {
        assertEquals(Long.BYTES, buf.capacity());
        try {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDoubleBE(1, value);
            fail("Should have bounds checked.");
        } catch (IndexOutOfBoundsException ignore) {
            // Good.
        }
        buf.writerIndex(Long.BYTES);
        // Verify contents are unchanged.
        assertEquals(0, buf.readLong());
    }

    @Test
    public void offsettedWriteOfDoubleBEMustHaveBigEndianByteOrder() {
        double value = Double.longBitsToDouble(0x0102030405060708L);
        buf.writeDoubleBE(0, value);
        buf.writerIndex(Long.BYTES);
        assertEquals((byte) 0x01, buf.readByte());
        assertEquals((byte) 0x02, buf.readByte());
        assertEquals((byte) 0x03, buf.readByte());
        assertEquals((byte) 0x04, buf.readByte());
        assertEquals((byte) 0x05, buf.readByte());
        assertEquals((byte) 0x06, buf.readByte());
        assertEquals((byte) 0x07, buf.readByte());
        assertEquals((byte) 0x08, buf.readByte());
    }
    // </editor-fold>
    // ### CODEGEN END primitive accessors tests

    private static void assertEquals(byte expected, byte actual) {
        if (expected != actual) {
            fail(String.format("expected: %1$s (0x%1$X) but was: %2$s (0x%2$X)", expected, actual));
        }
    }

    private static void assertEquals(char expected, char actual) {
        if (expected != actual) {
            fail(String.format("expected: %s (0x%X) but was: %s (0x%X)",
                               expected, (int) expected, actual, (int) actual));
        }
    }

    private static void assertEquals(short expected, short actual) {
        if (expected != actual) {
            fail(String.format("expected: %1$s (0x%1$X) but was: %2$s (0x%2$X)", expected, actual));
        }
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            fail(String.format("expected: %1$s (0x%1$X) but was: %2$s (0x%2$X)", expected, actual));
        }
    }

    private static void assertEquals(long expected, long actual) {
        if (expected != actual) {
            fail(String.format("expected: %1$s (0x%1$X) but was: %2$s (0x%2$X)", expected, actual));
        }
    }

    private static void assertEquals(float expected, float actual) {
        //noinspection FloatingPointEquality
        if (expected != actual) {
            fail(String.format("expected: %s (0x%X) but was: %s (0x%X)",
                               expected, Float.floatToRawIntBits(expected),
                               actual, Float.floatToRawIntBits(actual)));
        }
    }

    private static void assertEquals(double expected, double actual) {
        //noinspection FloatingPointEquality
        if (expected != actual) {
            fail(String.format("expected: %s (0x%X) but was: %s (0x%X)",
                               expected, Double.doubleToRawLongBits(expected),
                               actual, Double.doubleToRawLongBits(actual)));
        }
    }
}