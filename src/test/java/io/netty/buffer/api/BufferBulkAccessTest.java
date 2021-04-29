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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.assertj.core.api.Assertions.assertThat;

public class BufferBulkAccessTest extends BufferTestSupport {

    @ParameterizedTest
    @MethodSource("allocators")
    void fill(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(16)) {
            assertThat(buf.fill((byte) 0xA5)).isSameAs(buf);
            buf.writerOffset(16);
            assertEquals(0xA5A5A5A5_A5A5A5A5L, buf.readLong());
            assertEquals(0xA5A5A5A5_A5A5A5A5L, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoByteArray(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN).writeLong(0x0102030405060708L);
            byte[] array = new byte[8];
            buf.copyInto(0, array, 0, array.length);
            assertThat(array).containsExactly(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08);

            buf.writerOffset(0).order(LITTLE_ENDIAN).writeLong(0x0102030405060708L);
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

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoOnHeapBuf(Fixture fixture) {
        testCopyIntoBuf(fixture, BufferAllocator.heap()::allocate);
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoOffHeapBuf(Fixture fixture) {
        testCopyIntoBuf(fixture, BufferAllocator.direct()::allocate);
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoOnHeapBufSlice(Fixture fixture) {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Scope scope = new Scope()) {
            testCopyIntoBuf(fixture, size -> {
                return scope.add(allocator.allocate(size)).writerOffset(size).slice();
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoOffHeapBufSlice(Fixture fixture) {
        try (BufferAllocator allocator = BufferAllocator.direct();
             Scope scope = new Scope()) {
            testCopyIntoBuf(fixture, size -> {
                return scope.add(allocator.allocate(size)).writerOffset(size).slice();
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOnHeapOnHeapBuf(Fixture fixture) {
        try (var a = BufferAllocator.heap();
             var b = BufferAllocator.heap()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return CompositeBuffer.compose(a, bufFirst, bufSecond);
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOnHeapOffHeapBuf(Fixture fixture) {
        try (var a = BufferAllocator.heap();
             var b = BufferAllocator.direct()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return CompositeBuffer.compose(a, bufFirst, bufSecond);
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOffHeapOnHeapBuf(Fixture fixture) {
        try (var a = BufferAllocator.direct();
             var b = BufferAllocator.heap()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return CompositeBuffer.compose(a, bufFirst, bufSecond);
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOffHeapOffHeapBuf(Fixture fixture) {
        try (var a = BufferAllocator.direct();
             var b = BufferAllocator.direct()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return CompositeBuffer.compose(a, bufFirst, bufSecond);
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOnHeapOnHeapBufSlice(Fixture fixture) {
        try (var a = BufferAllocator.heap();
             var b = BufferAllocator.heap();
             var scope = new Scope()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return scope.add(CompositeBuffer.compose(a, bufFirst, bufSecond)).writerOffset(size).slice();
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOnHeapOffHeapBufSlice(Fixture fixture) {
        try (var a = BufferAllocator.heap();
             var b = BufferAllocator.direct();
             var scope = new Scope()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return scope.add(CompositeBuffer.compose(a, bufFirst, bufSecond)).writerOffset(size).slice();
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOffHeapOnHeapBufSlice(Fixture fixture) {
        try (var a = BufferAllocator.direct();
             var b = BufferAllocator.heap();
             var scope = new Scope()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return scope.add(CompositeBuffer.compose(a, bufFirst, bufSecond)).writerOffset(size).slice();
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void copyIntoCompositeOffHeapOffHeapBufSlice(Fixture fixture) {
        try (var a = BufferAllocator.direct();
             var b = BufferAllocator.direct();
             var scope = new Scope()) {
            testCopyIntoBuf(fixture, size -> {
                int first = size / 2;
                int second = size - first;
                try (var bufFirst = a.allocate(first);
                     var bufSecond = b.allocate(second)) {
                    return scope.add(CompositeBuffer.compose(a, bufFirst, bufSecond)).writerOffset(size).slice();
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void byteIterationOfBigEndianBuffers(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(0x28)) {
            buf.order(BIG_ENDIAN); // The byte order should have no impact.
            checkByteIteration(buf);
            buf.reset();
            checkByteIterationOfRegion(buf);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void byteIterationOfLittleEndianBuffers(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(0x28)) {
            buf.order(LITTLE_ENDIAN); // The byte order should have no impact.
            checkByteIteration(buf);
            buf.reset();
            checkByteIterationOfRegion(buf);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void reverseByteIterationOfBigEndianBuffers(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(0x28)) {
            buf.order(BIG_ENDIAN); // The byte order should have no impact.
            checkReverseByteIteration(buf);
            buf.reset();
            checkReverseByteIterationOfRegion(buf);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void reverseByteIterationOfLittleEndianBuffers(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(0x28)) {
            buf.order(LITTLE_ENDIAN); // The byte order should have no impact.
            checkReverseByteIteration(buf);
            buf.reset();
            checkReverseByteIterationOfRegion(buf);
        }
    }

    @ParameterizedTest
    @MethodSource("heapAllocators")
    public void heapBufferMustHaveZeroAddress(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThat(buf.nativeAddress()).isZero();
        }
    }

    @ParameterizedTest
    @MethodSource("directAllocators")
    public void directBufferMustHaveNonZeroAddress(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThat(buf.nativeAddress()).isNotZero();
        }
    }

    private static final Memoize<Fixture[]> OTHER_FIXTURES = new Memoize<Fixture[]>(
            () -> Arrays.stream(allocators()).filter(filterOfTheDay(10)).toArray(Fixture[]::new));

    @ParameterizedTest
    @MethodSource("allocators")
    public void writeBytesMustTransferDataAndUpdateOffsets(Fixture fixture) {
        try (BufferAllocator alloc1 = fixture.createAllocator()) {
            // Only test 10% of available combinations. Otherwise, this takes too long.
            Fixture[] allocators = OTHER_FIXTURES.get();
            Arrays.stream(allocators).parallel().forEach(otherFixture -> {
                try (BufferAllocator alloc2 = otherFixture.createAllocator();
                     Buffer target = alloc1.allocate(37);
                     Buffer source = alloc2.allocate(35)) {
                    // BE to BE
                    target.order(BIG_ENDIAN);
                    source.order(BIG_ENDIAN);
                    verifyWriteBytes(target, source);

                    // LE to BE
                    target.fill((byte) 0).reset().order(BIG_ENDIAN);
                    source.fill((byte) 0).reset().order(LITTLE_ENDIAN);
                    verifyWriteBytes(target, source);

                    // BE to LE
                    target.fill((byte) 0).reset().order(LITTLE_ENDIAN);
                    source.fill((byte) 0).reset().order(BIG_ENDIAN);
                    verifyWriteBytes(target, source);

                    // LE to LE
                    target.fill((byte) 0).reset().order(LITTLE_ENDIAN);
                    source.fill((byte) 0).reset().order(BIG_ENDIAN);
                    verifyWriteBytes(target, source);
                } catch (Exception e) {
                    e.addSuppressed(new RuntimeException("other fixture was: " + otherFixture));
                    throw e;
                }
            });
        }
    }

    private static void verifyWriteBytes(Buffer target, Buffer source) {
        for (int i = 0; i < 35; i++) {
            source.writeByte((byte) (i + 1));
        }
        target.writeBytes(source);
        assertThat(target.readerOffset()).isZero();
        assertThat(target.writerOffset()).isEqualTo(35);
        assertThat(source.readerOffset()).isEqualTo(35);
        assertThat(source.writerOffset()).isEqualTo(35);
        try (Buffer readableSlice = target.slice()) {
            assertEquals(source, readableSlice);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void writeBytesMustWriteAllBytesFromByteArray(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buffer = allocator.allocate(8)) {
            buffer.writeByte((byte) 1);
            buffer.writeBytes(new byte[] {2, 3, 4, 5, 6, 7});
            assertThat(buffer.writerOffset()).isEqualTo(7);
            assertThat(buffer.readerOffset()).isZero();
            assertThat(toByteArray(buffer)).containsExactly(1, 2, 3, 4, 5, 6, 7, 0);
        }
    }
}
