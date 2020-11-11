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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.ParseException;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.Stream.Builder;

import static io.netty.buffer.b2.Fixture.Properties.CLEANER;
import static io.netty.buffer.b2.Fixture.Properties.COMPOSITE;
import static io.netty.buffer.b2.Fixture.Properties.DIRECT;
import static io.netty.buffer.b2.Fixture.Properties.HEAP;
import static io.netty.buffer.b2.Fixture.Properties.POOLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BufTest {
    private static volatile Fixture[] fixtures;
    private static ExecutorService executor;

    static Fixture[] allocators() {
        Fixture[] fxs = fixtures;
        if (fxs != null) {
            return fxs;
        }
        return fixtures = fixtureCombinations().toArray(Fixture[]::new);
    }

    static Stream<Fixture> heapAllocators() {
        return fixtureCombinations().filter(Fixture::isHeap);
    }

    static Stream<Fixture> directAllocators() {
        return fixtureCombinations().filter(Fixture::isDirect);
    }

    static Stream<Fixture> directWithCleanerAllocators() {
        return fixtureCombinations().filter(f -> f.isDirect() && f.isCleaner());
    }

    static Stream<Fixture> directPooledWithCleanerAllocators() {
        return fixtureCombinations().filter(f -> f.isDirect() && f.isCleaner() && f.isPooled());
    }

    private static Stream<Fixture> fixtureCombinations() {
        Fixture[] fxs = fixtures;
        if (fxs != null) {
            return Arrays.stream(fxs);
        }
        List<Fixture> initFixtures = List.of(
                new Fixture("heap", Allocator::heap, HEAP),
                new Fixture("direct", Allocator::direct, DIRECT),
                new Fixture("directWithCleaner", Allocator::directWithCleaner, DIRECT, CLEANER),
                new Fixture("pooledHeap", Allocator::pooledHeap, POOLED, HEAP),
                new Fixture("pooledDirect", Allocator::pooledDirect, POOLED, DIRECT),
                new Fixture("pooledDirectWithCleaner", Allocator::pooledDirectWithCleaner, POOLED, DIRECT, CLEANER));
        Builder<Fixture> builder = Stream.builder();
        initFixtures.forEach(builder);

        // Add 2-way composite buffers of all combinations.
        for (Fixture first : initFixtures) {
            for (Fixture second : initFixtures) {
                var a = first.get();
                var b = second.get();
                builder.add(new Fixture("compose(" + first + ", " + second + ')', () -> {
                    return new Allocator() {
                        @Override
                        public Buf allocate(int size) {
                            int half = size / 2;
                            try (Buf firstHalf = a.allocate(half);
                                 Buf secondHalf = b.allocate(size - half)) {
                                return Buf.compose(firstHalf, secondHalf);
                            }
                        }

                        @Override
                        public void close() {
                            a.close();
                            b.close();
                        }
                    };
                }, COMPOSITE));
            }
        }

        // Also add a 3-way composite buffer.
        builder.add(new Fixture("compose(heap,heap,heap)", () -> {
            return new Allocator() {
                final Allocator alloc = Allocator.heap();
                @Override
                public Buf allocate(int size) {
                    int part = size / 3;
                    try (Buf a = alloc.allocate(part);
                         Buf b = alloc.allocate(part);
                         Buf c = alloc.allocate(size - part * 2)) {
                        return Buf.compose(a, b, c);
                    }
                }

                @Override
                public void close() {
                    alloc.close();
                }
            };
        }, COMPOSITE));
        return builder.build();
    }

    @BeforeAll
    static void startExecutor() throws IOException, ParseException {
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterAll
    static void stopExecutor() throws IOException {
        executor.shutdown();
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void allocateAndAccessingBuffer(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.writeByte((byte) 1);
            buf.writeByte((byte) 2);
            try (Buf inner = buf.acquire()) {
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
        try (Allocator allocator = fixture.createAllocator()) {
            var buf = allocator.allocate(8);
            buf.close();
            assertThrows(IllegalStateException.class, buf::acquire);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void allocateAndSendToThread(Fixture fixture) throws Exception {
        try (Allocator allocator = fixture.createAllocator()) {
            ArrayBlockingQueue<Send<Buf>> queue = new ArrayBlockingQueue<>(10);
            Future<Byte> future = executor.submit(() -> {
                try (Buf byteBuf = queue.take().receive()) {
                    return byteBuf.readByte();
                }
            });

            try (Buf buf = allocator.allocate(8)) {
                buf.writeByte((byte) 42);
                assertTrue(queue.offer(buf.send()));
            }

            assertEquals((byte) 42, future.get().byteValue());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void allocateAndSendToThreadViaSyncQueue(Fixture fixture) throws Exception {
        SynchronousQueue<Send<Buf>> queue = new SynchronousQueue<>();
        Future<Byte> future = executor.submit(() -> {
            try (Buf byteBuf = queue.take().receive()) {
                return byteBuf.readByte();
            }
        });

        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThat(buf.writeByte((byte) 42)).isSameAs(buf);
            queue.put(buf.send());
        }

        assertEquals((byte) 42, future.get().byteValue());
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sendMustThrowWhenBufIsAcquired(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            try (Buf ignored = buf.acquire()) {
                assertFalse(buf.isSendable());
                assertThrows(IllegalStateException.class, buf::send);
            }
            // Now send() should work again.
            assertTrue(buf.isSendable());
            buf.send().receive().close();
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void mustThrowWhenAllocatingZeroSizedBuffer(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator()) {
            assertThrows(IllegalArgumentException.class, () -> allocator.allocate(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void mustThrowWhenAllocatingNegativeSizedBuffer(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator()) {
            assertThrows(IllegalArgumentException.class, () -> allocator.allocate(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void setReaderOffsetMustThrowOnNegativeIndex(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readerOffset(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void setReaderOffsetMustThrowOnOversizedIndex(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readerOffset(1));
            buf.writeLong(0);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readerOffset(9));

            buf.readerOffset(8);
            assertThrows(IndexOutOfBoundsException.class, buf::readByte);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void setReaderOffsetMustNotThrowWithinBounds(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThat(buf.readerOffset(0)).isSameAs(buf);
            buf.writeLong(0);
            assertThat(buf.readerOffset(7)).isSameAs(buf);
            assertThat(buf.readerOffset(8)).isSameAs(buf);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void capacityMustBeAllocatedSize(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(8, buf.capacity());
            try (Buf b = allocator.allocate(13)) {
                assertEquals(13, b.capacity());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void fill(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(16)) {
            assertThat(buf.fill((byte) 0xA5)).isSameAs(buf);
            buf.writerOffset(16);
            assertEquals(0xA5A5A5A5_A5A5A5A5L, buf.readLong());
            assertEquals(0xA5A5A5A5_A5A5A5A5L, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void readerWriterOffsetUpdates(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(22)) {
            assertEquals(0, buf.writerOffset());
            assertThat(buf.writerOffset(1)).isSameAs(buf);
            assertEquals(1, buf.writerOffset());
            assertThat(buf.writeByte((byte) 7)).isSameAs(buf);
            assertEquals(2, buf.writerOffset());
            assertThat(buf.writeShort((short) 3003)).isSameAs(buf);
            assertEquals(4, buf.writerOffset());
            assertThat(buf.writeInt(0x5A55_BA55)).isSameAs(buf);
            assertEquals(8, buf.writerOffset());
            assertThat(buf.writeLong(0x123456789ABCDEF0L)).isSameAs(buf);
            assertEquals(16, buf.writerOffset());
            assertEquals(6, buf.writableBytes());
            assertEquals(16, buf.readableBytes());

            assertEquals(0, buf.readerOffset());
            assertThat(buf.readerOffset(1)).isSameAs(buf);
            assertEquals(1, buf.readerOffset());
            assertEquals((byte) 7, buf.readByte());
            assertEquals(2, buf.readerOffset());
            assertEquals((short) 3003, buf.readShort());
            assertEquals(4, buf.readerOffset());
            assertEquals(0x5A55_BA55, buf.readInt());
            assertEquals(8, buf.readerOffset());
            assertEquals(0x123456789ABCDEF0L, buf.readLong());
            assertEquals(16, buf.readerOffset());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void readAndWriteBoundsChecksWithIndexUpdates(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.writeLong(0);

            buf.readLong(); // Fine.
            buf.readerOffset(1);
            assertThrows(IndexOutOfBoundsException.class, buf::readLong);

            buf.readerOffset(4);
            buf.readInt(); // Fine.
            buf.readerOffset(5);

            assertThrows(IndexOutOfBoundsException.class, buf::readInt);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void resetMustSetReaderAndWriterOffsetsToTheirInitialPositions(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.writeInt(0).readShort();
            buf.reset();
            assertEquals(0, buf.readerOffset());
            assertEquals(0, buf.writerOffset());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithoutOffsetAndSizeMustReturnReadableRegion(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            for (byte b : new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 }) {
                buf.writeByte(b);
            }
            assertEquals(0x01, buf.readByte());
            buf.writerOffset(buf.writerOffset() - 1);
            try (Buf slice = buf.slice()) {
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
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            for (byte b : new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 }) {
                buf.writeByte(b);
            }
            buf.readerOffset(3); // Reader and writer offsets must be ignored.
            buf.writerOffset(6);
            try (Buf slice = buf.slice(1, 6)) {
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
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            try (Buf ignored = buf.slice()) {
                assertFalse(buf.isSendable());
                assertThrows(IllegalStateException.class, buf::send);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithOffsetAndSizeWillIncreaseReferenceCount(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            try (Buf ignored = buf.slice(0, 8)) {
                assertFalse(buf.isSendable());
                assertThrows(IllegalStateException.class, buf::send);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithoutOffsetAndSizeHasSameEndianAsParent(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
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

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithOffsetAndSizeHasSameEndianAsParent(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
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

    @ParameterizedTest
    @MethodSource("allocators")
    void sendOnSliceWithoutOffsetAndSizeMustThrow(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            try (Buf slice = buf.slice()) {
                assertFalse(buf.isSendable());
                assertThrows(IllegalStateException.class, slice::send);
            }
            // Verify that the slice is closed properly afterwards.
            assertTrue(buf.isSendable());
            buf.send().receive().close();
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sendOnSliceWithOffsetAndSizeMustThrow(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            try (Buf slice = buf.slice(0, 8)) {
                assertFalse(buf.isSendable());
                assertThrows(IllegalStateException.class, slice::send);
            }
            // Verify that the slice is closed properly afterwards.
            assertTrue(buf.isSendable());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithNegativeOffsetMustThrow(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.slice(-1, 1));
            // Verify that the slice is closed properly afterwards.
            assertTrue(buf.isSendable());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithNegativeSizeMustThrow(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.slice(0, -1));
            // Verify that the slice is closed properly afterwards.
            assertTrue(buf.isSendable());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sliceWithSizeGreaterThanCapacityMustThrow(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.slice(0, 9));
            buf.slice(0, 8).close(); // This is still fine.
            assertThrows(IndexOutOfBoundsException.class, () -> buf.slice(1, 8));
            // Verify that the slice is closed properly afterwards.
            assertTrue(buf.isSendable());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoByteArray(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN).writeLong(0x0102030405060708L);
            byte[] array = new byte[8];
            buf.copyInto(0, array, 0, array.length);
            assertThat(array).containsExactly(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08);

            buf.writerOffset(0).order(ByteOrder.LITTLE_ENDIAN).writeLong(0x0102030405060708L);
            buf.copyInto(0, array, 0, array.length);
            assertThat(array).containsExactly(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01);

            array = new byte[6];
            buf.copyInto(1, array, 1, 3);
            assertThat(array).containsExactly(0x00, 0x07, 0x06, 0x05, 0x00, 0x00);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoHeapByteBuffer(Fixture fixture) {
        testCopyIntoByteBuffer(fixture, ByteBuffer::allocate);
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoDirectByteBuffer(Fixture fixture) {
        testCopyIntoByteBuffer(fixture, ByteBuffer::allocateDirect);
    }

    private static void testCopyIntoByteBuffer(Fixture fixture, Function<Integer, ByteBuffer> bbAlloc) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN).writeLong(0x0102030405060708L);
            ByteBuffer buffer = bbAlloc.apply(8);
            buf.copyInto(0, buffer, 0, buffer.capacity());
            assertEquals((byte) 0x01, buffer.get());
            assertEquals((byte) 0x02, buffer.get());
            assertEquals((byte) 0x03, buffer.get());
            assertEquals((byte) 0x04, buffer.get());
            assertEquals((byte) 0x05, buffer.get());
            assertEquals((byte) 0x06, buffer.get());
            assertEquals((byte) 0x07, buffer.get());
            assertEquals((byte) 0x08, buffer.get());
            buffer.clear();

            buf.writerOffset(0).order(ByteOrder.LITTLE_ENDIAN).writeLong(0x0102030405060708L);
            buf.copyInto(0, buffer, 0, buffer.capacity());
            assertEquals((byte) 0x08, buffer.get());
            assertEquals((byte) 0x07, buffer.get());
            assertEquals((byte) 0x06, buffer.get());
            assertEquals((byte) 0x05, buffer.get());
            assertEquals((byte) 0x04, buffer.get());
            assertEquals((byte) 0x03, buffer.get());
            assertEquals((byte) 0x02, buffer.get());
            assertEquals((byte) 0x01, buffer.get());
            buffer.clear();

            buffer = bbAlloc.apply(6);
            buf.copyInto(1, buffer, 1, 3);
            assertEquals((byte) 0x00, buffer.get());
            assertEquals((byte) 0x07, buffer.get());
            assertEquals((byte) 0x06, buffer.get());
            assertEquals((byte) 0x05, buffer.get());
            assertEquals((byte) 0x00, buffer.get());
            assertEquals((byte) 0x00, buffer.get());
            buffer.clear();

            buffer = bbAlloc.apply(6);
            buffer.position(3).limit(3);
            buf.copyInto(1, buffer, 1, 3);
            assertEquals(3, buffer.position());
            assertEquals(3, buffer.limit());
            buffer.clear();
            assertEquals((byte) 0x00, buffer.get());
            assertEquals((byte) 0x07, buffer.get());
            assertEquals((byte) 0x06, buffer.get());
            assertEquals((byte) 0x05, buffer.get());
            assertEquals((byte) 0x00, buffer.get());
            assertEquals((byte) 0x00, buffer.get());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoOnHeapBuf(Fixture fixture) {
        testCopyIntoBuf(fixture, Allocator.heap()::allocate);
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoOffHeapBuf(Fixture fixture) {
        testCopyIntoBuf(fixture, Allocator.direct()::allocate);
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoOnHeapBufSlice(Fixture fixture) {
        try (Scope scope = new Scope()) {
            testCopyIntoBuf(fixture, size -> scope.add(Allocator.heap().allocate(size)).writerOffset(size).slice());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoOffHeapBufSlice(Fixture fixture) {
        try (Scope scope = new Scope()) {
            testCopyIntoBuf(fixture, size -> scope.add(Allocator.direct().allocate(size)).writerOffset(size).slice());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOnHeapOnHeapBuf(Fixture fixture) {
        try (var a = Allocator.heap();
             var b = Allocator.heap()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return Buf.compose(bufFirst, bufSecond);
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOnHeapOffHeapBuf(Fixture fixture) {
        try (var a = Allocator.heap();
             var b = Allocator.direct()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return Buf.compose(bufFirst, bufSecond);
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOffHeapOnHeapBuf(Fixture fixture) {
        try (var a = Allocator.direct();
             var b = Allocator.heap()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return Buf.compose(bufFirst, bufSecond);
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOffHeapOffHeapBuf(Fixture fixture) {
        try (var a = Allocator.direct();
             var b = Allocator.direct()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return Buf.compose(bufFirst, bufSecond);
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOnHeapOnHeapBufSlice(Fixture fixture) {
        try (var a = Allocator.heap();
             var b = Allocator.heap();
             var scope = new Scope()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return scope.add(Buf.compose(bufFirst, bufSecond)).writerOffset(size).slice();
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOnHeapOffHeapBufSlice(Fixture fixture) {
        try (var a = Allocator.heap();
             var b = Allocator.direct();
             var scope = new Scope()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return scope.add(Buf.compose(bufFirst, bufSecond)).writerOffset(size).slice();
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOffHeapOnHeapBufSlice(Fixture fixture) {
        try (var a = Allocator.direct();
             var b = Allocator.heap();
             var scope = new Scope()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return scope.add(Buf.compose(bufFirst, bufSecond)).writerOffset(size).slice();
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOffHeapOffHeapBufSlice(Fixture fixture) {
        try (var a = Allocator.direct();
             var b = Allocator.direct();
             var scope = new Scope()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return scope.add(Buf.compose(bufFirst, bufSecond)).writerOffset(size).slice();
                }
            });
        }
    }

    private static void testCopyIntoBuf(Fixture fixture, Function<Integer, Buf> bbAlloc) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN).writeLong(0x0102030405060708L);
            Buf buffer = bbAlloc.apply(8);
            buffer.writerOffset(8);
            buf.copyInto(0, buffer, 0, buffer.capacity());
            assertEquals((byte) 0x01, buffer.readByte());
            assertEquals((byte) 0x02, buffer.readByte());
            assertEquals((byte) 0x03, buffer.readByte());
            assertEquals((byte) 0x04, buffer.readByte());
            assertEquals((byte) 0x05, buffer.readByte());
            assertEquals((byte) 0x06, buffer.readByte());
            assertEquals((byte) 0x07, buffer.readByte());
            assertEquals((byte) 0x08, buffer.readByte());
            buffer.reset();

            buf.writerOffset(0).order(ByteOrder.LITTLE_ENDIAN).writeLong(0x0102030405060708L);
            buf.copyInto(0, buffer, 0, buffer.capacity());
            buffer.writerOffset(8);
            assertEquals((byte) 0x08, buffer.readByte());
            assertEquals((byte) 0x07, buffer.readByte());
            assertEquals((byte) 0x06, buffer.readByte());
            assertEquals((byte) 0x05, buffer.readByte());
            assertEquals((byte) 0x04, buffer.readByte());
            assertEquals((byte) 0x03, buffer.readByte());
            assertEquals((byte) 0x02, buffer.readByte());
            assertEquals((byte) 0x01, buffer.readByte());
            buffer.reset();

            buffer.close();
            buffer = bbAlloc.apply(6);
            buf.copyInto(1, buffer, 1, 3);
            buffer.writerOffset(6);
            assertEquals((byte) 0x00, buffer.readByte());
            assertEquals((byte) 0x07, buffer.readByte());
            assertEquals((byte) 0x06, buffer.readByte());
            assertEquals((byte) 0x05, buffer.readByte());
            assertEquals((byte) 0x00, buffer.readByte());
            assertEquals((byte) 0x00, buffer.readByte());

            buffer.close();
            buffer = bbAlloc.apply(6);
            buffer.writerOffset(3).readerOffset(3);
            buf.copyInto(1, buffer, 1, 3);
            assertEquals(3, buffer.readerOffset());
            assertEquals(3, buffer.writerOffset());
            buffer.reset();
            buffer.writerOffset(6);
            assertEquals((byte) 0x00, buffer.readByte());
            assertEquals((byte) 0x07, buffer.readByte());
            assertEquals((byte) 0x06, buffer.readByte());
            assertEquals((byte) 0x05, buffer.readByte());
            assertEquals((byte) 0x00, buffer.readByte());
            assertEquals((byte) 0x00, buffer.readByte());
            buffer.close();

            buf.reset();
            buf.order(ByteOrder.BIG_ENDIAN).writeLong(0x0102030405060708L);
            // Testing copyInto for overlapping writes:
            //
            //          0x0102030405060708
            //            └──┬──┬──┘     │
            //               └─▶└┬───────┘
            //                   ▼
            //          0x0102030102030405
            buf.copyInto(0, buf, 3, 5);
            assertThat(toByteArray(buf)).containsExactly(0x01, 0x02, 0x03, 0x01, 0x02, 0x03, 0x04, 0x05);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void readableBytesMustMatchWhatWasWritten(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(16)) {
            buf.writeLong(0);
            assertEquals(Long.BYTES, buf.readableBytes());
            buf.readShort();
            assertEquals(Long.BYTES - Short.BYTES, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void byteIterationOfBigEndianBuffers(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(0x28)) {
            buf.order(ByteOrder.BIG_ENDIAN); // The byte order should have no impact.
            checkByteIteration(buf);
            buf.reset();
            checkByteIterationOfRegion(buf);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void byteIterationOfLittleEndianBuffers(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(0x28)) {
            buf.order(ByteOrder.LITTLE_ENDIAN); // The byte order should have no impact.
            checkByteIteration(buf);
            buf.reset();
            checkByteIterationOfRegion(buf);
        }
    }

    private static void checkByteIteration(Buf buf) {
        var itr = buf.iterate();
        assertFalse(itr.hasNextByte());
        assertFalse(itr.hasNextLong());
        assertEquals(0, itr.bytesLeft());
        assertThrows(NoSuchElementException.class, itr::nextLong);
        assertThrows(NoSuchElementException.class, itr::nextByte);

        for (int i = 0; i < 0x27; i++) {
            buf.writeByte((byte) (i + 1));
        }
        int roff = buf.readerOffset();
        int woff = buf.writerOffset();
        itr = buf.iterate();
        assertEquals(0x27, itr.bytesLeft());
        assertTrue(itr.hasNextByte());
        assertTrue(itr.hasNextLong());
        assertEquals(0x0102030405060708L, itr.nextLong());
        assertEquals(0x1F, itr.bytesLeft());
        assertTrue(itr.hasNextLong());
        assertEquals(0x090A0B0C0D0E0F10L, itr.nextLong());
        assertTrue(itr.hasNextLong());
        assertEquals(0x17, itr.bytesLeft());
        assertEquals(0x1112131415161718L, itr.nextLong());
        assertTrue(itr.hasNextLong());
        assertEquals(0x0F, itr.bytesLeft());
        assertEquals(0x191A1B1C1D1E1F20L, itr.nextLong());
        assertFalse(itr.hasNextLong());
        assertEquals(7, itr.bytesLeft());
        assertThrows(NoSuchElementException.class, itr::nextLong);
        assertTrue(itr.hasNextByte());
        assertEquals((byte) 0x21, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(6, itr.bytesLeft());
        assertEquals((byte) 0x22, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(5, itr.bytesLeft());
        assertEquals((byte) 0x23, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(4, itr.bytesLeft());
        assertEquals((byte) 0x24, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(3, itr.bytesLeft());
        assertEquals((byte) 0x25, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(2, itr.bytesLeft());
        assertEquals((byte) 0x26, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(1, itr.bytesLeft());
        assertEquals((byte) 0x27, itr.nextByte());
        assertFalse(itr.hasNextByte());
        assertEquals(0, itr.bytesLeft());
        assertThrows(NoSuchElementException.class, itr::nextLong);
        assertThrows(NoSuchElementException.class, itr::nextByte);
        assertEquals(roff, buf.readerOffset());
        assertEquals(woff, buf.writerOffset());
    }

    private static void checkByteIterationOfRegion(Buf buf) {
        assertThrows(IllegalArgumentException.class, () -> buf.iterate(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> buf.iterate(1, -1));
        assertThrows(IllegalArgumentException.class, () -> buf.iterate(buf.capacity(), 1));
        assertThrows(IllegalArgumentException.class, () -> buf.iterate(buf.capacity() - 1, 2));
        assertThrows(IllegalArgumentException.class, () -> buf.iterate(buf.capacity() - 2, 3));

        var itr = buf.iterate(1, 0);
        assertFalse(itr.hasNextByte());
        assertFalse(itr.hasNextLong());
        assertEquals(0, itr.bytesLeft());
        assertThrows(NoSuchElementException.class, itr::nextLong);
        assertThrows(NoSuchElementException.class, itr::nextByte);

        for (int i = 0; i < 0x27; i++) {
            buf.writeByte((byte) (i + 1));
        }
        int roff = buf.readerOffset();
        int woff = buf.writerOffset();
        itr = buf.iterate(buf.readerOffset() + 1, buf.readableBytes() - 2);
        assertEquals(0x25, itr.bytesLeft());
        assertTrue(itr.hasNextByte());
        assertTrue(itr.hasNextLong());
        assertEquals(0x0203040506070809L, itr.nextLong());
        assertEquals(0x1D, itr.bytesLeft());
        assertTrue(itr.hasNextLong());
        assertEquals(0x0A0B0C0D0E0F1011L, itr.nextLong());
        assertTrue(itr.hasNextLong());
        assertEquals(0x15, itr.bytesLeft());
        assertEquals(0x1213141516171819L, itr.nextLong());
        assertTrue(itr.hasNextLong());
        assertEquals(0x0D, itr.bytesLeft());
        assertEquals(0x1A1B1C1D1E1F2021L, itr.nextLong());
        assertFalse(itr.hasNextLong());
        assertEquals(5, itr.bytesLeft());
        assertThrows(NoSuchElementException.class, itr::nextLong);
        assertTrue(itr.hasNextByte());
        assertEquals((byte) 0x22, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(4, itr.bytesLeft());
        assertEquals((byte) 0x23, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(3, itr.bytesLeft());
        assertEquals((byte) 0x24, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(2, itr.bytesLeft());
        assertEquals((byte) 0x25, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(1, itr.bytesLeft());
        assertEquals((byte) 0x26, itr.nextByte());
        assertFalse(itr.hasNextByte());
        assertEquals(0, itr.bytesLeft());
        assertThrows(NoSuchElementException.class, itr::nextLong);
        assertThrows(NoSuchElementException.class, itr::nextByte);

        itr = buf.iterate(buf.readerOffset() + 1, 2);
        assertEquals(2, itr.bytesLeft());
        assertTrue(itr.hasNextByte());
        assertFalse(itr.hasNextLong());
        assertEquals((byte) 0x02, itr.nextByte());
        assertEquals(1, itr.bytesLeft());
        assertTrue(itr.hasNextByte());
        assertFalse(itr.hasNextLong());
        assertEquals((byte) 0x03, itr.nextByte());
        assertEquals(0, itr.bytesLeft());
        assertFalse(itr.hasNextByte());
        assertFalse(itr.hasNextLong());
        assertEquals(roff, buf.readerOffset());
        assertEquals(woff, buf.writerOffset());
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void reverseByteIterationOfBigEndianBuffers(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(0x28)) {
            buf.order(ByteOrder.BIG_ENDIAN); // The byte order should have no impact.
            checkReverseByteIteration(buf);
            buf.reset();
            checkReverseByteIterationOfRegion(buf);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void reverseByteIterationOfLittleEndianBuffers(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(0x28)) {
            buf.order(ByteOrder.LITTLE_ENDIAN); // The byte order should have no impact.
            checkReverseByteIteration(buf);
            buf.reset();
            checkReverseByteIterationOfRegion(buf);
        }
    }

    private static void checkReverseByteIteration(Buf buf) {
        var itr = buf.iterateReverse();
        assertFalse(itr.hasNextByte());
        assertFalse(itr.hasNextLong());
        assertEquals(0, itr.bytesLeft());
        assertThrows(NoSuchElementException.class, itr::nextLong);
        assertThrows(NoSuchElementException.class, itr::nextByte);

        for (int i = 0; i < 0x27; i++) {
            buf.writeByte((byte) (i + 1));
        }
        int roff = buf.readerOffset();
        int woff = buf.writerOffset();
        itr = buf.iterateReverse();
        assertEquals(0x27, itr.bytesLeft());
        assertTrue(itr.hasNextByte());
        assertTrue(itr.hasNextLong());
        assertEquals(0x2726252423222120L, itr.nextLong());
        assertEquals(0x1F, itr.bytesLeft());
        assertTrue(itr.hasNextLong());
        assertEquals(0x1F1E1D1C1B1A1918L, itr.nextLong());
        assertTrue(itr.hasNextLong());
        assertEquals(0x17, itr.bytesLeft());
        assertEquals(0x1716151413121110L, itr.nextLong());
        assertTrue(itr.hasNextLong());
        assertEquals(0x0F, itr.bytesLeft());
        assertEquals(0x0F0E0D0C0B0A0908L, itr.nextLong());
        assertFalse(itr.hasNextLong());
        assertEquals(7, itr.bytesLeft());
        assertThrows(NoSuchElementException.class, itr::nextLong);
        assertTrue(itr.hasNextByte());
        assertEquals((byte) 0x07, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(6, itr.bytesLeft());
        assertEquals((byte) 0x06, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(5, itr.bytesLeft());
        assertEquals((byte) 0x05, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(4, itr.bytesLeft());
        assertEquals((byte) 0x04, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(3, itr.bytesLeft());
        assertEquals((byte) 0x03, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(2, itr.bytesLeft());
        assertEquals((byte) 0x02, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(1, itr.bytesLeft());
        assertEquals((byte) 0x01, itr.nextByte());
        assertFalse(itr.hasNextByte());
        assertEquals(0, itr.bytesLeft());
        assertThrows(NoSuchElementException.class, itr::nextLong);
        assertThrows(NoSuchElementException.class, itr::nextByte);
        assertEquals(roff, buf.readerOffset());
        assertEquals(woff, buf.writerOffset());
    }

    private static void checkReverseByteIterationOfRegion(Buf buf) {
        assertThrows(IllegalArgumentException.class, () -> buf.iterateReverse(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> buf.iterateReverse(0, -1));
        assertThrows(IllegalArgumentException.class, () -> buf.iterateReverse(0, 2));
        assertThrows(IllegalArgumentException.class, () -> buf.iterateReverse(1, 3));
        assertThrows(IllegalArgumentException.class, () -> buf.iterateReverse(buf.capacity(), 0));

        var itr = buf.iterateReverse(1, 0);
        assertFalse(itr.hasNextByte());
        assertFalse(itr.hasNextLong());
        assertEquals(0, itr.bytesLeft());
        assertThrows(NoSuchElementException.class, itr::nextLong);
        assertThrows(NoSuchElementException.class, itr::nextByte);

        for (int i = 0; i < 0x27; i++) {
            buf.writeByte((byte) (i + 1));
        }
        int roff = buf.readerOffset();
        int woff = buf.writerOffset();
        itr = buf.iterateReverse(buf.writerOffset() - 2, buf.readableBytes() - 2);
        assertEquals(0x25, itr.bytesLeft());
        assertTrue(itr.hasNextByte());
        assertTrue(itr.hasNextLong());
        assertEquals(0x262524232221201FL, itr.nextLong());
        assertEquals(0x1D, itr.bytesLeft());
        assertTrue(itr.hasNextLong());
        assertEquals(0x1E1D1C1B1A191817L, itr.nextLong());
        assertTrue(itr.hasNextLong());
        assertEquals(0x15, itr.bytesLeft());
        assertEquals(0x161514131211100FL, itr.nextLong());
        assertTrue(itr.hasNextLong());
        assertEquals(0x0D, itr.bytesLeft());
        assertEquals(0x0E0D0C0B0A090807L, itr.nextLong());
        assertFalse(itr.hasNextLong());
        assertEquals(5, itr.bytesLeft());
        assertThrows(NoSuchElementException.class, itr::nextLong);
        assertTrue(itr.hasNextByte());
        assertEquals((byte) 0x06, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(4, itr.bytesLeft());
        assertEquals((byte) 0x05, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(3, itr.bytesLeft());
        assertEquals((byte) 0x04, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(2, itr.bytesLeft());
        assertEquals((byte) 0x03, itr.nextByte());
        assertTrue(itr.hasNextByte());
        assertEquals(1, itr.bytesLeft());
        assertEquals((byte) 0x02, itr.nextByte());
        assertFalse(itr.hasNextByte());
        assertEquals(0, itr.bytesLeft());
        assertThrows(NoSuchElementException.class, itr::nextLong);
        assertThrows(NoSuchElementException.class, itr::nextByte);

        itr = buf.iterateReverse(buf.readerOffset() + 2, 2);
        assertEquals(2, itr.bytesLeft());
        assertTrue(itr.hasNextByte());
        assertFalse(itr.hasNextLong());
        assertEquals((byte) 0x03, itr.nextByte());
        assertEquals(1, itr.bytesLeft());
        assertTrue(itr.hasNextByte());
        assertFalse(itr.hasNextLong());
        assertEquals((byte) 0x02, itr.nextByte());
        assertEquals(0, itr.bytesLeft());
        assertFalse(itr.hasNextByte());
        assertFalse(itr.hasNextLong());
        assertEquals(roff, buf.readerOffset());
        assertEquals(woff, buf.writerOffset());
    }

    @ParameterizedTest
    @MethodSource("heapAllocators")
    public void heapBufferMustHaveZeroAddress(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThat(buf.getNativeAddress()).isZero();
        }
    }

    @ParameterizedTest
    @MethodSource("directAllocators")
    public void directBufferMustHaveNonZeroAddress(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThat(buf.getNativeAddress()).isNotZero();
        }
    }

    @Nested
    @Isolated
    class CleanerTests {
        @Disabled("Precise native memory accounting does not work since recent panama-foreign changes.")
        @ParameterizedTest
        @MethodSource("io.netty.buffer.b2.BufTest#directWithCleanerAllocators")
        public void bufferMustBeClosedByCleaner(Fixture fixture) throws InterruptedException {
            var allocator = fixture.createAllocator();
            allocator.close();
            int iterations = 100;
            int allocationSize = 1024;
            for (int i = 0; i < iterations; i++) {
                allocateAndForget(allocator, allocationSize);
                System.gc();
                System.runFinalization();
            }
            var sum = Statics.MEM_USAGE_NATIVE.sum();
            var totalAllocated = (long) allocationSize * iterations;
            assertThat(sum).isLessThan(totalAllocated);
        }

        private void allocateAndForget(Allocator allocator, int size) {
            allocator.allocate(size);
        }

        @Disabled("Precise native memory accounting does not work since recent panama-foreign changes.")
        @ParameterizedTest
        @MethodSource("io.netty.buffer.b2.BufTest#directPooledWithCleanerAllocators")
        public void buffersMustBeReusedByPoolingAllocatorEvenWhenDroppedByCleanerInsteadOfExplicitly(Fixture fixture)
                throws InterruptedException {
            try (var allocator = fixture.createAllocator()) {
                int iterations = 100;
                int allocationSize = 1024;
                for (int i = 0; i < iterations; i++) {
                    allocateAndForget(allocator, allocationSize);
                    System.gc();
                    System.runFinalization();
                }
                var sum = Statics.MEM_USAGE_NATIVE.sum();
                var totalAllocated = (long) allocationSize * iterations;
                assertThat(sum).isLessThan(totalAllocated);
            }
        }
    }

    // <editor-fold defaultstate="collapsed" desc="Primitive accessors tests.">
    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfByteMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            byte value = 0x01;
            buf.writeByte(value);
            assertEquals(1, buf.readableBytes());
            assertEquals(7, buf.writableBytes());
            assertEquals(value, buf.readByte());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfByteMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            byte value = 0x01;
            buf.writeByte(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(1, buf.readableBytes());
            assertEquals(7, buf.writableBytes());
            assertEquals(0x10, buf.readByte());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfByteMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            byte value = 0x01;
            buf.writeByte(value);
            buf.readerOffset(1);
            assertEquals(0, buf.readableBytes());
            assertEquals(7, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, buf::readByte);
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getByte(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            byte value = 0x01;
            buf.writeByte(value);
            assertEquals(value, buf.getByte(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            byte value = 0x01;
            buf.writeByte(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x10, buf.getByte(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            byte value = 0x01;
            buf.writeByte(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getByte(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getByte(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedByteMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x01;
            buf.writeUnsignedByte(value);
            assertEquals(1, buf.readableBytes());
            assertEquals(7, buf.writableBytes());
            assertEquals(value, buf.readUnsignedByte());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedByteMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x01;
            buf.writeUnsignedByte(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(1, buf.readableBytes());
            assertEquals(7, buf.writableBytes());
            assertEquals(0x10, buf.readUnsignedByte());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedByteMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x01;
            buf.writeUnsignedByte(value);
            buf.readerOffset(1);
            assertEquals(0, buf.readableBytes());
            assertEquals(7, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readUnsignedByte());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedByte(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            int value = 0x01;
            buf.writeUnsignedByte(value);
            assertEquals(value, buf.getUnsignedByte(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x01;
            buf.writeUnsignedByte(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x10, buf.getUnsignedByte(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            int value = 0x01;
            buf.writeUnsignedByte(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedByte(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedByte(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfByteMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            buf.writerOffset(8);
            byte value = 0x01;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeByte(value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfByteMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            byte value = 0x01;
            buf.writeByte(value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfByteMustBoundsCheckWhenWriteOffsetIsNegative(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            byte value = 0x01;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setByte(-1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfByteMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            byte value = 0x01;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setByte(8, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfByteMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            byte value = 0x01;
            buf.setByte(0, value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfUnsignedByteMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            buf.writerOffset(8);
            int value = 0x01;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeUnsignedByte(value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfUnsignedByteMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x01;
            buf.writeUnsignedByte(value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfUnsignedByteMustBoundsCheckWhenWriteOffsetIsNegative(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            int value = 0x01;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setUnsignedByte(-1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfUnsignedByteMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            int value = 0x01;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setUnsignedByte(8, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfUnsignedByteMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x01;
            buf.setUnsignedByte(0, value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfCharMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            char value = 0x0102;
            buf.writeChar(value);
            assertEquals(2, buf.readableBytes());
            assertEquals(6, buf.writableBytes());
            assertEquals(value, buf.readChar());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfCharMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            char value = 0x0102;
            buf.writeChar(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(2, buf.readableBytes());
            assertEquals(6, buf.writableBytes());
            assertEquals(0x1002, buf.readChar());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfCharMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            char value = 0x0102;
            buf.writeChar(value);
            buf.readerOffset(1);
            assertEquals(1, buf.readableBytes());
            assertEquals(6, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, buf::readChar);
            assertEquals(1, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getChar(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            char value = 0x0102;
            buf.writeChar(value);
            assertEquals(value, buf.getChar(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            char value = 0x0102;
            buf.writeChar(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x1002, buf.getChar(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            char value = 0x0102;
            buf.writeChar(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getChar(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getChar(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfCharMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            buf.writerOffset(7);
            char value = 0x0102;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeChar(value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfCharMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            char value = 0x0102;
            buf.writeChar(value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfCharMustBoundsCheckWhenWriteOffsetIsNegative(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            char value = 0x0102;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setChar(-1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfCharMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            char value = 0x0102;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setChar(7, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfCharMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            char value = 0x0102;
            buf.setChar(0, value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfShortMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            short value = 0x0102;
            buf.writeShort(value);
            assertEquals(2, buf.readableBytes());
            assertEquals(6, buf.writableBytes());
            assertEquals(value, buf.readShort());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfShortMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            short value = 0x0102;
            buf.writeShort(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(2, buf.readableBytes());
            assertEquals(6, buf.writableBytes());
            assertEquals(0x1002, buf.readShort());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfShortMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            short value = 0x0102;
            buf.writeShort(value);
            buf.readerOffset(1);
            assertEquals(1, buf.readableBytes());
            assertEquals(6, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, buf::readShort);
            assertEquals(1, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getShort(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            short value = 0x0102;
            buf.writeShort(value);
            assertEquals(value, buf.getShort(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            short value = 0x0102;
            buf.writeShort(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x1002, buf.getShort(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            short value = 0x0102;
            buf.writeShort(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getShort(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getShort(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedShortMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            assertEquals(2, buf.readableBytes());
            assertEquals(6, buf.writableBytes());
            assertEquals(value, buf.readUnsignedShort());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedShortMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(2, buf.readableBytes());
            assertEquals(6, buf.writableBytes());
            assertEquals(0x1002, buf.readUnsignedShort());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedShortMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            buf.readerOffset(1);
            assertEquals(1, buf.readableBytes());
            assertEquals(6, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, buf::readUnsignedShort);
            assertEquals(1, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedShort(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            assertEquals(value, buf.getUnsignedShort(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x1002, buf.getUnsignedShort(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedShort(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedShort(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfShortMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            buf.writerOffset(7);
            short value = 0x0102;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeShort(value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfShortMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            short value = 0x0102;
            buf.writeShort(value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfShortMustBoundsCheckWhenWriteOffsetIsNegative(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            short value = 0x0102;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setShort(-1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfShortMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            short value = 0x0102;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setShort(7, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfShortMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            short value = 0x0102;
            buf.setShort(0, value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfUnsignedShortMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            buf.writerOffset(7);
            int value = 0x0102;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeUnsignedShort(value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfUnsignedShortMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfUnsignedShortMustBoundsCheckWhenWriteOffsetIsNegative(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            int value = 0x0102;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setUnsignedShort(-1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfUnsignedShortMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            int value = 0x0102;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setUnsignedShort(7, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfUnsignedShortMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x0102;
            buf.setUnsignedShort(0, value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x010203;
            buf.writeMedium(value);
            assertEquals(3, buf.readableBytes());
            assertEquals(5, buf.writableBytes());
            assertEquals(value, buf.readMedium());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfMediumMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x010203;
            buf.writeMedium(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(3, buf.readableBytes());
            assertEquals(5, buf.writableBytes());
            assertEquals(0x100203, buf.readMedium());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfMediumMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x010203;
            buf.writeMedium(value);
            buf.readerOffset(1);
            assertEquals(2, buf.readableBytes());
            assertEquals(5, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, buf::readMedium);
            assertEquals(2, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getMedium(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            int value = 0x010203;
            buf.writeMedium(value);
            assertEquals(value, buf.getMedium(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x010203;
            buf.writeMedium(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x100203, buf.getMedium(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            int value = 0x010203;
            buf.writeMedium(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getMedium(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getMedium(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            assertEquals(3, buf.readableBytes());
            assertEquals(5, buf.writableBytes());
            assertEquals(value, buf.readUnsignedMedium());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedMediumMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(3, buf.readableBytes());
            assertEquals(5, buf.writableBytes());
            assertEquals(0x100203, buf.readUnsignedMedium());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedMediumMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            buf.readerOffset(1);
            assertEquals(2, buf.readableBytes());
            assertEquals(5, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, buf::readUnsignedMedium);
            assertEquals(2, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedMedium(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            assertEquals(value, buf.getUnsignedMedium(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x100203, buf.getUnsignedMedium(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedMedium(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedMedium(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfMediumMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            buf.writerOffset(6);
            int value = 0x010203;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeMedium(value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfMediumMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x010203;
            buf.writeMedium(value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfMediumMustBoundsCheckWhenWriteOffsetIsNegative(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            int value = 0x010203;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setMedium(-1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfMediumMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            int value = 0x010203;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setMedium(6, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfMediumMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x010203;
            buf.setMedium(0, value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfUnsignedMediumMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            buf.writerOffset(6);
            int value = 0x010203;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeUnsignedMedium(value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfUnsignedMediumMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfUnsignedMediumMustBoundsCheckWhenWriteOffsetIsNegative(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            int value = 0x010203;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setUnsignedMedium(-1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfUnsignedMediumMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            int value = 0x010203;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setUnsignedMedium(6, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfUnsignedMediumMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x010203;
            buf.setUnsignedMedium(0, value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfIntMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x01020304;
            buf.writeInt(value);
            assertEquals(4, buf.readableBytes());
            assertEquals(4, buf.writableBytes());
            assertEquals(value, buf.readInt());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfIntMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x01020304;
            buf.writeInt(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(4, buf.readableBytes());
            assertEquals(4, buf.writableBytes());
            assertEquals(0x10020304, buf.readInt());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfIntMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x01020304;
            buf.writeInt(value);
            buf.readerOffset(1);
            assertEquals(3, buf.readableBytes());
            assertEquals(4, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, buf::readInt);
            assertEquals(3, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getInt(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            int value = 0x01020304;
            buf.writeInt(value);
            assertEquals(value, buf.getInt(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x01020304;
            buf.writeInt(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x10020304, buf.getInt(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            int value = 0x01020304;
            buf.writeInt(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getInt(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getInt(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedIntMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            assertEquals(4, buf.readableBytes());
            assertEquals(4, buf.writableBytes());
            assertEquals(value, buf.readUnsignedInt());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedIntMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(4, buf.readableBytes());
            assertEquals(4, buf.writableBytes());
            assertEquals(0x10020304, buf.readUnsignedInt());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedIntMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            buf.readerOffset(1);
            assertEquals(3, buf.readableBytes());
            assertEquals(4, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, buf::readUnsignedInt);
            assertEquals(3, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedInt(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            assertEquals(value, buf.getUnsignedInt(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x10020304, buf.getUnsignedInt(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedInt(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedInt(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfIntMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            buf.writerOffset(5);
            int value = 0x01020304;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeInt(value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfIntMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x01020304;
            buf.writeInt(value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x04, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfIntMustBoundsCheckWhenWriteOffsetIsNegative(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            int value = 0x01020304;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setInt(-1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfIntMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            int value = 0x01020304;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setInt(5, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfIntMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int value = 0x01020304;
            buf.setInt(0, value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x04, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfUnsignedIntMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            buf.writerOffset(5);
            long value = 0x01020304;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeUnsignedInt(value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfUnsignedIntMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x04, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfUnsignedIntMustBoundsCheckWhenWriteOffsetIsNegative(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            long value = 0x01020304;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setUnsignedInt(-1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfUnsignedIntMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            long value = 0x01020304;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setUnsignedInt(5, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfUnsignedIntMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            long value = 0x01020304;
            buf.setUnsignedInt(0, value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x04, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfFloatMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            assertEquals(4, buf.readableBytes());
            assertEquals(4, buf.writableBytes());
            assertEquals(value, buf.readFloat());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfFloatMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(4, buf.readableBytes());
            assertEquals(4, buf.writableBytes());
            assertEquals(Float.intBitsToFloat(0x10020304), buf.readFloat());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfFloatMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            buf.readerOffset(1);
            assertEquals(3, buf.readableBytes());
            assertEquals(4, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, buf::readFloat);
            assertEquals(3, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getFloat(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            assertEquals(value, buf.getFloat(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(Float.intBitsToFloat(0x10020304), buf.getFloat(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getFloat(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getFloat(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfFloatMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            buf.writerOffset(5);
            float value = Float.intBitsToFloat(0x01020304);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeFloat(value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfFloatMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x04, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfFloatMustBoundsCheckWhenWriteOffsetIsNegative(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            float value = Float.intBitsToFloat(0x01020304);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setFloat(-1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfFloatMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            float value = Float.intBitsToFloat(0x01020304);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setFloat(5, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfFloatMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            float value = Float.intBitsToFloat(0x01020304);
            buf.setFloat(0, value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x04, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
            assertEquals((byte) 0x00, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfLongMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            assertEquals(8, buf.readableBytes());
            assertEquals(0, buf.writableBytes());
            assertEquals(value, buf.readLong());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfLongMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(8, buf.readableBytes());
            assertEquals(0, buf.writableBytes());
            assertEquals(0x1002030405060708L, buf.readLong());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfLongMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            buf.readerOffset(1);
            assertEquals(7, buf.readableBytes());
            assertEquals(0, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, buf::readLong);
            assertEquals(7, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getLong(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            assertEquals(value, buf.getLong(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x1002030405060708L, buf.getLong(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getLong(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getLong(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfLongMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            buf.writerOffset(1);
            long value = 0x0102030405060708L;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeLong(value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfLongMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x04, buf.readByte());
            assertEquals((byte) 0x05, buf.readByte());
            assertEquals((byte) 0x06, buf.readByte());
            assertEquals((byte) 0x07, buf.readByte());
            assertEquals((byte) 0x08, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfLongMustBoundsCheckWhenWriteOffsetIsNegative(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            long value = 0x0102030405060708L;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setLong(-1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfLongMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            long value = 0x0102030405060708L;
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setLong(1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfLongMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            long value = 0x0102030405060708L;
            buf.setLong(0, value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x04, buf.readByte());
            assertEquals((byte) 0x05, buf.readByte());
            assertEquals((byte) 0x06, buf.readByte());
            assertEquals((byte) 0x07, buf.readByte());
            assertEquals((byte) 0x08, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfDoubleMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            assertEquals(8, buf.readableBytes());
            assertEquals(0, buf.writableBytes());
            assertEquals(value, buf.readDouble());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfDoubleMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(8, buf.readableBytes());
            assertEquals(0, buf.writableBytes());
            assertEquals(Double.longBitsToDouble(0x1002030405060708L), buf.readDouble());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfDoubleMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            buf.readerOffset(1);
            assertEquals(7, buf.readableBytes());
            assertEquals(0, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, buf::readDouble);
            assertEquals(7, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getDouble(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            assertEquals(value, buf.getDouble(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(Double.longBitsToDouble(0x1002030405060708L), buf.getDouble(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getDouble(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getDouble(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfDoubleMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            buf.writerOffset(1);
            double value = Double.longBitsToDouble(0x0102030405060708L);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeDouble(value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfDoubleMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x04, buf.readByte());
            assertEquals((byte) 0x05, buf.readByte());
            assertEquals((byte) 0x06, buf.readByte());
            assertEquals((byte) 0x07, buf.readByte());
            assertEquals((byte) 0x08, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfDoubleMustBoundsCheckWhenWriteOffsetIsNegative(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            double value = Double.longBitsToDouble(0x0102030405060708L);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setDouble(-1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfDoubleMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            assertEquals(Long.BYTES, buf.capacity());
            double value = Double.longBitsToDouble(0x0102030405060708L);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.setDouble(1, value));
            buf.writerOffset(Long.BYTES);
            // Verify contents are unchanged.
            assertEquals(0, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedSetOfDoubleMustHaveDefaultEndianByteOrder(Fixture fixture) {
        try (Allocator allocator = fixture.createAllocator();
             Buf buf = allocator.allocate(8)) {
            buf.order(ByteOrder.BIG_ENDIAN);
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.setDouble(0, value);
            buf.writerOffset(Long.BYTES);
            assertEquals((byte) 0x01, buf.readByte());
            assertEquals((byte) 0x02, buf.readByte());
            assertEquals((byte) 0x03, buf.readByte());
            assertEquals((byte) 0x04, buf.readByte());
            assertEquals((byte) 0x05, buf.readByte());
            assertEquals((byte) 0x06, buf.readByte());
            assertEquals((byte) 0x07, buf.readByte());
            assertEquals((byte) 0x08, buf.readByte());
        }
    }
    // </editor-fold>

    private static byte[] toByteArray(Buf buf) {
        byte[] bs = new byte[buf.capacity()];
        buf.copyInto(0, bs, 0, bs.length);
        return bs;
    }

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