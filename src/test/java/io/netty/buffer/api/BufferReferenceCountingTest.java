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
package io.netty.buffer.api;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BufferReferenceCountingTest extends BufferTestSupport {
    @ParameterizedTest
    @MethodSource("allocators")
    void allocateAndAccessingBuffer(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeByte((byte) 1);
            buf.writeByte((byte) 2);
            try (Buffer inner = buf.acquire()) {
                inner.writeByte((byte) 3);
                inner.writeByte((byte) 4);
                inner.writeByte((byte) 5);
                inner.writeByte((byte) 6);
                inner.writeByte((byte) 7);
                inner.writeByte((byte) 8);
                var re = assertThrows(RuntimeException.class, () -> inner.writeByte((byte) 9));
                assertThat(re).hasMessageContaining("bound");
                re = assertThrows(RuntimeException.class, () -> inner.writeByte((byte) 9));
                assertThat(re).hasMessageContaining("bound");
                re = assertThrows(RuntimeException.class, () -> buf.writeByte((byte) 9));
                assertThat(re).hasMessageContaining("bound");
            }
            assertEquals((byte) 1, buf.readByte());
            assertEquals((byte) 2, buf.readByte());
            assertEquals((byte) 3, buf.readByte());
            assertEquals((byte) 4, buf.readByte());
            assertEquals((byte) 5, buf.readByte());
            assertEquals((byte) 6, buf.readByte());
            assertEquals((byte) 7, buf.readByte());
            assertEquals((byte) 8, buf.readByte());
            var re = assertThrows(RuntimeException.class, buf::readByte);
            assertThat(re).hasMessageContaining("bound");
            assertThat(toByteArray(buf)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void acquireOnClosedBufferMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            var buf = allocator.allocate(8);
            buf.close();
            assertThrows(IllegalStateException.class, buf::acquire);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void bufferShouldNotBeAccessibleAfterClose(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            Buffer buf = allocator.allocate(24);
            buf.writeLong(42);
            buf.close();
            verifyInaccessible(buf);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void bufferMustNotBeThreadConfined(Fixture fixture) throws Exception {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeInt(42);
            Future<Integer> fut = executor.submit(() -> buf.readInt());
            assertEquals(42, fut.get());
            fut = executor.submit(() -> {
                buf.writeInt(32);
                return buf.readInt();
            });
            assertEquals(32, fut.get());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithoutOffsetAndSizeMustReturnReadableRegion(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            for (byte b : new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 }) {
                buf.writeByte(b);
            }
            assertEquals(0x01, buf.readByte());
            buf.writerOffset(buf.writerOffset() - 1);
            try (Buffer slice = buf.slice()) {
                assertThat(toByteArray(slice)).containsExactly(0x02, 0x03, 0x04, 0x05, 0x06, 0x07);
                assertEquals(0, slice.readerOffset());
                assertEquals(6, slice.readableBytes());
                assertEquals(6, slice.writerOffset());
                assertEquals(6, slice.capacity());
                assertEquals(0x02, slice.readByte());
                assertEquals(0x03, slice.readByte());
                assertEquals(0x04, slice.readByte());
                assertEquals(0x05, slice.readByte());
                assertEquals(0x06, slice.readByte());
                assertEquals(0x07, slice.readByte());
                assertThrows(IndexOutOfBoundsException.class, slice::readByte);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithOffsetAndSizeMustReturnGivenRegion(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            for (byte b : new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 }) {
                buf.writeByte(b);
            }
            buf.readerOffset(3); // Reader and writer offsets must be ignored.
            buf.writerOffset(6);
            try (Buffer slice = buf.slice(1, 6)) {
                assertThat(toByteArray(slice)).containsExactly(0x02, 0x03, 0x04, 0x05, 0x06, 0x07);
                assertEquals(0, slice.readerOffset());
                assertEquals(6, slice.readableBytes());
                assertEquals(6, slice.writerOffset());
                assertEquals(6, slice.capacity());
                assertEquals(0x02, slice.readByte());
                assertEquals(0x03, slice.readByte());
                assertEquals(0x04, slice.readByte());
                assertEquals(0x05, slice.readByte());
                assertEquals(0x06, slice.readByte());
                assertEquals(0x07, slice.readByte());
                assertThrows(IndexOutOfBoundsException.class, slice::readByte);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithoutOffsetAndSizeWillIncreaseReferenceCount(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int borrows = buf.countBorrows();
            try (Buffer ignored = buf.slice()) {
                assertFalse(buf.isOwned());
                assertThrows(IllegalStateException.class, buf::send);
            }
            assertEquals(borrows, buf.countBorrows());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithOffsetAndSizeWillIncreaseReferenceCount(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int borrows = buf.countBorrows();
            try (Buffer ignored = buf.slice(0, 8)) {
                assertFalse(buf.isOwned());
                assertThrows(IllegalStateException.class, buf::send);
            }
            assertEquals(borrows, buf.countBorrows());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithoutOffsetAndSizeHasSameEndianAsParent(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            buf.writeLong(0x0102030405060708L);
            try (Buffer slice = buf.slice()) {
                assertEquals(0x0102030405060708L, slice.readLong());
            }
            buf.order(LITTLE_ENDIAN);
            try (Buffer slice = buf.slice()) {
                assertEquals(0x0807060504030201L, slice.readLong());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithOffsetAndSizeHasSameEndianAsParent(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            buf.writeLong(0x0102030405060708L);
            try (Buffer slice = buf.slice(0, 8)) {
                assertEquals(0x0102030405060708L, slice.readLong());
            }
            buf.order(LITTLE_ENDIAN);
            try (Buffer slice = buf.slice(0, 8)) {
                assertEquals(0x0807060504030201L, slice.readLong());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sendOnSliceWithoutOffsetAndSizeMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            try (Buffer slice = buf.slice()) {
                assertFalse(buf.isOwned());
                assertThrows(IllegalStateException.class, slice::send);
            }
            // Verify that the slice is closed properly afterwards.
            assertTrue(buf.isOwned());
            buf.send().receive().close();
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sendOnSliceWithOffsetAndSizeMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            try (Buffer slice = buf.slice(0, 8)) {
                assertFalse(buf.isOwned());
                assertThrows(IllegalStateException.class, slice::send);
            }
            // Verify that the slice is closed properly afterwards.
            assertTrue(buf.isOwned());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithNegativeOffsetMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int borrows = buf.countBorrows();
            assertThrows(IndexOutOfBoundsException.class, () -> buf.slice(-1, 1));
            // Verify that the slice is closed properly afterwards.
            assertEquals(borrows, buf.countBorrows());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithNegativeSizeMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int borrows = buf.countBorrows();
            assertThrows(IllegalArgumentException.class, () -> buf.slice(0, -1));
            // Verify that the slice is closed properly afterwards.
            assertEquals(borrows, buf.countBorrows());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithSizeGreaterThanCapacityMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int borrows = buf.countBorrows();
            assertThrows(IndexOutOfBoundsException.class, () -> buf.slice(0, 9));
            buf.slice(0, 8).close(); // This is still fine.
            assertThrows(IndexOutOfBoundsException.class, () -> buf.slice(1, 8));
            // Verify that the slice is closed properly afterwards.
            assertEquals(borrows, buf.countBorrows());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithZeroSizeMustBeAllowed(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int borrows = buf.countBorrows();
            buf.slice(0, 0).close(); // This is fine.
            // Verify that the slice is closed properly afterwards.
            assertEquals(borrows, buf.countBorrows());
        }
    }

    @ParameterizedTest
    @MethodSource("nonCompositeAllocators")
    public void acquireComposingAndSlicingMustIncrementBorrows(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int borrows = buf.countBorrows();
            try (Buffer ignored = buf.acquire()) {
                assertEquals(borrows + 1, buf.countBorrows());
                try (Buffer slice = buf.slice()) {
                    assertEquals(0, slice.capacity()); // We haven't written anything, so the slice is empty.
                    int sliceBorrows = slice.countBorrows();
                    assertEquals(borrows + 2, buf.countBorrows());
                    try (Buffer ignored1 = CompositeBuffer.compose(allocator, buf, slice)) {
                        assertEquals(borrows + 3, buf.countBorrows());
                        // Note: Slice is empty; not acquired by the composite buffer.
                        assertEquals(sliceBorrows, slice.countBorrows());
                    }
                    assertEquals(sliceBorrows, slice.countBorrows());
                    assertEquals(borrows + 2, buf.countBorrows());
                }
                assertEquals(borrows + 1, buf.countBorrows());
            }
            assertEquals(borrows, buf.countBorrows());
        }
    }

    @ParameterizedTest
    @MethodSource("nonCompositeAllocators")
    public void acquireComposingAndSlicingMustIncrementBorrowsWithData(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeByte((byte) 1);
            int borrows = buf.countBorrows();
            try (Buffer ignored = buf.acquire()) {
                assertEquals(borrows + 1, buf.countBorrows());
                try (Buffer slice = buf.slice()) {
                    assertEquals(1, slice.capacity());
                    int sliceBorrows = slice.countBorrows();
                    assertEquals(borrows + 2, buf.countBorrows());
                    try (Buffer ignored1 = CompositeBuffer.compose(allocator, buf, slice)) {
                        assertEquals(borrows + 3, buf.countBorrows());
                        assertEquals(sliceBorrows + 1, slice.countBorrows());
                    }
                    assertEquals(sliceBorrows, slice.countBorrows());
                    assertEquals(borrows + 2, buf.countBorrows());
                }
                assertEquals(borrows + 1, buf.countBorrows());
            }
            assertEquals(borrows, buf.countBorrows());
        }
    }

    @Disabled // TODO
    @ParameterizedTest
    @MethodSource("allocators")
    public void sliceMustBecomeOwnedOnSourceBufferClose(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            Buffer buf = allocator.allocate(8);
            buf.writeInt(42);
            try (Buffer slice = buf.slice()) {
                buf.close();
                assertFalse(buf.isAccessible());
                assertTrue(slice.isOwned());
                try (Buffer receive = slice.send().receive()) {
                    assertTrue(receive.isOwned());
                    assertFalse(slice.isAccessible());
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void pooledBuffersMustResetStateBeforeReuse(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer expected = allocator.allocate(8)) {
            for (int i = 0; i < 10; i++) {
                try (Buffer buf = allocator.allocate(8)) {
                    assertEquals(expected.capacity(), buf.capacity());
                    assertEquals(expected.readableBytes(), buf.readableBytes());
                    assertEquals(expected.readerOffset(), buf.readerOffset());
                    assertEquals(expected.writableBytes(), buf.writableBytes());
                    assertEquals(expected.writerOffset(), buf.writerOffset());
                    assertThat(buf.order()).isEqualTo(expected.order());
                    byte[] bytes = new byte[8];
                    buf.copyInto(0, bytes, 0, 8);
                    assertThat(bytes).containsExactly(0, 0, 0, 0, 0, 0, 0, 0);

                    var tlr = ThreadLocalRandom.current();
                    buf.order(tlr.nextBoolean()? LITTLE_ENDIAN : BIG_ENDIAN);
                    for (int j = 0; j < tlr.nextInt(0, 8); j++) {
                        buf.writeByte((byte) 1);
                    }
                    if (buf.readableBytes() > 0) {
                        for (int j = 0; j < tlr.nextInt(0, buf.readableBytes()); j++) {
                            buf.readByte();
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void splitWithNegativeOffsetMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.split(0).close();
            assertThrows(IllegalArgumentException.class, () -> buf.split(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void splitWithOversizedOffsetMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IllegalArgumentException.class, () -> buf.split(9));
            buf.split(8).close();
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void splitOfNonOwnedBufferMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeInt(1);
            try (Buffer acquired = buf.acquire()) {
                var exc = assertThrows(IllegalStateException.class, () -> acquired.split());
                assertThat(exc).hasMessageContaining("owned");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void splitOnOffsetOfNonOwnedBufferMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            try (Buffer acquired = buf.acquire()) {
                var exc = assertThrows(IllegalStateException.class, () -> acquired.split(4));
                assertThat(exc).hasMessageContaining("owned");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void splitOnOffsetMustTruncateGreaterOffsets(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeInt(0x01020304);
            buf.writeByte((byte) 0x05);
            buf.readInt();
            try (Buffer split = buf.split(2)) {
                assertThat(buf.readerOffset()).isEqualTo(2);
                assertThat(buf.writerOffset()).isEqualTo(3);

                assertThat(split.readerOffset()).isEqualTo(2);
                assertThat(split.writerOffset()).isEqualTo(2);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void splitOnOffsetMustExtendLesserOffsets(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeInt(0x01020304);
            buf.readInt();
            try (Buffer split = buf.split(6)) {
                assertThat(buf.readerOffset()).isEqualTo(0);
                assertThat(buf.writerOffset()).isEqualTo(0);

                assertThat(split.readerOffset()).isEqualTo(4);
                assertThat(split.writerOffset()).isEqualTo(4);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void splitPartMustContainFirstHalfOfBuffer(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(16).order(BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L);
            assertThat(buf.readByte()).isEqualTo((byte) 0x01);
            try (Buffer split = buf.split()) {
                // Original buffer:
                assertThat(buf.capacity()).isEqualTo(8);
                assertThat(buf.readerOffset()).isZero();
                assertThat(buf.writerOffset()).isZero();
                assertThat(buf.readableBytes()).isZero();
                assertThrows(IndexOutOfBoundsException.class, () -> buf.readByte());

                // Split part:
                assertThat(split.capacity()).isEqualTo(8);
                assertThat(split.readerOffset()).isOne();
                assertThat(split.writerOffset()).isEqualTo(8);
                assertThat(split.readableBytes()).isEqualTo(7);
                assertThat(split.readByte()).isEqualTo((byte) 0x02);
                assertThat(split.readInt()).isEqualTo(0x03040506);
                assertThat(split.readByte()).isEqualTo((byte) 0x07);
                assertThat(split.readByte()).isEqualTo((byte) 0x08);
                assertThrows(IndexOutOfBoundsException.class, () -> split.readByte());
            }

            // Split part does NOT return when closed:
            assertThat(buf.capacity()).isEqualTo(8);
            assertThat(buf.readerOffset()).isZero();
            assertThat(buf.writerOffset()).isZero();
            assertThat(buf.readableBytes()).isZero();
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void splitPartsMustBeIndividuallySendable(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(16).order(BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L);
            assertThat(buf.readByte()).isEqualTo((byte) 0x01);
            try (Buffer sentSplit = buf.split().send().receive()) {
                try (Buffer sentBuf = buf.send().receive()) {
                    assertThat(sentBuf.capacity()).isEqualTo(8);
                    assertThat(sentBuf.readerOffset()).isZero();
                    assertThat(sentBuf.writerOffset()).isZero();
                    assertThat(sentBuf.readableBytes()).isZero();
                    assertThrows(IndexOutOfBoundsException.class, () -> sentBuf.readByte());
                }

                assertThat(sentSplit.capacity()).isEqualTo(8);
                assertThat(sentSplit.readerOffset()).isOne();
                assertThat(sentSplit.writerOffset()).isEqualTo(8);
                assertThat(sentSplit.readableBytes()).isEqualTo(7);
                assertThat(sentSplit.readByte()).isEqualTo((byte) 0x02);
                assertThat(sentSplit.readInt()).isEqualTo(0x03040506);
                assertThat(sentSplit.readByte()).isEqualTo((byte) 0x07);
                assertThat(sentSplit.readByte()).isEqualTo((byte) 0x08);
                assertThrows(IndexOutOfBoundsException.class, () -> sentSplit.readByte());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void mustBePossibleToSplitMoreThanOnce(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(16).order(BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L);
            try (Buffer a = buf.split()) {
                a.writerOffset(4);
                try (Buffer b = a.split()) {
                    assertEquals(0x01020304, b.readInt());
                    a.writerOffset(4);
                    assertEquals(0x05060708, a.readInt());
                    assertThrows(IndexOutOfBoundsException.class, () -> b.readByte());
                    assertThrows(IndexOutOfBoundsException.class, () -> a.readByte());
                    buf.writeLong(0xA1A2A3A4A5A6A7A8L);
                    buf.writerOffset(4);
                    try (Buffer c = buf.split()) {
                        assertEquals(0xA1A2A3A4, c.readInt());
                        buf.writerOffset(4);
                        assertEquals(0xA5A6A7A8, buf.readInt());
                        assertThrows(IndexOutOfBoundsException.class, () -> c.readByte());
                        assertThrows(IndexOutOfBoundsException.class, () -> buf.readByte());
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void mustBePossibleToSplitOwnedSlices(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            Buffer buf = allocator.allocate(16).order(BIG_ENDIAN);
            buf.writeLong(0x0102030405060708L);
            try (Buffer slice = buf.slice()) {
                buf.close();
                assertTrue(slice.isOwned());
                try (Buffer split = slice.split(4)) {
                    split.reset().ensureWritable(Long.BYTES);
                    slice.reset().ensureWritable(Long.BYTES);
                    assertThat(split.capacity()).isEqualTo(Long.BYTES);
                    assertThat(slice.capacity()).isEqualTo(Long.BYTES);
                    assertThat(split.getLong(0)).isEqualTo(0x01020304_00000000L);
                    assertThat(slice.getLong(0)).isEqualTo(0x05060708_00000000L);
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void splitBufferMustHaveSameByteOrderAsParent(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8).order(BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L);
            try (Buffer a = buf.split()) {
                assertThat(a.order()).isEqualTo(BIG_ENDIAN);
                a.order(LITTLE_ENDIAN);
                a.writerOffset(4);
                try (Buffer b = a.split()) {
                    assertThat(b.order()).isEqualTo(LITTLE_ENDIAN);
                    assertThat(buf.order()).isEqualTo(BIG_ENDIAN);
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void ensureWritableOnSplitBuffers(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeLong(0x0102030405060708L);
            try (Buffer a = buf.split()) {
                assertEquals(0x0102030405060708L, a.readLong());
                a.ensureWritable(8);
                a.writeLong(0xA1A2A3A4A5A6A7A8L);
                assertEquals(0xA1A2A3A4A5A6A7A8L, a.readLong());

                buf.ensureWritable(8);
                buf.writeLong(0xA1A2A3A4A5A6A7A8L);
                assertEquals(0xA1A2A3A4A5A6A7A8L, buf.readLong());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void ensureWritableOnSplitBuffersWithOddOffsets(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(10).order(BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L);
            buf.writeByte((byte) 0x09);
            buf.readByte();
            try (Buffer a = buf.split()) {
                assertEquals(0x0203040506070809L, a.readLong());
                a.ensureWritable(8);
                a.writeLong(0xA1A2A3A4A5A6A7A8L);
                assertEquals(0xA1A2A3A4A5A6A7A8L, a.readLong());

                buf.ensureWritable(8);
                buf.writeLong(0xA1A2A3A4A5A6A7A8L);
                assertEquals(0xA1A2A3A4A5A6A7A8L, buf.readLong());
            }
        }
    }

    @Test
    public void splitOnEmptyBigEndianCompositeBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer buf = CompositeBuffer.compose(allocator).order(BIG_ENDIAN)) {
            verifySplitEmptyCompositeBuffer(buf);
        }
    }

    @Test
    public void splitOnEmptyLittleEndianCompositeBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer buf = CompositeBuffer.compose(allocator).order(LITTLE_ENDIAN)) {
            verifySplitEmptyCompositeBuffer(buf);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void splitBuffersMustBeAccessibleInOtherThreads(Fixture fixture) throws Exception {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeInt(42);
            var send = buf.split().send();
            var fut = executor.submit(() -> {
                try (Buffer receive = send.receive()) {
                    assertEquals(42, receive.readInt());
                    receive.readerOffset(0).writerOffset(0).writeInt(24);
                    assertEquals(24, receive.readInt());
                }
            });
            fut.get();
            buf.writeInt(32);
            assertEquals(32, buf.readInt());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void acquireOfReadOnlyBufferMustBeReadOnly(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true);
            try (Buffer acquire = buf.acquire()) {
                assertTrue(acquire.readOnly());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void sliceOfReadOnlyBufferMustBeReadOnly(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeLong(0x0102030405060708L);
            buf.readOnly(true);
            try (Buffer slice = buf.slice()) {
                assertTrue(slice.readOnly());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void splitOfReadOnlyBufferMustBeReadOnly(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(16)) {
            buf.writeLong(0x0102030405060708L);
            buf.readOnly(true);
            try (Buffer split = buf.split()) {
                assertTrue(split.readOnly());
                assertTrue(buf.readOnly());
            }
        }
    }
}
