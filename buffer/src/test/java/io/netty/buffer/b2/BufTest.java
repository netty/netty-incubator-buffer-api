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

import org.junit.After;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public abstract class BufTest {
    protected abstract Allocator createAllocator();

    private Allocator allocator;
    private Buf buf;

    @Before
    public void setUp() {
        allocator = createAllocator();
        buf = allocate(8);
    }

    protected Buf allocate(int size) {
        return allocator.allocate(size);
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
        var buf = allocate(8);
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

        try (Buf buf = allocate(8)) {
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

        try (Buf buf = allocate(8)) {
            assertSame(buf, buf.writeByte((byte) 42));
            queue.put(buf.send());
        }

        assertEquals((byte) 42, future.get().byteValue());
    }

    @Test
    public void sendMustThrowWhenBufIsAcquired() {
        try (Buf buf = allocate(8)) {
            try (Buf ignored = buf.acquire()) {
                try {
                    buf.send();
                    fail("Should not be able to send() a borrowed buffer.");
                } catch (IllegalStateException ignore) {
                    // Good.
                }
            }
            // Now send() should work again.
            buf.send().receive().close();
        }
    }

    @Test
    public void mustThrowWhenAllocatingZeroSizedBuffer() {
        try {
            allocate(0);
            fail("Expected to throw an IllegalArgumentException.");
        } catch (IllegalArgumentException ignore) {
        }
    }

    @Test
    public void mustThrowWhenAllocatingNegativeSizedBuffer() {
        try {
            allocate(-1);
            fail("Expected to throw an IllegalArgumentException.");
        } catch (IllegalArgumentException ignore) {
        }
    }

    @Ignore // This test uses too much memory.
    @Test
    public void mustThrowWhenAllocatingOverSizedBuffer() {
        try {
            allocate(Integer.MAX_VALUE);
            fail("Expected to throw an IllegalArgumentException.");
        } catch (IllegalArgumentException ignore) {
        }
    }

    @Ignore // This test uses too much memory.
    @Test
    public void mustAllowAllocatingMaxArraySizedBuffer() {
        try {
            allocate(Integer.MAX_VALUE - 8).close();
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
        try (Buf b = allocate(13)) {
            assertEquals(13, b.capacity());
        }
    }

    @Test
    public void fill() {
        try (Buf buf = allocate(16)) {
            assertSame(buf, buf.fill((byte) 0xA5));
            buf.writerIndex(16);
            assertEquals(0xA5A5A5A5_A5A5A5A5L, buf.readLong());
            assertEquals(0xA5A5A5A5_A5A5A5A5L, buf.readLong());
        }
    }

    @Test
    public void readerWriterIndexUpdates() {
        try (Buf buf = allocate(22)) {
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
        try (Buf buf = allocate(8)) {
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

    @Test
    public void sliceWithoutOffsetAndSizeMustReturnReadableRegion() {
        try (Buf buf = allocate(8)) {
            for (byte b : new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 }) {
                buf.writeByte(b);
            }
            assertEquals(0x01, buf.readByte());
            buf.writerIndex(buf.writerIndex() - 1);
            try (Buf slice = buf.slice()) {
                assertArrayEquals(new byte[] {0x02, 0x03, 0x04, 0x05, 0x06, 0x07}, slice.copy());
                assertEquals(0, slice.readerIndex());
                assertEquals(6, slice.readableBytes());
                assertEquals(6, slice.writerIndex());
                assertEquals(6, slice.capacity());
                assertEquals(0x02, slice.readByte());
                assertEquals(0x03, slice.readByte());
                assertEquals(0x04, slice.readByte());
                assertEquals(0x05, slice.readByte());
                assertEquals(0x06, slice.readByte());
                assertEquals(0x07, slice.readByte());
                try {
                    slice.readByte();
                    fail("Should have bounds checked.");
                } catch (IndexOutOfBoundsException ignore) {
                    // Good.
                }
            }
        }
    }

    @Test
    public void sliceWithOffsetAndSizeMustReturnGivenRegion() {
        try (Buf buf = allocate(8)) {
            for (byte b : new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 }) {
                buf.writeByte(b);
            }
            buf.readerIndex(3); // Reader and writer offsets must be ignored.
            buf.writerIndex(6);
            try (Buf slice = buf.slice(1, 6)) {
                assertArrayEquals(new byte[] {0x02, 0x03, 0x04, 0x05, 0x06, 0x07}, slice.copy());
                assertEquals(0, slice.readerIndex());
                assertEquals(6, slice.readableBytes());
                assertEquals(6, slice.writerIndex());
                assertEquals(6, slice.capacity());
                assertEquals(0x02, slice.readByte());
                assertEquals(0x03, slice.readByte());
                assertEquals(0x04, slice.readByte());
                assertEquals(0x05, slice.readByte());
                assertEquals(0x06, slice.readByte());
                assertEquals(0x07, slice.readByte());
                try {
                    slice.readByte();
                    fail("Should have bounds checked.");
                } catch (IndexOutOfBoundsException ignore) {
                    // Good.
                }
            }
        }
    }

    @Test
    public void sliceWithoutOffsetAndSizeWillIncreaseReferenceCount() {
        try (Buf buf = allocate(8)) {
            try (Buf ignored = buf.slice()) {
                buf.send();
                fail("Should have refused send() of acquired buffer.");
            } catch (IllegalStateException ignore) {
                // Good.
            }
        }
    }

    @Test
    public void sliceWithOffsetAndSizeWillIncreaseReferenceCount() {
        try (Buf buf = allocate(8)) {
            try (Buf ignored = buf.slice(0, 8)) {
                buf.send();
                fail("Should have refused send() of acquired buffer.");
            } catch (IllegalStateException ignore) {
                // Good.
            }
        }
    }

    @Test
    public void sliceWithoutOffsetAndSizeHasSameEndianAsParent() {
        try (Buf buf = allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.writeLong(0x0102030405060708L);
            try (Buf slice = buf.slice()) {
                assertEquals(0x0102030405060708L, slice.readLong());
            }
            buf.order(ByteOrder.LITTLE_ENDIAN);
            try (Buf slice = buf.slice()) {
                assertEquals(0x0807060504030201L, slice.readLong());
            }
        }
    }

    @Test
    public void sliceWithOffsetAndSizeHasSameEndianAsParent() {
        try (Buf buf = allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.writeLong(0x0102030405060708L);
            try (Buf slice = buf.slice(0, 8)) {
                assertEquals(0x0102030405060708L, slice.readLong());
            }
            buf.order(ByteOrder.LITTLE_ENDIAN);
            try (Buf slice = buf.slice(0, 8)) {
                assertEquals(0x0807060504030201L, slice.readLong());
            }
        }
    }

    @Test
    public void sendOnSliceWithoutOffsetAndSizeMustThrow() {
        try (Buf buf = allocate(8)) {
            try (Buf slice = buf.slice()) {
                slice.send();
                fail("Should not be able to send a slice.");
            } catch (IllegalStateException ignore) {
                // Good.
            }
            // Verify that the slice is closed properly afterwards.
            buf.send().receive().close();
        }
    }

    @Test
    public void sendOnSliceWithOffsetAndSizeMustThrow() {
        try (Buf buf = allocate(8)) {
            try (Buf slice = buf.slice(0, 8)) {
                slice.send();
                fail("Should not be able to send a slice.");
            } catch (IllegalStateException ignore) {
                // Good.
            }
            // Verify that the slice is closed properly afterwards.
            buf.send().receive().close();
        }
    }

    @Test
    public void sliceWithNegativeOffsetMustThrow() {
        try (Buf buf = allocate(8)) {
            try (Buf ignored = buf.slice(-1, 1)) {
                fail("Should not allow negative offsets to slice().");
            } catch (IndexOutOfBoundsException ignore) {
                // Good.
            }
            // Verify that the slice is closed properly afterwards.
            buf.send().receive().close();
        }
    }

    @Test
    public void sliceWithNegativeSizeMustThrow() {
        try (Buf buf = allocate(8)) {
            try (Buf ignored = buf.slice(0, -1)) {
                fail("Should not allow negative size to slice().");
            } catch (IndexOutOfBoundsException ignore) {
                // Good.
            }
            // Verify that the slice is closed properly afterwards.
            buf.send().receive().close();
        }
    }

    @Test
    public void sliceWithSizeGreaterThanCapacityMustThrow() {
        try (Buf buf = allocate(8)) {
            try (Buf ignored = buf.slice(0, 9)) {
                fail("Should not allow slice() size greater than parent capacity.");
            } catch (IndexOutOfBoundsException ignore) {
                // Good.
            }
            buf.slice(0, 8).close(); // This is still fine.
            try (Buf ignored = buf.slice(1, 8)) {
                fail("Should not allow slice() size greater than parent capacity.");
            } catch (IndexOutOfBoundsException ignore) {
                // Good.
            }
            // Verify that the slice is closed properly afterwards.
            buf.send().receive().close();
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