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
package io.netty.buffer.api;

import io.netty.buffer.api.Fixture.Properties;
import io.netty.buffer.api.memseg.NativeMemorySegmentManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.Stream.Builder;

import static io.netty.buffer.api.Fixture.Properties.CLEANER;
import static io.netty.buffer.api.Fixture.Properties.COMPOSITE;
import static io.netty.buffer.api.Fixture.Properties.DIRECT;
import static io.netty.buffer.api.Fixture.Properties.HEAP;
import static io.netty.buffer.api.Fixture.Properties.POOLED;
import static io.netty.buffer.api.MemoryManagers.using;
import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BufferTest {
    private static ExecutorService executor;

    private static final Memoize<Fixture[]> ALL_COMBINATIONS = new Memoize<>(
            () -> fixtureCombinations().filter(sample()).toArray(Fixture[]::new));
    private static final Memoize<Fixture[]> NON_COMPOSITE = new Memoize<>(
            () -> Arrays.stream(ALL_COMBINATIONS.get()).filter(f -> !f.isComposite()).toArray(Fixture[]::new));
    private static final Memoize<Fixture[]> HEAP_ALLOCS = new Memoize<>(
            () -> Arrays.stream(ALL_COMBINATIONS.get()).filter(f -> f.isHeap()).toArray(Fixture[]::new));
    private static final Memoize<Fixture[]> DIRECT_ALLOCS = new Memoize<>(
            () -> Arrays.stream(ALL_COMBINATIONS.get()).filter(f -> f.isDirect()).toArray(Fixture[]::new));
    private static final Memoize<Fixture[]> POOLED_ALLOCS = new Memoize<>(
            () -> Arrays.stream(ALL_COMBINATIONS.get()).filter(f -> f.isPooled()).toArray(Fixture[]::new));
    private static final Memoize<Fixture[]> POOLED_DIRECT_ALLOCS = new Memoize<>(
            () -> Arrays.stream(ALL_COMBINATIONS.get()).filter(
                    f -> f.isPooled() && f.isDirect()).toArray(Fixture[]::new));

    private static Predicate<Fixture> sample() {
        String sampleSetting = System.getProperty("sample");
        if ("nosample".equalsIgnoreCase(sampleSetting)) {
            return fixture -> true;
        }
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        SplittableRandom rng = new SplittableRandom(today.hashCode());
        return fixture -> rng.nextInt(0, 100) <= 2; // Filter out 97% of tests.
    }

    static Fixture[] allocators() {
        return ALL_COMBINATIONS.get();
    }

    static Fixture[] nonCompositeAllocators() {
        return NON_COMPOSITE.get();
    }

    static Fixture[] heapAllocators() {
        return HEAP_ALLOCS.get();
    }

    static Fixture[] directAllocators() {
        return DIRECT_ALLOCS.get();
    }

    static Fixture[] pooledAllocators() {
        return POOLED_ALLOCS.get();
    }

    static Fixture[] pooledDirectAllocators() {
        return POOLED_DIRECT_ALLOCS.get();
    }

    static List<Fixture> initialAllocators() {
        return List.of(
                new Fixture("heap", BufferAllocator::heap, HEAP),
                new Fixture("direct", BufferAllocator::direct, DIRECT, CLEANER),
                new Fixture("pooledHeap", BufferAllocator::pooledHeap, POOLED, HEAP),
                new Fixture("pooledDirect", BufferAllocator::pooledDirect, POOLED, DIRECT, CLEANER));
    }

    private static Stream<Fixture> fixtureCombinations() {
        List<Fixture> initFixtures = initialAllocators();

        // Multiply by all MemoryManagers.
        List<MemoryManagers> loadableManagers = new ArrayList<>();
        MemoryManagers.getAllManagers().forEach(provider -> {
            loadableManagers.add(provider.get());
        });
        initFixtures = initFixtures.stream().flatMap(f -> {
            Stream.Builder<Fixture> builder = Stream.builder();
            for (MemoryManagers managers : loadableManagers) {
                builder.add(new Fixture(f + "/" + managers, () -> using(managers, f), f.getProperties()));
            }
            return builder.build();
        }).toList();

        Builder<Fixture> builder = Stream.builder();
        initFixtures.forEach(builder);

        // Add 2-way composite buffers of all combinations.
        for (Fixture first : initFixtures) {
            for (Fixture second : initFixtures) {
                var a = first.get();
                var b = second.get();
                builder.add(new Fixture("compose(" + first + ", " + second + ')', () -> {
                    return new BufferAllocator() {
                        @Override
                        public Buffer allocate(int size) {
                            int half = size / 2;
                            try (Buffer firstHalf = a.allocate(half);
                                 Buffer secondHalf = b.allocate(size - half)) {
                                return Buffer.compose(a, firstHalf, secondHalf);
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
            return new BufferAllocator() {
                final BufferAllocator alloc = BufferAllocator.heap();
                @Override
                public Buffer allocate(int size) {
                    int part = size / 3;
                    try (Buffer a = alloc.allocate(part);
                         Buffer b = alloc.allocate(part);
                         Buffer c = alloc.allocate(size - part * 2)) {
                        return Buffer.compose(alloc, a, b, c);
                    }
                }

                @Override
                public void close() {
                    alloc.close();
                }
            };
        }, COMPOSITE));

        for (Fixture fixture : initFixtures) {
            builder.add(new Fixture(fixture + ".ensureWritable", () -> {
                var allocator = fixture.createAllocator();
                return new BufferAllocator() {
                    @Override
                    public Buffer allocate(int size) {
                        if (size < 2) {
                            return allocator.allocate(size);
                        }
                        var buf = allocator.allocate(size - 1);
                        buf.ensureWritable(size);
                        return buf;
                    }

                    @Override
                    public void close() {
                        allocator.close();
                    }
                };
            }, fixture.getProperties()));
            builder.add(new Fixture(fixture + ".compose.ensureWritable", () -> {
                var allocator = fixture.createAllocator();
                return new BufferAllocator() {
                    @Override
                    public Buffer allocate(int size) {
                        if (size < 2) {
                            return allocator.allocate(size);
                        }
                        var buf = Buffer.compose(allocator);
                        buf.ensureWritable(size);
                        return buf;
                    }

                    @Override
                    public void close() {
                        allocator.close();
                    }
                };
            }, COMPOSITE));
        }

        var stream = builder.build();
        return stream.flatMap(BufferTest::injectBifurcations)
                     .flatMap(BufferTest::injectSlices)
                     .flatMap(BufferTest::injectReadOnlyToggling);
    }

    private static Stream<Fixture> injectBifurcations(Fixture f) {
        Builder<Fixture> builder = Stream.builder();
        builder.add(f);
        builder.add(new Fixture(f + ".bifurcate", () -> {
            var allocatorBase = f.get();
            return new BufferAllocator() {
                @Override
                public Buffer allocate(int size) {
                    try (Buffer buf = allocatorBase.allocate(size + 1)) {
                        buf.writerOffset(size);
                        return buf.bifurcate().writerOffset(0);
                    }
                }

                @Override
                public void close() {
                    allocatorBase.close();
                }
            };
        }, f.getProperties()));
        return builder.build();
    }

    private static Stream<Fixture> injectSlices(Fixture f) {
        Builder<Fixture> builder = Stream.builder();
        builder.add(f);
        var props = concat(f.getProperties(), Properties.SLICE);
        builder.add(new Fixture(f + ".slice(0, capacity())", () -> {
            var allocatorBase = f.get();
            return new BufferAllocator() {
                @Override
                public Buffer allocate(int size) {
                    try (Buffer base = allocatorBase.allocate(size)) {
                        return base.slice(0, base.capacity()).writerOffset(0);
                    }
                }

                @Override
                public void close() {
                    allocatorBase.close();
                }
            };
        }, props));
        builder.add(new Fixture(f + ".slice(1, capacity() - 2)", () -> {
            var allocatorBase = f.get();
            return new BufferAllocator() {
                @Override
                public Buffer allocate(int size) {
                    try (Buffer base = allocatorBase.allocate(size + 2)) {
                        return base.slice(1, size).writerOffset(0);
                    }
                }

                @Override
                public void close() {
                    allocatorBase.close();
                }
            };
        }, props));
        return builder.build();
    }

    private static Stream<Fixture> injectReadOnlyToggling(Fixture f) {
        Builder<Fixture> builder = Stream.builder();
        builder.add(f);
        builder.add(new Fixture(f + ".readOnly(true/false)", () -> {
            var allocatorBase = f.get();
            return new BufferAllocator() {
                @Override
                public Buffer allocate(int size) {
                    return allocatorBase.allocate(size).readOnly(true).readOnly(false);
                }

                @Override
                public void close() {
                    allocatorBase.close();
                }
            };
        }, f.getProperties()));
        return builder.build();
    }

    private static Properties[] concat(Properties[] props, Properties prop) {
        props = Arrays.copyOf(props, props.length + 1);
        props[props.length - 1] = prop;
        return props;
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
    void allocateAndSendToThread(Fixture fixture) throws Exception {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            ArrayBlockingQueue<Send<Buffer>> queue = new ArrayBlockingQueue<>(10);
            Future<Byte> future = executor.submit(() -> {
                try (Buffer byteBuf = queue.take().receive()) {
                    return byteBuf.readByte();
                }
            });

            try (Buffer buf = allocator.allocate(8)) {
                buf.writeByte((byte) 42);
                assertTrue(queue.offer(buf.send()));
            }

            assertEquals((byte) 42, future.get().byteValue());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void allocateAndSendToThreadViaSyncQueue(Fixture fixture) throws Exception {
        SynchronousQueue<Send<Buffer>> queue = new SynchronousQueue<>();
        Future<Byte> future = executor.submit(() -> {
            try (Buffer byteBuf = queue.take().receive()) {
                return byteBuf.readByte();
            }
        });

        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThat(buf.writeByte((byte) 42)).isSameAs(buf);
            queue.put(buf.send());
        }

        assertEquals((byte) 42, future.get().byteValue());
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void sendMustThrowWhenBufIsAcquired(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            try (Buffer ignored = buf.acquire()) {
                assertFalse(buf.isOwned());
                assertThrows(IllegalStateException.class, buf::send);
            }
            // Now send() should work again.
            assertTrue(buf.isOwned());
            buf.send().receive().close();
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void originalBufferMustNotBeAccessibleAfterSend(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer orig = allocator.allocate(24)) {
            orig.writeLong(42);
            var send = orig.send();
            verifyInaccessible(orig);
            try (Buffer receive = send.receive()) {
                assertEquals(42, receive.readInt());
            }
        }
    }

    private static void verifyInaccessible(Buffer buf) {
        verifyReadInaccessible(buf);

        verifyWriteInaccessible(buf);

        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer target = allocator.allocate(24)) {
            assertThrows(IllegalStateException.class, () -> buf.copyInto(0, target, 0, 1));
            assertThrows(IllegalStateException.class, () -> buf.copyInto(0, new byte[1], 0, 1));
            assertThrows(IllegalStateException.class, () -> buf.copyInto(0, ByteBuffer.allocate(1), 0, 1));
            if (Buffer.isComposite(buf)) {
                assertThrows(IllegalStateException.class, () -> Buffer.extendComposite(buf, target));
            }
        }

        assertThrows(IllegalStateException.class, () -> buf.bifurcate());
        assertThrows(IllegalStateException.class, () -> buf.send());
        assertThrows(IllegalStateException.class, () -> buf.acquire());
        assertThrows(IllegalStateException.class, () -> buf.slice());
        assertThrows(IllegalStateException.class, () -> buf.openCursor());
        assertThrows(IllegalStateException.class, () -> buf.openCursor(0, 0));
        assertThrows(IllegalStateException.class, () -> buf.openReverseCursor());
        assertThrows(IllegalStateException.class, () -> buf.openReverseCursor(0, 0));
    }

    private static void verifyReadInaccessible(Buffer buf) {
        assertThrows(IllegalStateException.class, () -> buf.readByte());
        assertThrows(IllegalStateException.class, () -> buf.readUnsignedByte());
        assertThrows(IllegalStateException.class, () -> buf.readChar());
        assertThrows(IllegalStateException.class, () -> buf.readShort());
        assertThrows(IllegalStateException.class, () -> buf.readUnsignedShort());
        assertThrows(IllegalStateException.class, () -> buf.readMedium());
        assertThrows(IllegalStateException.class, () -> buf.readUnsignedMedium());
        assertThrows(IllegalStateException.class, () -> buf.readInt());
        assertThrows(IllegalStateException.class, () -> buf.readUnsignedInt());
        assertThrows(IllegalStateException.class, () -> buf.readFloat());
        assertThrows(IllegalStateException.class, () -> buf.readLong());
        assertThrows(IllegalStateException.class, () -> buf.readDouble());

        assertThrows(IllegalStateException.class, () -> buf.getByte(0));
        assertThrows(IllegalStateException.class, () -> buf.getUnsignedByte(0));
        assertThrows(IllegalStateException.class, () -> buf.getChar(0));
        assertThrows(IllegalStateException.class, () -> buf.getShort(0));
        assertThrows(IllegalStateException.class, () -> buf.getUnsignedShort(0));
        assertThrows(IllegalStateException.class, () -> buf.getMedium(0));
        assertThrows(IllegalStateException.class, () -> buf.getUnsignedMedium(0));
        assertThrows(IllegalStateException.class, () -> buf.getInt(0));
        assertThrows(IllegalStateException.class, () -> buf.getUnsignedInt(0));
        assertThrows(IllegalStateException.class, () -> buf.getFloat(0));
        assertThrows(IllegalStateException.class, () -> buf.getLong(0));
        assertThrows(IllegalStateException.class, () -> buf.getDouble(0));
    }

    private static void verifyWriteInaccessible(Buffer buf) {
        assertThrows(IllegalStateException.class, () -> buf.writeByte((byte) 32));
        assertThrows(IllegalStateException.class, () -> buf.writeUnsignedByte(32));
        assertThrows(IllegalStateException.class, () -> buf.writeChar('3'));
        assertThrows(IllegalStateException.class, () -> buf.writeShort((short) 32));
        assertThrows(IllegalStateException.class, () -> buf.writeUnsignedShort(32));
        assertThrows(IllegalStateException.class, () -> buf.writeMedium(32));
        assertThrows(IllegalStateException.class, () -> buf.writeUnsignedMedium(32));
        assertThrows(IllegalStateException.class, () -> buf.writeInt(32));
        assertThrows(IllegalStateException.class, () -> buf.writeUnsignedInt(32));
        assertThrows(IllegalStateException.class, () -> buf.writeFloat(3.2f));
        assertThrows(IllegalStateException.class, () -> buf.writeLong(32));
        assertThrows(IllegalStateException.class, () -> buf.writeDouble(32));

        assertThrows(IllegalStateException.class, () -> buf.setByte(0, (byte) 32));
        assertThrows(IllegalStateException.class, () -> buf.setUnsignedByte(0, 32));
        assertThrows(IllegalStateException.class, () -> buf.setChar(0, '3'));
        assertThrows(IllegalStateException.class, () -> buf.setShort(0, (short) 32));
        assertThrows(IllegalStateException.class, () -> buf.setUnsignedShort(0, 32));
        assertThrows(IllegalStateException.class, () -> buf.setMedium(0, 32));
        assertThrows(IllegalStateException.class, () -> buf.setUnsignedMedium(0, 32));
        assertThrows(IllegalStateException.class, () -> buf.setInt(0, 32));
        assertThrows(IllegalStateException.class, () -> buf.setUnsignedInt(0, 32));
        assertThrows(IllegalStateException.class, () -> buf.setFloat(0, 3.2f));
        assertThrows(IllegalStateException.class, () -> buf.setLong(0, 32));
        assertThrows(IllegalStateException.class, () -> buf.setDouble(0, 32));

        assertThrows(IllegalStateException.class, () -> buf.ensureWritable(1));
        assertThrows(IllegalStateException.class, () -> buf.fill((byte) 0));
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer source = allocator.allocate(8)) {
            assertThrows(IllegalStateException.class, () -> source.copyInto(0, buf, 0, 1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void cannotSendMoreThanOnce(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            var send = buf.send();
            var exc = assertThrows(IllegalStateException.class, () -> buf.send());
            send.receive().close();
            assertThat(exc).hasMessageContaining("Cannot send()");
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
    @MethodSource("initialAllocators")
    void mustThrowWhenAllocatingZeroSizedBuffer(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            assertThrows(IllegalArgumentException.class, () -> allocator.allocate(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void mustThrowWhenAllocatingNegativeSizedBuffer(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            assertThrows(IllegalArgumentException.class, () -> allocator.allocate(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void setReaderOffsetMustThrowOnNegativeIndex(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readerOffset(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void setReaderOffsetMustThrowOnOversizedIndex(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readerOffset(1));
            buf.writeLong(0);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readerOffset(9));

            buf.readerOffset(8);
            assertThrows(IndexOutOfBoundsException.class, buf::readByte);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void setWriterOffsetMustThrowOutsideOfWritableRegion(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            // Writer offset cannot be negative.
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writerOffset(-1));

            buf.writerOffset(4);
            buf.readerOffset(4);

            // Cannot set writer offset before reader offset.
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writerOffset(3));
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writerOffset(0));

            buf.writerOffset(buf.capacity());

            // Cannot set writer offset beyond capacity.
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writerOffset(buf.capacity() + 1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void setReaderOffsetMustNotThrowWithinBounds(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThat(buf.readerOffset(0)).isSameAs(buf);
            buf.writeLong(0);
            assertThat(buf.readerOffset(7)).isSameAs(buf);
            assertThat(buf.readerOffset(8)).isSameAs(buf);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void capacityMustBeAllocatedSize(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertEquals(8, buf.capacity());
            try (Buffer b = allocator.allocate(13)) {
                assertEquals(13, b.capacity());
            }
        }
    }

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
    void readerWriterOffsetUpdates(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(22)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeInt(0).readShort();
            buf.reset();
            assertEquals(0, buf.readerOffset());
            assertEquals(0, buf.writerOffset());
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
                    try (Buffer ignored1 = Buffer.compose(allocator, buf, slice)) {
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
                    try (Buffer ignored1 = Buffer.compose(allocator, buf, slice)) {
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

    private static void testCopyIntoByteBuffer(Fixture fixture, Function<Integer, ByteBuffer> bbAlloc) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN).writeLong(0x0102030405060708L);
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

            buf.writerOffset(0).order(LITTLE_ENDIAN).writeLong(0x0102030405060708L);
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
                    return Buffer.compose(a, bufFirst, bufSecond);
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
                    return Buffer.compose(a, bufFirst, bufSecond);
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
                    return Buffer.compose(a, bufFirst, bufSecond);
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
                    return Buffer.compose(a, bufFirst, bufSecond);
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
                    return scope.add(Buffer.compose(a, bufFirst, bufSecond)).writerOffset(size).slice();
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
                    return scope.add(Buffer.compose(a, bufFirst, bufSecond)).writerOffset(size).slice();
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
                    return scope.add(Buffer.compose(a, bufFirst, bufSecond)).writerOffset(size).slice();
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
                    return scope.add(Buffer.compose(a, bufFirst, bufSecond)).writerOffset(size).slice();
                }
            });
        }
    }

    private static void testCopyIntoBuf(Fixture fixture, Function<Integer, Buffer> bbAlloc) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN).writeLong(0x0102030405060708L);
            Buffer buffer = bbAlloc.apply(8);
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

            buf.writerOffset(0).order(LITTLE_ENDIAN).writeLong(0x0102030405060708L);
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
            buf.order(BIG_ENDIAN).writeLong(0x0102030405060708L);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(16)) {
            buf.writeLong(0);
            assertEquals(Long.BYTES, buf.readableBytes());
            buf.readShort();
            assertEquals(Long.BYTES - Short.BYTES, buf.readableBytes());
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

    private static void checkByteIteration(Buffer buf) {
        var cursor = buf.openCursor();
        assertFalse(cursor.readByte());
        assertFalse(cursor.readLong());
        assertEquals(0, cursor.bytesLeft());
        assertEquals((byte) -1, cursor.getByte());
        assertEquals(-1L, cursor.getLong());

        for (int i = 0; i < 0x27; i++) {
            buf.writeByte((byte) (i + 1));
        }
        int roff = buf.readerOffset();
        int woff = buf.writerOffset();
        cursor = buf.openCursor();
        assertEquals(0x27, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x01, cursor.getByte());
        assertEquals((byte) 0x01, cursor.getByte());
        assertTrue(cursor.readLong());
        assertEquals(0x0203040506070809L, cursor.getLong());
        assertEquals(0x0203040506070809L, cursor.getLong());
        assertEquals(0x1E, cursor.bytesLeft());
        assertTrue(cursor.readLong());
        assertEquals(0x0A0B0C0D0E0F1011L, cursor.getLong());
        assertEquals(0x16, cursor.bytesLeft());
        assertTrue(cursor.readLong());
        assertEquals(0x1213141516171819L, cursor.getLong());
        assertEquals(0x0E, cursor.bytesLeft());
        assertTrue(cursor.readLong());
        assertEquals(0x1A1B1C1D1E1F2021L, cursor.getLong());
        assertEquals(6, cursor.bytesLeft());
        assertFalse(cursor.readLong());
        assertEquals(6, cursor.bytesLeft());
        assertEquals(0x1A1B1C1D1E1F2021L, cursor.getLong());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x22, cursor.getByte());
        assertEquals((byte) 0x22, cursor.getByte());
        assertEquals(5, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x23, cursor.getByte());
        assertEquals(4, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x24, cursor.getByte());
        assertEquals(3, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x25, cursor.getByte());
        assertEquals(2, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x26, cursor.getByte());
        assertEquals(1, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x27, cursor.getByte());
        assertEquals(0, cursor.bytesLeft());
        assertFalse(cursor.readByte());
        assertEquals((byte) 0x27, cursor.getByte());
        assertEquals((byte) 0x27, cursor.getByte());
        assertFalse(cursor.readLong());
        assertEquals(roff, buf.readerOffset());
        assertEquals(woff, buf.writerOffset());
    }

    private static void checkByteIterationOfRegion(Buffer buf) {
        assertThrows(IllegalArgumentException.class, () -> buf.openCursor(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> buf.openCursor(1, -1));
        assertThrows(IllegalArgumentException.class, () -> buf.openCursor(buf.capacity(), 1));
        assertThrows(IllegalArgumentException.class, () -> buf.openCursor(buf.capacity() - 1, 2));
        assertThrows(IllegalArgumentException.class, () -> buf.openCursor(buf.capacity() - 2, 3));

        var cursor = buf.openCursor(1, 0);
        assertFalse(cursor.readByte());
        assertFalse(cursor.readLong());
        assertEquals(0, cursor.bytesLeft());
        assertEquals((byte) -1, cursor.getByte());
        assertEquals(-1L, cursor.getLong());

        for (int i = 0; i < 0x27; i++) {
            buf.writeByte((byte) (i + 1));
        }
        int roff = buf.readerOffset();
        int woff = buf.writerOffset();
        cursor = buf.openCursor(buf.readerOffset() + 1, buf.readableBytes() - 2);
        assertEquals(0x25, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x02, cursor.getByte());
        assertEquals((byte) 0x02, cursor.getByte());
        assertTrue(cursor.readLong());
        assertEquals(0x030405060708090AL, cursor.getLong());
        assertEquals(0x1C, cursor.bytesLeft());
        assertTrue(cursor.readLong());
        assertEquals(0x0B0C0D0E0F101112L, cursor.getLong());
        assertEquals(0x14, cursor.bytesLeft());
        assertTrue(cursor.readLong());
        assertEquals(0x131415161718191AL, cursor.getLong());
        assertEquals(0x0C, cursor.bytesLeft());
        assertTrue(cursor.readLong());
        assertEquals(0x1B1C1D1E1F202122L, cursor.getLong());
        assertEquals(4, cursor.bytesLeft());
        assertFalse(cursor.readLong());
        assertEquals(4, cursor.bytesLeft());
        assertEquals(0x1B1C1D1E1F202122L, cursor.getLong());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x23, cursor.getByte());
        assertEquals(3, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x24, cursor.getByte());
        assertEquals(2, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x25, cursor.getByte());
        assertEquals(1, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x26, cursor.getByte());
        assertEquals(0, cursor.bytesLeft());
        assertFalse(cursor.readByte());
        assertEquals(0, cursor.bytesLeft());
        assertEquals(0x1B1C1D1E1F202122L, cursor.getLong());
        assertEquals((byte) 0x26, cursor.getByte());

        cursor = buf.openCursor(buf.readerOffset() + 1, 2);
        assertEquals(2, cursor.bytesLeft());
        assertFalse(cursor.readLong());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x02, cursor.getByte());
        assertEquals(1, cursor.bytesLeft());
        assertFalse(cursor.readLong());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x03, cursor.getByte());
        assertEquals(0, cursor.bytesLeft());
        assertFalse(cursor.readByte());
        assertFalse(cursor.readLong());
        assertEquals(roff, buf.readerOffset());
        assertEquals(woff, buf.writerOffset());
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

    private static void checkReverseByteIteration(Buffer buf) {
        var cursor = buf.openReverseCursor();
        assertFalse(cursor.readByte());
        assertFalse(cursor.readLong());
        assertEquals(0, cursor.bytesLeft());
        assertEquals((byte) -1, cursor.getByte());
        assertEquals(-1L, cursor.getLong());

        for (int i = 0; i < 0x27; i++) {
            buf.writeByte((byte) (i + 1));
        }
        int roff = buf.readerOffset();
        int woff = buf.writerOffset();
        cursor = buf.openReverseCursor();
        assertEquals(0x27, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x27, cursor.getByte());
        assertEquals((byte) 0x27, cursor.getByte());
        assertTrue(cursor.readLong());
        assertEquals(0x262524232221201FL, cursor.getLong());
        assertEquals(0x1E, cursor.bytesLeft());
        assertTrue(cursor.readLong());
        assertEquals(0x1E1D1C1B1A191817L, cursor.getLong());
        assertEquals(0x16, cursor.bytesLeft());
        assertTrue(cursor.readLong());
        assertEquals(0x161514131211100FL, cursor.getLong());
        assertEquals(0x0E, cursor.bytesLeft());
        assertTrue(cursor.readLong());
        assertEquals(0x0E0D0C0B0A090807L, cursor.getLong());
        assertEquals(6, cursor.bytesLeft());
        assertFalse(cursor.readLong());
        assertEquals(6, cursor.bytesLeft());
        assertEquals(0x0E0D0C0B0A090807L, cursor.getLong());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x06, cursor.getByte());
        assertEquals(5, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x05, cursor.getByte());
        assertEquals(4, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x04, cursor.getByte());
        assertEquals(3, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x03, cursor.getByte());
        assertEquals(2, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x02, cursor.getByte());
        assertEquals(1, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x01, cursor.getByte());
        assertEquals(0, cursor.bytesLeft());
        assertFalse(cursor.readByte());
        assertEquals((byte) 0x01, cursor.getByte());
        assertFalse(cursor.readByte());
        assertEquals(0, cursor.bytesLeft());
        assertEquals(0x0E0D0C0B0A090807L, cursor.getLong());
        assertEquals(roff, buf.readerOffset());
        assertEquals(woff, buf.writerOffset());
    }

    private static void checkReverseByteIterationOfRegion(Buffer buf) {
        assertThrows(IllegalArgumentException.class, () -> buf.openReverseCursor(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> buf.openReverseCursor(0, -1));
        assertThrows(IllegalArgumentException.class, () -> buf.openReverseCursor(0, 2));
        assertThrows(IllegalArgumentException.class, () -> buf.openReverseCursor(1, 3));
        assertThrows(IllegalArgumentException.class, () -> buf.openReverseCursor(buf.capacity(), 0));

        var cursor = buf.openReverseCursor(1, 0);
        assertFalse(cursor.readByte());
        assertFalse(cursor.readLong());
        assertEquals(0, cursor.bytesLeft());
        assertEquals((byte) -1, cursor.getByte());
        assertEquals(-1L, cursor.getLong());

        for (int i = 0; i < 0x27; i++) {
            buf.writeByte((byte) (i + 1));
        }
        int roff = buf.readerOffset();
        int woff = buf.writerOffset();
        cursor = buf.openReverseCursor(buf.writerOffset() - 2, buf.readableBytes() - 2);
        assertEquals(0x25, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x26, cursor.getByte());
        assertEquals((byte) 0x26, cursor.getByte());
        assertTrue(cursor.readLong());
        assertEquals(0x2524232221201F1EL, cursor.getLong());
        assertEquals(0x1C, cursor.bytesLeft());
        assertTrue(cursor.readLong());
        assertEquals(0x1D1C1B1A19181716L, cursor.getLong());
        assertEquals(0x14, cursor.bytesLeft());
        assertTrue(cursor.readLong());
        assertEquals(0x1514131211100F0EL, cursor.getLong());
        assertEquals(0x0C, cursor.bytesLeft());
        assertTrue(cursor.readLong());
        assertEquals(0x0D0C0B0A09080706L, cursor.getLong());
        assertEquals(4, cursor.bytesLeft());
        assertFalse(cursor.readLong());
        assertEquals(4, cursor.bytesLeft());
        assertEquals(0x0D0C0B0A09080706L, cursor.getLong());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x05, cursor.getByte());
        assertEquals(3, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x04, cursor.getByte());
        assertEquals(2, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x03, cursor.getByte());
        assertEquals(1, cursor.bytesLeft());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x02, cursor.getByte());
        assertEquals(0, cursor.bytesLeft());
        assertFalse(cursor.readByte());
        assertEquals((byte) 0x02, cursor.getByte());
        assertFalse(cursor.readByte());
        assertEquals(0, cursor.bytesLeft());
        assertEquals(0x0D0C0B0A09080706L, cursor.getLong());

        cursor = buf.openReverseCursor(buf.readerOffset() + 2, 2);
        assertEquals(2, cursor.bytesLeft());
        assertFalse(cursor.readLong());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x03, cursor.getByte());
        assertEquals(1, cursor.bytesLeft());
        assertFalse(cursor.readLong());
        assertTrue(cursor.readByte());
        assertEquals((byte) 0x02, cursor.getByte());
        assertEquals(0, cursor.bytesLeft());
        assertFalse(cursor.readByte());
        assertFalse(cursor.readLong());
        assertEquals(roff, buf.readerOffset());
        assertEquals(woff, buf.writerOffset());
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

    @Nested
    @Isolated
    class CleanerTests {
        @ParameterizedTest
        @MethodSource("io.netty.buffer.api.BufferTest#directAllocators")
        public void bufferMustBeClosedByCleaner(Fixture fixture) throws InterruptedException {
            var initial = NativeMemorySegmentManager.MEM_USAGE_NATIVE.sum();
            var allocator = fixture.createAllocator();
            allocator.close();
            int iterations = 50;
            int allocationSize = 1024;
            for (int i = 0; i < iterations; i++) {
                allocateAndForget(allocator, allocationSize);
                System.gc();
                System.runFinalization();
            }
            var sum = NativeMemorySegmentManager.MEM_USAGE_NATIVE.sum() - initial;
            var totalAllocated = (long) allocationSize * iterations;
            assertThat(sum).isLessThan(totalAllocated);
        }

        private static void allocateAndForget(BufferAllocator allocator, int size) {
            allocator.allocate(size);
        }

        @ParameterizedTest
        @MethodSource("io.netty.buffer.api.BufferTest#pooledDirectAllocators")
        public void buffersMustBeReusedByPoolingAllocatorEvenWhenDroppedByCleanerInsteadOfExplicitly(Fixture fixture)
                throws InterruptedException {
            var initial = NativeMemorySegmentManager.MEM_USAGE_NATIVE.sum();
            try (var allocator = fixture.createAllocator()) {
                int iterations = 50;
                int allocationSize = 1024;
                for (int i = 0; i < iterations; i++) {
                    allocateAndForget(allocator, allocationSize);
                    System.gc();
                    System.runFinalization();
                }
                var sum = NativeMemorySegmentManager.MEM_USAGE_NATIVE.sum() - initial;
                var totalAllocated = (long) allocationSize * iterations;
                assertThat(sum).isLessThan(totalAllocated);
            }
        }
    }

    @Test
    public void compositeBufferCanOnlyBeOwnedWhenAllConstituentBuffersAreOwned() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(8)) {
                assertTrue(a.isOwned());
                Buffer leakB;
                try (Buffer b = allocator.allocate(8)) {
                    assertTrue(a.isOwned());
                    assertTrue(b.isOwned());
                    composite = Buffer.compose(allocator, a, b);
                    assertFalse(composite.isOwned());
                    assertFalse(a.isOwned());
                    assertFalse(b.isOwned());
                    leakB = b;
                }
                assertFalse(composite.isOwned());
                assertFalse(a.isOwned());
                assertTrue(leakB.isOwned());
            }
            assertTrue(composite.isOwned());
        }
    }

    @Test
    public void compositeBuffersCannotHaveDuplicateComponents() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer a = allocator.allocate(4)) {
            var e = assertThrows(IllegalArgumentException.class, () -> Buffer.compose(allocator, a, a));
            assertThat(e).hasMessageContaining("duplicate");

            try (Buffer composite = Buffer.compose(allocator, a)) {
                a.close();
                try {
                    e = assertThrows(IllegalArgumentException.class, () -> Buffer.extendComposite(composite, a));
                    assertThat(e).hasMessageContaining("duplicate");
                } finally {
                    a.acquire();
                }
            }
        }
    }

    @Test
    public void compositeBufferFromSends() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer composite = Buffer.compose(allocator,
                     allocator.allocate(8).send(),
                     allocator.allocate(8).send(),
                     allocator.allocate(8).send())) {
            assertEquals(24, composite.capacity());
            assertTrue(composite.isOwned());
        }
    }

    @Test
    public void compositeBufferMustNotBeAllowedToContainThemselves() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer a = allocator.allocate(4);
            Buffer buf = Buffer.compose(allocator, a);
            try (buf; a) {
                a.close();
                try {
                    assertThrows(IllegalArgumentException.class, () -> Buffer.extendComposite(buf, buf));
                    assertTrue(buf.isOwned());
                    try (Buffer composite = Buffer.compose(allocator, buf)) {
                        // the composing increments the reference count of constituent buffers...
                        // counter-act this so it can be extended:
                        a.close(); // buf is now owned so it can be extended.
                        try {
                            assertThrows(IllegalArgumentException.class, () -> Buffer.extendComposite(buf, composite));
                        } finally {
                            a.acquire(); // restore the reference count to align with our try-with-resources structure.
                        }
                    }
                    assertTrue(buf.isOwned());
                } finally {
                    a.acquire();
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void ensureWritableMustThrowForBorrowedBuffers(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            try (Buffer slice = buf.slice()) {
                assertThrows(IllegalStateException.class, () -> slice.ensureWritable(1));
                assertThrows(IllegalStateException.class, () -> buf.ensureWritable(1));
            }
            try (Buffer compose = Buffer.compose(allocator, buf)) {
                assertThrows(IllegalStateException.class, () -> compose.ensureWritable(1));
                assertThrows(IllegalStateException.class, () -> buf.ensureWritable(1));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void ensureWritableMustThrowForNegativeSize(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IllegalArgumentException.class, () -> buf.ensureWritable(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void ensureWritableMustThrowIfRequestedSizeWouldGrowBeyondMaxAllowed(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(512)) {
            assertThrows(IllegalArgumentException.class, () -> buf.ensureWritable(Integer.MAX_VALUE - 8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void ensureWritableMustNotThrowWhenSpaceIsAlreadyAvailable(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.ensureWritable(8);
            buf.writeLong(1);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.writeByte((byte) 1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void ensureWritableMustExpandBufferCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThat(buf.writableBytes()).isEqualTo(8);
            buf.writeLong(0x0102030405060708L);
            assertThat(buf.writableBytes()).isEqualTo(0);
            buf.ensureWritable(8);
            assertThat(buf.writableBytes()).isGreaterThanOrEqualTo(8);
            assertThat(buf.capacity()).isGreaterThanOrEqualTo(16);
            buf.writeLong(0xA1A2A3A4A5A6A7A8L);
            assertThat(buf.readableBytes()).isEqualTo(16);
            assertThat(buf.readLong()).isEqualTo(0x0102030405060708L);
            assertThat(buf.readLong()).isEqualTo(0xA1A2A3A4A5A6A7A8L);
            assertThrows(IndexOutOfBoundsException.class, buf::readByte);
            // Is it implementation dependent if the capacity increased by *exactly* the requested size, or more.
        }
    }

    @Test
    public void ensureWritableMustExpandCapacityOfEmptyCompositeBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer buf = Buffer.compose(allocator)) {
            assertThat(buf.writableBytes()).isEqualTo(0);
            buf.ensureWritable(8);
            assertThat(buf.writableBytes()).isGreaterThanOrEqualTo(8);
            buf.writeLong(0xA1A2A3A4A5A6A7A8L);
            assertThat(buf.readableBytes()).isEqualTo(8);
            assertThat(buf.readLong()).isEqualTo(0xA1A2A3A4A5A6A7A8L);
            assertThrows(IndexOutOfBoundsException.class, buf::readByte);
            // Is it implementation dependent if the capacity increased by *exactly* the requested size, or more.
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void mustBeAbleToSliceAfterEnsureWritable(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(4)) {
            buf.ensureWritable(8);
            assertThat(buf.writableBytes()).isGreaterThanOrEqualTo(8);
            assertThat(buf.capacity()).isGreaterThanOrEqualTo(8);
            buf.writeLong(0x0102030405060708L);
            try (Buffer slice = buf.slice()) {
                assertEquals(0x0102030405060708L, slice.readLong());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void ensureWritableOnCompositeBuffersMustRespectExistingBigEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(4, BIG_ENDIAN)) {
                composite = Buffer.compose(allocator, a);
            }
            try (composite) {
                composite.writeInt(0x01020304);
                composite.ensureWritable(4);
                composite.writeInt(0x05060708);
                assertEquals(0x0102030405060708L, composite.readLong());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void ensureWritableOnCompositeBuffersMustRespectExistingLittleEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(4, LITTLE_ENDIAN)) {
                composite = Buffer.compose(allocator, a);
            }
            try (composite) {
                composite.writeInt(0x05060708);
                composite.ensureWritable(4);
                composite.writeInt(0x01020304);
                assertEquals(0x0102030405060708L, composite.readLong());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void ensureWritableWithCompactionMustNotAllocateIfCompactionIsEnough(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(64)) {
            while (buf.writableBytes() > 0) {
                buf.writeByte((byte) 42);
            }
            while (buf.readableBytes() > 0) {
                buf.readByte();
            }
            buf.ensureWritable(4, true);
            buf.writeInt(42);
            assertThat(buf.capacity()).isEqualTo(64);

            buf.writerOffset(60).readerOffset(60);
            buf.ensureWritable(8, true);
            buf.writeLong(42);
            // Don't assert the capacity on this one, because single-component
            // composite buffers may choose to allocate rather than compact.
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

    @Test
    public void emptyCompositeBufferMustUseNativeByteOrder() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer composite = Buffer.compose(allocator)) {
            assertThat(composite.order()).isEqualTo(ByteOrder.nativeOrder());
        }
    }

    @Test
    public void extendOnNonCompositeBufferMustThrow() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer a = allocator.allocate(8);
             Buffer b = allocator.allocate(8)) {
            var exc = assertThrows(IllegalArgumentException.class, () -> Buffer.extendComposite(a, b));
            assertThat(exc).hasMessageContaining("Expected").hasMessageContaining("composite");
        }
    }

    @Test
    public void extendingNonOwnedCompositeBufferMustThrow() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer a = allocator.allocate(8);
             Buffer b = allocator.allocate(8);
             Buffer composed = Buffer.compose(allocator, a)) {
            try (Buffer ignore = composed.acquire()) {
                var exc = assertThrows(IllegalStateException.class, () -> Buffer.extendComposite(composed, b));
                assertThat(exc).hasMessageContaining("owned");
            }
        }
    }

    @Test
    public void extendingCompositeBufferWithItselfMustThrow() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(8)) {
                composite = Buffer.compose(allocator, a);
            }
            try (composite) {
                var exc = assertThrows(IllegalArgumentException.class,
                                       () -> Buffer.extendComposite(composite, composite));
                assertThat(exc).hasMessageContaining("cannot be extended");
            }
        }
    }

    @Test
    public void extendingWithZeroCapacityBufferHasNoEffect() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer composite = Buffer.compose(allocator)) {
            Buffer.extendComposite(composite, composite);
            assertThat(composite.capacity()).isZero();
            assertThat(composite.countComponents()).isZero();
        }
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer a = allocator.allocate(1);
            Buffer composite = Buffer.compose(allocator, a);
            a.close();
            assertTrue(composite.isOwned());
            assertThat(composite.capacity()).isOne();
            assertThat(composite.countComponents()).isOne();
            try (Buffer b = Buffer.compose(allocator)) {
                Buffer.extendComposite(composite, b);
            }
            assertTrue(composite.isOwned());
            assertThat(composite.capacity()).isOne();
            assertThat(composite.countComponents()).isOne();
        }
    }

    @Test
    public void extendingCompositeBufferWithNullMustThrow() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer composite = Buffer.compose(allocator)) {
            assertThrows(NullPointerException.class, () -> Buffer.extendComposite(composite, null));
        }
    }

    @Test
    public void extendingCompositeBufferMustIncreaseCapacityByGivenBigEndianBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer composite = Buffer.compose(allocator)) {
            assertThat(composite.capacity()).isZero();
            try (Buffer buf = allocator.allocate(8, BIG_ENDIAN)) {
                Buffer.extendComposite(composite, buf);
            }
            assertThat(composite.capacity()).isEqualTo(8);
            composite.writeLong(0x0102030405060708L);
            assertThat(composite.readLong()).isEqualTo(0x0102030405060708L);
        }
    }

    @Test
    public void extendingCompositeBufferMustIncreaseCapacityByGivenLittleEndianBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer composite = Buffer.compose(allocator)) {
            assertThat(composite.capacity()).isZero();
            try (Buffer buf = allocator.allocate(8, LITTLE_ENDIAN)) {
                Buffer.extendComposite(composite, buf);
            }
            assertThat(composite.capacity()).isEqualTo(8);
            composite.writeLong(0x0102030405060708L);
            assertThat(composite.readLong()).isEqualTo(0x0102030405060708L);
        }
    }

    @Test
    public void extendingBigEndianCompositeBufferMustThrowIfExtensionIsLittleEndian() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(8, BIG_ENDIAN)) {
                composite = Buffer.compose(allocator, a);
            }
            try (composite) {
                try (Buffer b = allocator.allocate(8, LITTLE_ENDIAN)) {
                    var exc = assertThrows(IllegalArgumentException.class, () -> Buffer.extendComposite(composite, b));
                    assertThat(exc).hasMessageContaining("byte order");
                }
            }
        }
    }

    @Test
    public void extendingLittleEndianCompositeBufferMustThrowIfExtensionIsBigEndian() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(8, LITTLE_ENDIAN)) {
                composite = Buffer.compose(allocator, a);
            }
            try (composite) {
                try (Buffer b = allocator.allocate(8, BIG_ENDIAN)) {
                    var exc = assertThrows(IllegalArgumentException.class, () -> Buffer.extendComposite(composite, b));
                    assertThat(exc).hasMessageContaining("byte order");
                }
            }
        }
    }

    @Test
    public void emptyCompositeBufferMustAllowExtendingWithBufferWithBigEndianByteOrder() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            try (Buffer composite = Buffer.compose(allocator)) {
                try (Buffer b = allocator.allocate(8, BIG_ENDIAN)) {
                    Buffer.extendComposite(composite, b);
                    assertThat(composite.order()).isEqualTo(BIG_ENDIAN);
                }
            }
        }
    }

    @Test
    public void emptyCompositeBufferMustAllowExtendingWithBufferWithLittleEndianByteOrder() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            try (Buffer composite = Buffer.compose(allocator)) {
                try (Buffer b = allocator.allocate(8, LITTLE_ENDIAN)) {
                    Buffer.extendComposite(composite, b);
                    assertThat(composite.order()).isEqualTo(LITTLE_ENDIAN);
                }
            }
        }
    }

    @Test
    public void emptyCompositeBufferMustAllowExtendingWithReadOnlyBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            try (Buffer composite = Buffer.compose(allocator)) {
                try (Buffer b = allocator.allocate(8).readOnly(true)) {
                    Buffer.extendComposite(composite, b);
                    assertTrue(composite.readOnly());
                }
            }
        }
    }

    @Test
    public void whenExtendingCompositeBufferWithWriteOffsetAtCapacityExtensionWriteOffsetCanBeNonZero() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(8)) {
                composite = Buffer.compose(allocator, a);
            }
            try (composite) {
                composite.writeLong(0);
                try (Buffer b = allocator.allocate(8)) {
                    b.writeInt(1);
                    Buffer.extendComposite(composite, b);
                    assertThat(composite.capacity()).isEqualTo(16);
                    assertThat(composite.writerOffset()).isEqualTo(12);
                }
            }
        }
    }

    @Test
    public void whenExtendingCompositeBufferWithWriteOffsetLessThanCapacityExtensionWriteOffsetMustZero() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(8)) {
                composite = Buffer.compose(allocator, a);
            }
            try (composite) {
                composite.writeInt(0);
                try (Buffer b = allocator.allocate(8)) {
                    b.writeInt(1);
                    var exc = assertThrows(IllegalArgumentException.class, () -> Buffer.extendComposite(composite, b));
                    assertThat(exc).hasMessageContaining("unwritten gap");
                    b.writerOffset(0);
                    Buffer.extendComposite(composite, b);
                    assertThat(composite.capacity()).isEqualTo(16);
                    assertThat(composite.writerOffset()).isEqualTo(4);
                }
            }
        }
    }

    @Test
    public void whenExtendingCompositeBufferWithReadOffsetAtCapacityExtensionReadOffsetCanBeNonZero() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(8)) {
                composite = Buffer.compose(allocator, a);
            }
            try (composite) {
                composite.writeLong(0);
                composite.readLong();
                try (Buffer b = allocator.allocate(8)) {
                    b.writeInt(1);
                    b.readInt();
                    Buffer.extendComposite(composite, b);
                    assertThat(composite.capacity()).isEqualTo(16);
                    assertThat(composite.writerOffset()).isEqualTo(12);
                }
            }
        }
    }

    @Test
    public void whenExtendingCompositeBufferWithReadOffsetLessThanCapacityExtensionReadOffsetMustZero() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(8)) {
                composite = Buffer.compose(allocator, a);
            }
            try (composite) {
                composite.writeLong(0);
                composite.readInt();
                try (Buffer b = allocator.allocate(8)) {
                    b.writeInt(1);
                    b.readInt();
                    var exc = assertThrows(IllegalArgumentException.class, () -> Buffer.extendComposite(composite, b));
                    assertThat(exc).hasMessageContaining("unread gap");
                    b.readerOffset(0);
                    Buffer.extendComposite(composite, b);
                    assertThat(composite.capacity()).isEqualTo(16);
                    assertThat(composite.writerOffset()).isEqualTo(12);
                    assertThat(composite.readerOffset()).isEqualTo(4);
                }
            }
        }
    }

    @Test
    public void composeMustThrowWhenBuffersHaveMismatchedByteOrder() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer a = allocator.allocate(4, BIG_ENDIAN);
             Buffer b = allocator.allocate(4, LITTLE_ENDIAN)) {
            assertThrows(IllegalArgumentException.class, () -> Buffer.compose(allocator, a, b));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void bifurcateOfNonOwnedBufferMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeInt(1);
            try (Buffer acquired = buf.acquire()) {
                var exc = assertThrows(IllegalStateException.class, () -> acquired.bifurcate());
                assertThat(exc).hasMessageContaining("owned");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void bifurcatedPartMustContainFirstHalfOfBuffer(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(16).order(BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L);
            assertThat(buf.readByte()).isEqualTo((byte) 0x01);
            try (Buffer bif = buf.bifurcate()) {
                // Original buffer:
                assertThat(buf.capacity()).isEqualTo(8);
                assertThat(buf.readerOffset()).isZero();
                assertThat(buf.writerOffset()).isZero();
                assertThat(buf.readableBytes()).isZero();
                assertThrows(IndexOutOfBoundsException.class, () -> buf.readByte());

                // Bifurcated part:
                assertThat(bif.capacity()).isEqualTo(8);
                assertThat(bif.readerOffset()).isOne();
                assertThat(bif.writerOffset()).isEqualTo(8);
                assertThat(bif.readableBytes()).isEqualTo(7);
                assertThat(bif.readByte()).isEqualTo((byte) 0x02);
                assertThat(bif.readInt()).isEqualTo(0x03040506);
                assertThat(bif.readByte()).isEqualTo((byte) 0x07);
                assertThat(bif.readByte()).isEqualTo((byte) 0x08);
                assertThrows(IndexOutOfBoundsException.class, () -> bif.readByte());
            }

            // Bifurcated part does NOT return when closed:
            assertThat(buf.capacity()).isEqualTo(8);
            assertThat(buf.readerOffset()).isZero();
            assertThat(buf.writerOffset()).isZero();
            assertThat(buf.readableBytes()).isZero();
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void bifurcatedPartsMustBeIndividuallySendable(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(16).order(BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L);
            assertThat(buf.readByte()).isEqualTo((byte) 0x01);
            try (Buffer sentBif = buf.bifurcate().send().receive()) {
                try (Buffer sentBuf = buf.send().receive()) {
                    assertThat(sentBuf.capacity()).isEqualTo(8);
                    assertThat(sentBuf.readerOffset()).isZero();
                    assertThat(sentBuf.writerOffset()).isZero();
                    assertThat(sentBuf.readableBytes()).isZero();
                    assertThrows(IndexOutOfBoundsException.class, () -> sentBuf.readByte());
                }

                assertThat(sentBif.capacity()).isEqualTo(8);
                assertThat(sentBif.readerOffset()).isOne();
                assertThat(sentBif.writerOffset()).isEqualTo(8);
                assertThat(sentBif.readableBytes()).isEqualTo(7);
                assertThat(sentBif.readByte()).isEqualTo((byte) 0x02);
                assertThat(sentBif.readInt()).isEqualTo(0x03040506);
                assertThat(sentBif.readByte()).isEqualTo((byte) 0x07);
                assertThat(sentBif.readByte()).isEqualTo((byte) 0x08);
                assertThrows(IndexOutOfBoundsException.class, () -> sentBif.readByte());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void mustBePossibleToBifurcateMoreThanOnce(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(16).order(BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L);
            try (Buffer a = buf.bifurcate()) {
                a.writerOffset(4);
                try (Buffer b = a.bifurcate()) {
                    assertEquals(0x01020304, b.readInt());
                    a.writerOffset(4);
                    assertEquals(0x05060708, a.readInt());
                    assertThrows(IndexOutOfBoundsException.class, () -> b.readByte());
                    assertThrows(IndexOutOfBoundsException.class, () -> a.readByte());
                    buf.writeLong(0xA1A2A3A4A5A6A7A8L);
                    buf.writerOffset(4);
                    try (Buffer c = buf.bifurcate()) {
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
    public void bifurcatedBufferMustHaveSameByteOrderAsParent(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8).order(BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L);
            try (Buffer a = buf.bifurcate()) {
                assertThat(a.order()).isEqualTo(BIG_ENDIAN);
                a.order(LITTLE_ENDIAN);
                a.writerOffset(4);
                try (Buffer b = a.bifurcate()) {
                    assertThat(b.order()).isEqualTo(LITTLE_ENDIAN);
                    assertThat(buf.order()).isEqualTo(BIG_ENDIAN);
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void ensureWritableOnBifurcatedBuffers(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeLong(0x0102030405060708L);
            try (Buffer a = buf.bifurcate()) {
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
    public void ensureWritableOnBifurcatedBuffersWithOddOffsets(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(10).order(BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L);
            buf.writeByte((byte) 0x09);
            buf.readByte();
            try (Buffer a = buf.bifurcate()) {
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
    public void bifurcateOnEmptyBigEndianCompositeBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer buf = Buffer.compose(allocator).order(BIG_ENDIAN)) {
            verifyBifurcateEmptyCompositeBuffer(buf);
        }
    }

    @Test
    public void bifurcateOnEmptyLittleEndianCompositeBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer buf = Buffer.compose(allocator).order(LITTLE_ENDIAN)) {
            verifyBifurcateEmptyCompositeBuffer(buf);
        }
    }

    private static void verifyBifurcateEmptyCompositeBuffer(Buffer buf) {
        try (Buffer a = buf.bifurcate()) {
            a.ensureWritable(4);
            buf.ensureWritable(4);
            a.writeInt(1);
            buf.writeInt(2);
            assertEquals(1, a.readInt());
            assertEquals(2, buf.readInt());
            assertThat(a.order()).isEqualTo(buf.order());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void bifurcatedBuffersMustBeAccessibleInOtherThreads(Fixture fixture) throws Exception {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeInt(42);
            var send = buf.bifurcate().send();
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
    public void sendMustNotMakeBifurcatedBuffersInaccessible(Fixture fixture) throws Exception {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(16)) {
            buf.writeInt(64);
            var bifA = buf.bifurcate();
            buf.writeInt(42);
            var send = buf.bifurcate().send();
            buf.writeInt(72);
            var bifB = buf.bifurcate();
            var fut = executor.submit(() -> {
                try (Buffer receive = send.receive()) {
                    assertEquals(42, receive.readInt());
                }
            });
            fut.get();
            buf.writeInt(32);
            assertEquals(32, buf.readInt());
            assertEquals(64, bifA.readInt());
            assertEquals(72, bifB.readInt());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void compactMustDiscardReadBytes(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(16, BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L).writeInt(0x090A0B0C);
            assertEquals(0x01020304, buf.readInt());
            assertEquals(12, buf.writerOffset());
            assertEquals(4, buf.readerOffset());
            assertEquals(4, buf.writableBytes());
            assertEquals(8, buf.readableBytes());
            assertEquals(16, buf.capacity());
            buf.compact();
            assertEquals(8, buf.writerOffset());
            assertEquals(0, buf.readerOffset());
            assertEquals(8, buf.writableBytes());
            assertEquals(8, buf.readableBytes());
            assertEquals(16, buf.capacity());
            assertEquals(0x05060708090A0B0CL, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void compactMustThrowForUnownedBuffer(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8, BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L);
            assertEquals((byte) 0x01, buf.readByte());
            try (Buffer ignore = buf.acquire()) {
                assertThrows(IllegalStateException.class, () -> buf.compact());
                assertEquals(1, buf.readerOffset());
            }
            assertEquals((byte) 0x02, buf.readByte());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void readOnlyBufferMustPreventWriteAccess(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            var b = buf.readOnly(true);
            assertThat(b).isSameAs(buf);
            verifyWriteInaccessible(buf);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void closedBuffersAreNotReadOnly(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            Buffer buf = allocator.allocate(8);
            buf.readOnly(true);
            buf.close();
            assertFalse(buf.readOnly());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void readOnlyBufferMustBecomeWritableAgainAfterTogglingReadOnlyOff(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertFalse(buf.readOnly());
            buf.readOnly(true);
            assertTrue(buf.readOnly());
            verifyWriteInaccessible(buf);

            buf.readOnly(false);
            assertFalse(buf.readOnly());

            verifyWriteAccessible(buf);
        }
    }

    private static void verifyWriteAccessible(Buffer buf) {
        buf.reset().writeByte((byte) 32);
        assertThat(buf.readByte()).isEqualTo((byte) 32);
        buf.reset().writerOffset(0).writeUnsignedByte(32);
        assertThat(buf.readUnsignedByte()).isEqualTo(32);
        buf.reset().writerOffset(0).writeChar('3');
        assertThat(buf.readChar()).isEqualTo('3');
        buf.reset().writerOffset(0).writeShort((short) 32);
        assertThat(buf.readShort()).isEqualTo((short) 32);
        buf.reset().writerOffset(0).writeUnsignedShort(32);
        assertThat(buf.readUnsignedShort()).isEqualTo(32);
        buf.reset().writerOffset(0).writeMedium(32);
        assertThat(buf.readMedium()).isEqualTo(32);
        buf.reset().writerOffset(0).writeUnsignedMedium(32);
        assertThat(buf.readUnsignedMedium()).isEqualTo(32);
        buf.reset().writerOffset(0).writeInt(32);
        assertThat(buf.readInt()).isEqualTo(32);
        buf.reset().writerOffset(0).writeUnsignedInt(32);
        assertThat(buf.readUnsignedInt()).isEqualTo(32L);
        buf.reset().writerOffset(0).writeFloat(3.2f);
        assertThat(buf.readFloat()).isEqualTo(3.2f);
        buf.reset().writerOffset(0).writeLong(32);
        assertThat(buf.readLong()).isEqualTo(32L);
        buf.reset().writerOffset(0).writeDouble(3.2);
        assertThat(buf.readDouble()).isEqualTo(3.2);

        buf.setByte(0, (byte) 32);
        assertThat(buf.getByte(0)).isEqualTo((byte) 32);
        buf.setUnsignedByte(0, 32);
        assertThat(buf.getUnsignedByte(0)).isEqualTo(32);
        buf.setChar(0, '3');
        assertThat(buf.getChar(0)).isEqualTo('3');
        buf.setShort(0, (short) 32);
        assertThat(buf.getShort(0)).isEqualTo((short) 32);
        buf.setUnsignedShort(0, 32);
        assertThat(buf.getUnsignedShort(0)).isEqualTo(32);
        buf.setMedium(0, 32);
        assertThat(buf.getMedium(0)).isEqualTo(32);
        buf.setUnsignedMedium(0, 32);
        assertThat(buf.getUnsignedMedium(0)).isEqualTo(32);
        buf.setInt(0, 32);
        assertThat(buf.getInt(0)).isEqualTo(32);
        buf.setUnsignedInt(0, 32);
        assertThat(buf.getUnsignedInt(0)).isEqualTo(32L);
        buf.setFloat(0, 3.2f);
        assertThat(buf.getFloat(0)).isEqualTo(3.2f);
        buf.setLong(0, 32);
        assertThat(buf.getLong(0)).isEqualTo(32L);
        buf.setDouble(0, 3.2);
        assertThat(buf.getDouble(0)).isEqualTo(3.2);

        if (buf.isOwned()) {
            buf.ensureWritable(1);
        }
        buf.fill((byte) 0);
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer source = allocator.allocate(8)) {
            source.copyInto(0, buf, 0, 1);
        }
    }

    @Test
    public void composingReadOnlyBuffersMustCreateReadOnlyCompositeBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer a = allocator.allocate(4).readOnly(true);
             Buffer b = allocator.allocate(4).readOnly(true);
             Buffer composite = Buffer.compose(allocator, a, b)) {
            assertTrue(composite.readOnly());
            verifyWriteInaccessible(composite);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void readOnlyBufferMustRemainReadOnlyAfterSend(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true);
            var send = buf.send();
            try (Buffer receive = send.receive()) {
                assertTrue(receive.readOnly());
                verifyWriteInaccessible(receive);
            }
        }
    }

    @Test
    public void readOnlyBufferMustRemainReadOnlyAfterSendForEmptyCompositeBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer buf = Buffer.compose(allocator)) {
            buf.readOnly(true);
            var send = buf.send();
            try (Buffer receive = send.receive()) {
                assertTrue(receive.readOnly());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("pooledAllocators")
    public void readOnlyBufferMustNotBeReadOnlyAfterBeingReusedFromPool(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            for (int i = 0; i < 1000; i++) {
                try (Buffer buf = allocator.allocate(8)) {
                    assertFalse(buf.readOnly());
                    buf.readOnly(true);
                    assertTrue(buf.readOnly());
                }
            }
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
    public void bifurcateOfReadOnlyBufferMustBeReadOnly(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(16)) {
            buf.writeLong(0x0102030405060708L);
            buf.readOnly(true);
            try (Buffer bifurcate = buf.bifurcate()) {
                assertTrue(bifurcate.readOnly());
                assertTrue(buf.readOnly());
            }
        }
    }

    @Test
    public void composingReadOnlyAndWritableBuffersMustThrow() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer a = allocator.allocate(8).readOnly(true);
             Buffer b = allocator.allocate(8)) {
            assertThrows(IllegalArgumentException.class, () -> Buffer.compose(allocator, a, b));
            assertThrows(IllegalArgumentException.class, () -> Buffer.compose(allocator, b, a));
            assertThrows(IllegalArgumentException.class, () -> Buffer.compose(allocator, a, b, a));
            assertThrows(IllegalArgumentException.class, () -> Buffer.compose(allocator, b, a, b));
        }
    }

    @Test
    public void compositeWritableBufferCannotBeExtendedWithReadOnlyBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(8)) {
                composite = Buffer.compose(allocator, a);
            }
            try (composite; Buffer b = allocator.allocate(8).readOnly(true)) {
                assertThrows(IllegalArgumentException.class, () -> Buffer.extendComposite(composite, b));
            }
        }
    }

    @Test
    public void compositeReadOnlyBufferCannotBeExtendedWithWritableBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(8).readOnly(true)) {
                composite = Buffer.compose(allocator, a);
            }
            try (composite; Buffer b = allocator.allocate(8)) {
                assertThrows(IllegalArgumentException.class, () -> Buffer.extendComposite(composite, b));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void compactOnReadOnlyBufferMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true);
            assertThrows(IllegalStateException.class, () -> buf.compact());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void ensureWritableOnReadOnlyBufferMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true);
            assertThrows(IllegalStateException.class, () -> buf.ensureWritable(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void copyIntoOnReadOnlyBufferMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer dest = allocator.allocate(8)) {
            dest.readOnly(true);
            try (Buffer src = allocator.allocate(8)) {
                assertThrows(IllegalStateException.class, () -> src.copyInto(0, dest, 0, 1));
            }
        }
    }
    // todo read only buffer must have zero writable bytes

    @ParameterizedTest
    @MethodSource("nonCompositeAllocators")
    public void componentCountOfNonCompositeBufferMustBeOne(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThat(buf.countComponents()).isOne();
        }
    }

    @ParameterizedTest
    @MethodSource("nonCompositeAllocators")
    public void readableComponentCountMustBeOneIfThereAreReadableBytes(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThat(buf.countReadableComponents()).isZero();
            buf.writeByte((byte) 1);
            assertThat(buf.countReadableComponents()).isOne();
        }
    }

    @ParameterizedTest
    @MethodSource("nonCompositeAllocators")
    public void writableComponentCountMustBeOneIfThereAreWritableBytes(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThat(buf.countWritableComponents()).isOne();
            buf.writeLong(1);
            assertThat(buf.countWritableComponents()).isZero();
        }
    }

    @Test
    public void compositeBufferComponentCountMustBeTransitiveSum() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer buf;
            try (Buffer a = allocator.allocate(8);
                 Buffer b = allocator.allocate(8);
                 Buffer c = allocator.allocate(8);
                 Buffer x = Buffer.compose(allocator, b, c)) {
                buf = Buffer.compose(allocator, a, x);
            }
            assertThat(buf.countComponents()).isEqualTo(3);
            assertThat(buf.countReadableComponents()).isZero();
            assertThat(buf.countWritableComponents()).isEqualTo(3);
            buf.writeInt(1);
            assertThat(buf.countReadableComponents()).isOne();
            assertThat(buf.countWritableComponents()).isEqualTo(3);
            buf.writeInt(1);
            assertThat(buf.countReadableComponents()).isOne();
            assertThat(buf.countWritableComponents()).isEqualTo(2);
            buf.writeInt(1);
            assertThat(buf.countReadableComponents()).isEqualTo(2);
            assertThat(buf.countWritableComponents()).isEqualTo(2);
            buf.writeInt(1);
            assertThat(buf.countReadableComponents()).isEqualTo(2);
            assertThat(buf.countWritableComponents()).isOne();
            buf.writeInt(1);
            assertThat(buf.countReadableComponents()).isEqualTo(3);
            assertThat(buf.countWritableComponents()).isOne();
            buf.writeInt(1);
            assertThat(buf.countReadableComponents()).isEqualTo(3);
            assertThat(buf.countWritableComponents()).isZero();
        }
    }

    @ParameterizedTest
    @MethodSource("nonCompositeAllocators")
    public void forEachReadableMustVisitBuffer(Fixture fixture) {
        long value = 0x0102030405060708L;
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer bufBERW = allocator.allocate(8).order(BIG_ENDIAN).writeLong(value);
             Buffer bufLERW = allocator.allocate(8).order(LITTLE_ENDIAN).writeLong(value);
             Buffer bufBERO = allocator.allocate(8).order(BIG_ENDIAN).writeLong(value).readOnly(true);
             Buffer bufLERO = allocator.allocate(8).order(LITTLE_ENDIAN).writeLong(value).readOnly(true)) {
            verifyForEachReadableSingleComponent(fixture, bufBERW);
            verifyForEachReadableSingleComponent(fixture, bufLERW);
            verifyForEachReadableSingleComponent(fixture, bufBERO);
            verifyForEachReadableSingleComponent(fixture, bufLERO);
        }
    }

    private static void verifyForEachReadableSingleComponent(Fixture fixture, Buffer buf) {
        buf.forEachReadable(0, (index, component) -> {
            var buffer = component.readableBuffer();
            assertThat(buffer.position()).isZero();
            assertThat(buffer.limit()).isEqualTo(8);
            assertThat(buffer.capacity()).isEqualTo(8);
            assertEquals(0x0102030405060708L, buffer.getLong());

            if (fixture.isDirect()) {
                assertThat(component.readableNativeAddress()).isNotZero();
            } else {
                assertThat(component.readableNativeAddress()).isZero();
            }

            if (component.hasReadableArray()) {
                byte[] array = component.readableArray();
                byte[] arrayCopy = new byte[component.readableArrayLength()];
                System.arraycopy(array, component.readableArrayOffset(), arrayCopy, 0, arrayCopy.length);
                if (buffer.order() == BIG_ENDIAN) {
                    assertThat(arrayCopy).containsExactly(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08);
                } else {
                    assertThat(arrayCopy).containsExactly(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01);
                }
            }

            assertThrows(ReadOnlyBufferException.class, () -> buffer.put(0, (byte) 0xFF));
            return true;
        });
    }

    @Test
    public void forEachReadableMustVisitAllReadableConstituentBuffersInOrder() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(4);
                 Buffer b = allocator.allocate(4);
                 Buffer c = allocator.allocate(4)) {
                a.writeInt(1);
                b.writeInt(2);
                c.writeInt(3);
                composite = Buffer.compose(allocator, a, b, c);
            }
            var list = new LinkedList<Integer>(List.of(1, 2, 3));
            int count = composite.forEachReadable(0, (index, component) -> {
                var buffer = component.readableBuffer();
                int bufferValue = buffer.getInt();
                assertEquals(list.pollFirst().intValue(), bufferValue);
                assertEquals(bufferValue, index + 1);
                assertThrows(ReadOnlyBufferException.class, () -> buffer.put(0, (byte) 0xFF));
                return true;
            });
            assertEquals(3, count);
            assertThat(list).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void forEachReadableMustReturnNegativeCountWhenProcessorReturnsFalse(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.writeLong(0x0102030405060708L);
            int count = buf.forEachReadable(0, (index, component) -> false);
            assertEquals(-1, count);
        }
    }

    @Test
    public void forEachReadableMustStopIterationWhenProcessorReturnsFalse() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(4);
                 Buffer b = allocator.allocate(4);
                 Buffer c = allocator.allocate(4)) {
                a.writeInt(1);
                b.writeInt(2);
                c.writeInt(3);
                composite = Buffer.compose(allocator, a, b, c);
            }
            int readPos = composite.readerOffset();
            int writePos = composite.writerOffset();
            var list = new LinkedList<Integer>(List.of(1, 2, 3));
            int count = composite.forEachReadable(0, (index, component) -> {
                var buffer = component.readableBuffer();
                int bufferValue = buffer.getInt();
                assertEquals(list.pollFirst().intValue(), bufferValue);
                assertEquals(bufferValue, index + 1);
                return false;
            });
            assertEquals(-1, count);
            assertThat(list).containsExactly(2, 3);
            assertEquals(readPos, composite.readerOffset());
            assertEquals(writePos, composite.writerOffset());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void forEachReadableOnClosedBufferMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            var buf = allocator.allocate(8);
            buf.writeLong(0);
            buf.close();
            assertThrows(IllegalStateException.class, () -> buf.forEachReadable(0, (component, index) -> true));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void forEachReadableMustAllowCollectingBuffersInArray(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            Buffer buf;
            try (Buffer a = allocator.allocate(4);
                 Buffer b = allocator.allocate(4);
                 Buffer c = allocator.allocate(4)) {
                buf = Buffer.compose(allocator, a, b, c);
            }
            int i = 1;
            while (buf.writableBytes() > 0) {
                buf.writeByte((byte) i++);
            }
            ByteBuffer[] buffers = new ByteBuffer[buf.countReadableComponents()];
            buf.forEachReadable(0, (index, component) -> {
                buffers[index] = component.readableBuffer();
                return true;
            });
            i = 1;
            assertThat(buffers.length).isGreaterThanOrEqualTo(1);
            for (ByteBuffer buffer : buffers) {
                while (buffer.hasRemaining()) {
                    assertEquals((byte) i++, buffer.get());
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void forEachReadableMustExposeByteCursors(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(32).order(BIG_ENDIAN)) {
            buf.writeLong(0x0102030405060708L);
            buf.writeLong(0x1112131415161718L);
            assertEquals(0x01020304, buf.readInt());
            try (Buffer actualData = allocator.allocate(buf.readableBytes()).order(BIG_ENDIAN);
                 Buffer expectedData = allocator.allocate(12).order(BIG_ENDIAN)) {
                expectedData.writeInt(0x05060708);
                expectedData.writeInt(0x11121314);
                expectedData.writeInt(0x15161718);

                buf.forEachReadable(0, (i, component) -> {
                    ByteCursor forward = component.openCursor();
                    while (forward.readLong()) {
                        actualData.writeLong(forward.getLong());
                    }
                    while (forward.readByte()) {
                        actualData.writeByte(forward.getByte());
                    }
                    return true;
                });

                assertEquals(expectedData.readableBytes(), actualData.readableBytes());
                while (expectedData.readableBytes() > 0) {
                    assertEquals(expectedData.readByte(), actualData.readByte());
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("nonCompositeAllocators")
    public void forEachWritableMustVisitBuffer(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer bufBERW = allocator.allocate(8).order(BIG_ENDIAN);
             Buffer bufLERW = allocator.allocate(8).order(LITTLE_ENDIAN)) {
            verifyForEachWritableSingleComponent(fixture, bufBERW);
            verifyForEachWritableSingleComponent(fixture, bufLERW);
        }
    }

    private static void verifyForEachWritableSingleComponent(Fixture fixture, Buffer buf) {
        buf.forEachWritable(0, (index, component) -> {
            var buffer = component.writableBuffer();
            assertThat(buffer.position()).isZero();
            assertThat(buffer.limit()).isEqualTo(8);
            assertThat(buffer.capacity()).isEqualTo(8);
            buffer.putLong(0x0102030405060708L);
            buffer.flip();
            assertEquals(0x0102030405060708L, buffer.getLong());
            buf.writerOffset(8);
            assertEquals(0x0102030405060708L, buf.getLong(0));

            if (fixture.isDirect()) {
                assertThat(component.writableNativeAddress()).isNotZero();
            } else {
                assertThat(component.writableNativeAddress()).isZero();
            }

            buf.writerOffset(0);
            if (component.hasWritableArray()) {
                byte[] array = component.writableArray();
                int offset = component.writableArrayOffset();
                byte[] arrayCopy = new byte[component.writableArrayLength()];
                System.arraycopy(array, offset, arrayCopy, 0, arrayCopy.length);
                if (buffer.order() == BIG_ENDIAN) {
                    assertThat(arrayCopy).containsExactly(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08);
                } else {
                    assertThat(arrayCopy).containsExactly(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01);
                }
            }

            buffer.put(0, (byte) 0xFF);
            assertEquals((byte) 0xFF, buffer.get(0));
            assertEquals((byte) 0xFF, buf.getByte(0));
            return true;
        });
    }

    @Test
    public void forEachWritableMustVisitAllWritableConstituentBuffersInOrder() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer buf;
            try (Buffer a = allocator.allocate(8);
                 Buffer b = allocator.allocate(8);
                 Buffer c = allocator.allocate(8)) {
                buf = Buffer.compose(allocator, a, b, c);
            }
            buf.order(BIG_ENDIAN);
            buf.forEachWritable(0, (index, component) -> {
                component.writableBuffer().putLong(0x0102030405060708L + 0x1010101010101010L * index);
                return true;
            });
            buf.writerOffset(3 * 8);
            assertEquals(0x0102030405060708L, buf.readLong());
            assertEquals(0x1112131415161718L, buf.readLong());
            assertEquals(0x2122232425262728L, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void forEachWritableMustReturnNegativeCountWhenProcessorReturnsFalse(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int count = buf.forEachWritable(0, (index, component) -> false);
            assertEquals(-1, count);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void forEachWritableMustStopIterationWhenProcessorRetursFalse(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            AtomicInteger counter = new AtomicInteger();
            buf.forEachWritable(0, (index, component) -> {
                counter.incrementAndGet();
                return false;
            });
            assertEquals(1, counter.get());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void forEachWritableChangesMadeToByteBufferComponentMustBeReflectedInBuffer(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(9).order(BIG_ENDIAN)) {
            buf.writeByte((byte) 0xFF);
            AtomicInteger writtenCounter = new AtomicInteger();
            buf.forEachWritable(0, (index, component) -> {
                var buffer = component.writableBuffer();
                while (buffer.hasRemaining()) {
                    buffer.put((byte) writtenCounter.incrementAndGet());
                }
                return true;
            });
            buf.writerOffset(9);
            assertEquals((byte) 0xFF, buf.readByte());
            assertEquals(0x0102030405060708L, buf.readLong());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void changesMadeToByteBufferComponentsShouldBeReflectedInBuffer(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            AtomicInteger counter = new AtomicInteger();
            buf.forEachWritable(0, (index, component) -> {
                var buffer = component.writableBuffer();
                while (buffer.hasRemaining()) {
                    buffer.put((byte) counter.incrementAndGet());
                }
                return true;
            });
            buf.writerOffset(buf.capacity());
            for (int i = 0; i < 8; i++) {
                assertEquals((byte) i + 1, buf.getByte(i));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void forEachWritableOnClosedBufferMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            Buffer buf = allocator.allocate(8);
            buf.close();
            assertThrows(IllegalStateException.class, () -> buf.forEachWritable(0, (index, component) -> true));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void forEachWritableOnReadOnlyBufferMustThrow(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8).readOnly(true)) {
            assertThrows(IllegalStateException.class, () -> buf.forEachWritable(0, (index, component) -> true));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void forEachWritableMustAllowCollectingBuffersInArray(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            ByteBuffer[] buffers = new ByteBuffer[buf.countWritableComponents()];
            buf.forEachWritable(0, (index, component) -> {
                buffers[index] = component.writableBuffer();
                return true;
            });
            assertThat(buffers.length).isGreaterThanOrEqualTo(1);
            int i = 1;
            for (ByteBuffer buffer : buffers) {
                while (buffer.hasRemaining()) {
                    buffer.put((byte) i++);
                }
            }
            buf.writerOffset(buf.capacity());
            i = 1;
            while (buf.readableBytes() > 0) {
                assertEquals((byte) i++, buf.readByte());
            }
        }
    }

    // <editor-fold defaultstate="collapsed" desc="Primitive accessors tests.">
    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfByteMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getByte(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteReadOnlyMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getByte(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            byte value = 0x01;
            buf.writeByte(value);
            assertEquals(value, buf.getByte(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            byte value = 0x01;
            buf.writeByte(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x10, buf.getByte(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            byte value = 0x01;
            buf.writeByte(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getByte(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            byte value = 0x01;
            buf.writeByte(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getByte(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.getByte(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getByte(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteReadOnlyMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true).getByte(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfByteReadOnlyMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getByte(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedByteMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
    void relativeReadOfUnsignedByteReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x01;
            buf.writeUnsignedByte(value);
            buf.readerOffset(1);
            assertEquals(0, buf.readableBytes());
            assertEquals(7, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).readUnsignedByte());
            assertEquals(0, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedByte(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteReadOnlyMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getUnsignedByte(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x01;
            buf.writeUnsignedByte(value);
            assertEquals(value, buf.getUnsignedByte(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            int value = 0x01;
            buf.writeUnsignedByte(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x10, buf.getUnsignedByte(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x01;
            buf.writeUnsignedByte(value);
            buf.getUnsignedByte(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedByte(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteReadOnlyMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(
            Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x01;
            buf.writeUnsignedByte(value);
            buf.readOnly(true).getUnsignedByte(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(
            Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getUnsignedByte(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.getUnsignedByte(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedByte(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteReadOnlyMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true).getUnsignedByte(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedByteReadOnlyMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getUnsignedByte(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfByteMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
    void relativeReadOfCharReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            char value = 0x0102;
            buf.writeChar(value);
            buf.readerOffset(1);
            assertEquals(1, buf.readableBytes());
            assertEquals(6, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).readChar());
            assertEquals(1, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getChar(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharReadOnlyMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getChar(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            char value = 0x0102;
            buf.writeChar(value);
            assertEquals(value, buf.getChar(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            char value = 0x0102;
            buf.writeChar(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x1002, buf.getChar(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            char value = 0x0102;
            buf.writeChar(value);
            buf.getChar(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getChar(7));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharReadOnlyMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            char value = 0x0102;
            buf.writeChar(value);
            buf.readOnly(true).getChar(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getChar(7));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.getChar(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getChar(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharReadOnlyMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true).getChar(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfCharReadOnlyMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getChar(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfCharMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
    void relativeReadOfShortReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            short value = 0x0102;
            buf.writeShort(value);
            buf.readerOffset(1);
            assertEquals(1, buf.readableBytes());
            assertEquals(6, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).readShort());
            assertEquals(1, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getShort(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortReadOnlyMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getShort(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            short value = 0x0102;
            buf.writeShort(value);
            assertEquals(value, buf.getShort(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            short value = 0x0102;
            buf.writeShort(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x1002, buf.getShort(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            short value = 0x0102;
            buf.writeShort(value);
            buf.getShort(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getShort(7));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortReadOnlyMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            short value = 0x0102;
            buf.writeShort(value);
            buf.readOnly(true).getShort(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getShort(7));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.getShort(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getShort(7));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortReadOnlyMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true).getShort(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfShortReadOnlyMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getShort(7));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedShortMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
    void relativeReadOfUnsignedShortReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            buf.readerOffset(1);
            assertEquals(1, buf.readableBytes());
            assertEquals(6, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).readUnsignedShort());
            assertEquals(1, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedShort(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortReadOnlyMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getUnsignedShort(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            assertEquals(value, buf.getUnsignedShort(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x1002, buf.getUnsignedShort(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            buf.getUnsignedShort(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedShort(7));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortReadOnlyMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(
            Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x0102;
            buf.writeUnsignedShort(value);
            buf.readOnly(true).getUnsignedShort(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(
            Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getUnsignedShort(7));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.getUnsignedShort(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedShort(7));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortReadOnlyMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true).getUnsignedShort(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedShortReadOnlyMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getUnsignedShort(7));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfShortMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
    void relativeReadOfMediumReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x010203;
            buf.writeMedium(value);
            buf.readerOffset(1);
            assertEquals(2, buf.readableBytes());
            assertEquals(5, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).readMedium());
            assertEquals(2, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getMedium(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumReadOnlyMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getMedium(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x010203;
            buf.writeMedium(value);
            assertEquals(value, buf.getMedium(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            int value = 0x010203;
            buf.writeMedium(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x100203, buf.getMedium(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x010203;
            buf.writeMedium(value);
            buf.getMedium(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x010203;
            buf.writeMedium(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getMedium(6));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumReadOnlyMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x010203;
            buf.writeMedium(value);
            buf.readOnly(true).getMedium(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getMedium(6));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.getMedium(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getMedium(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumReadOnlyMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true).getMedium(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfMediumReadOnlyMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getMedium(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
    void relativeReadOfUnsignedMediumReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            buf.readerOffset(1);
            assertEquals(2, buf.readableBytes());
            assertEquals(5, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).readUnsignedMedium());
            assertEquals(2, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedMedium(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumReadOnlyMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getUnsignedMedium(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            assertEquals(value, buf.getUnsignedMedium(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x100203, buf.getUnsignedMedium(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            buf.getUnsignedMedium(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedMedium(6));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumReadOnlyMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(
            Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x010203;
            buf.writeUnsignedMedium(value);
            buf.readOnly(true).getUnsignedMedium(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(
            Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getUnsignedMedium(6));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.getUnsignedMedium(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedMedium(6));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumReadOnlyMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true).getUnsignedMedium(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedMediumReadOnlyMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getUnsignedMedium(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfMediumMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
    void relativeReadOfIntReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            int value = 0x01020304;
            buf.writeInt(value);
            buf.readerOffset(1);
            assertEquals(3, buf.readableBytes());
            assertEquals(4, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).readInt());
            assertEquals(3, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getInt(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntReadOnlyMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getInt(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x01020304;
            buf.writeInt(value);
            assertEquals(value, buf.getInt(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            int value = 0x01020304;
            buf.writeInt(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x10020304, buf.getInt(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x01020304;
            buf.writeInt(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getInt(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            int value = 0x01020304;
            buf.writeInt(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getInt(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.getInt(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getInt(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntReadOnlyMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true).getInt(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfIntReadOnlyMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getInt(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeReadOfUnsignedIntMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
    void relativeReadOfUnsignedIntReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            buf.readerOffset(1);
            assertEquals(3, buf.readableBytes());
            assertEquals(4, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).readUnsignedInt());
            assertEquals(3, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedInt(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntReadOnlyMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getUnsignedInt(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            assertEquals(value, buf.getUnsignedInt(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x10020304, buf.getUnsignedInt(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            buf.getUnsignedInt(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedInt(5));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntReadOnlyMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(
            Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            long value = 0x01020304;
            buf.writeUnsignedInt(value);
            buf.readOnly(true).getUnsignedInt(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(
            Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getUnsignedInt(5));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.getUnsignedInt(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getUnsignedInt(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntReadOnlyMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true).getUnsignedInt(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfUnsignedIntReadOnlyMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getUnsignedInt(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfIntMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
    void relativeReadOfFloatReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            buf.readerOffset(1);
            assertEquals(3, buf.readableBytes());
            assertEquals(4, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).readFloat());
            assertEquals(3, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getFloat(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatReadOnlyMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getFloat(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            assertEquals(value, buf.getFloat(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(Float.intBitsToFloat(0x10020304), buf.getFloat(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            buf.getFloat(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getFloat(7));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatReadOnlyMustNotBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            float value = Float.intBitsToFloat(0x01020304);
            buf.writeFloat(value);
            buf.readOnly(true).getFloat(1);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getFloat(5));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.getFloat(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getFloat(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatReadOnlyMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true).getFloat(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfFloatReadOnlyMustBoundsCheckWhenReadOffsetIsGreaterThan(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getFloat(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfFloatMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
    void relativeReadOfLongReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            buf.readerOffset(1);
            assertEquals(7, buf.readableBytes());
            assertEquals(0, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).readLong());
            assertEquals(7, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getLong(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongReadOnlyMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getLong(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            assertEquals(value, buf.getLong(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(0x1002030405060708L, buf.getLong(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getLong(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            long value = 0x0102030405060708L;
            buf.writeLong(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getLong(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.getLong(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getLong(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongReadOnlyMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true).getLong(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfLongReadOnlyMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getLong(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfLongMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
    void relativeReadOfDoubleReadOnllyMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertEquals(0, buf.readableBytes());
            assertEquals(Long.BYTES, buf.writableBytes());
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            buf.readerOffset(1);
            assertEquals(7, buf.readableBytes());
            assertEquals(0, buf.writableBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).readDouble());
            assertEquals(7, buf.readableBytes());
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getDouble(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleReadOnlyMustBoundsCheckOnNegativeOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getDouble(-1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            assertEquals(value, buf.getDouble(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleMustReadWithDefaultEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            buf.setByte(0, (byte) 0x10);
            assertEquals(Double.longBitsToDouble(0x1002030405060708L), buf.getDouble(0));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getDouble(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleReadOnlyMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            double value = Double.longBitsToDouble(0x0102030405060708L);
            buf.writeDouble(value);
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getDouble(1));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.getDouble(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.getDouble(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleReadOnlyMustNotBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.readOnly(true).getDouble(0);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void offsettedGetOfDoubleReadOnlyMustBoundsCheckWhenReadOffsetIsGreaterThanCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> buf.readOnly(true).getDouble(8));
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    void relativeWriteOfDoubleMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
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
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8)) {
            buf.order(BIG_ENDIAN);
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

    private static byte[] toByteArray(Buffer buf) {
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
