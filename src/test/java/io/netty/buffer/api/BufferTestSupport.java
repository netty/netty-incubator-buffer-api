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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
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

public abstract class BufferTestSupport {
    public static ExecutorService executor;

    private static final Memoize<Fixture[]> ALL_COMBINATIONS = new Memoize<>(
            () -> fixtureCombinations().toArray(Fixture[]::new));
    private static final Memoize<Fixture[]> ALL_ALLOCATORS = new Memoize<>(
            () -> Arrays.stream(ALL_COMBINATIONS.get())
                    .filter(sample())
                    .toArray(Fixture[]::new));
    private static final Memoize<Fixture[]> NON_COMPOSITE = new Memoize<>(
            () -> Arrays.stream(ALL_COMBINATIONS.get())
                    .filter(f -> !f.isComposite())
                    .filter(sample())
                    .toArray(Fixture[]::new));
    private static final Memoize<Fixture[]> HEAP_ALLOCS = new Memoize<>(
            () -> Arrays.stream(ALL_COMBINATIONS.get())
                    .filter(f -> f.isHeap())
                    .filter(sample())
                    .toArray(Fixture[]::new));
    private static final Memoize<Fixture[]> DIRECT_ALLOCS = new Memoize<>(
            () -> Arrays.stream(ALL_COMBINATIONS.get())
                    .filter(f -> f.isDirect())
                    .filter(sample())
                    .toArray(Fixture[]::new));
    private static final Memoize<Fixture[]> POOLED_ALLOCS = new Memoize<>(
            () -> Arrays.stream(ALL_COMBINATIONS.get())
                    .filter(f -> f.isPooled())
                    .filter(sample())
                    .toArray(Fixture[]::new));
    private static final Memoize<Fixture[]> POOLED_DIRECT_ALLOCS = new Memoize<>(
            () -> Arrays.stream(ALL_COMBINATIONS.get())
                    .filter(f -> f.isPooled() && f.isDirect())
                    .filter(sample())
                    .toArray(Fixture[]::new));

    private static Predicate<Fixture> sample() {
        String sampleSetting = System.getProperty("sample");
        if ("nosample".equalsIgnoreCase(sampleSetting)) {
            return fixture -> true;
        }
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        SplittableRandom rng = new SplittableRandom(today.hashCode());
        AtomicInteger counter = new AtomicInteger();
        return fixture -> {
            boolean res = counter.getAndIncrement() < 1 || rng.nextInt(0, 100) <= 2;
            return res;
        }; // Filter out 97% of tests.
    }

    static Fixture[] allocators() {
        return ALL_ALLOCATORS.get();
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
        return stream.flatMap(BufferTestSupport::injectBifurcations)
                     .flatMap(BufferTestSupport::injectSlices)
                     .flatMap(BufferTestSupport::injectReadOnlyToggling);
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
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = Executors.defaultThreadFactory().newThread(r);
                thread.setName("BufferTest-" + thread.getName());
                thread.setDaemon(true); // Do not prevent shut down of test runner.
                return thread;
            }
        });
    }

    @AfterAll
    static void stopExecutor() throws IOException {
        executor.shutdown();
    }

    public static void verifyInaccessible(Buffer buf) {
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

    public static void verifyReadInaccessible(Buffer buf) {
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

    public static void verifyWriteInaccessible(Buffer buf) {
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

    public static void testCopyIntoByteBuffer(Fixture fixture, Function<Integer, ByteBuffer> bbAlloc) {
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

    public static void testCopyIntoBuf(Fixture fixture, Function<Integer, Buffer> bbAlloc) {
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

    public static void checkByteIteration(Buffer buf) {
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

    public static void checkByteIterationOfRegion(Buffer buf) {
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

    public static void checkReverseByteIteration(Buffer buf) {
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

    public static void checkReverseByteIterationOfRegion(Buffer buf) {
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

    public static void verifyBifurcateEmptyCompositeBuffer(Buffer buf) {
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

    public static void verifyWriteAccessible(Buffer buf) {
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

    public static void verifyForEachReadableSingleComponent(Fixture fixture, Buffer buf) {
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

    public static void verifyForEachWritableSingleComponent(Fixture fixture, Buffer buf) {
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

    public static byte[] toByteArray(Buffer buf) {
        byte[] bs = new byte[buf.capacity()];
        buf.copyInto(0, bs, 0, bs.length);
        return bs;
    }

    public static void assertEquals(byte expected, byte actual) {
        if (expected != actual) {
            fail(String.format("expected: %1$s (0x%1$X) but was: %2$s (0x%2$X)", expected, actual));
        }
    }

    public static void assertEquals(char expected, char actual) {
        if (expected != actual) {
            fail(String.format("expected: %s (0x%X) but was: %s (0x%X)",
                               expected, (int) expected, actual, (int) actual));
        }
    }

    public static void assertEquals(short expected, short actual) {
        if (expected != actual) {
            fail(String.format("expected: %1$s (0x%1$X) but was: %2$s (0x%2$X)", expected, actual));
        }
    }

    public static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            fail(String.format("expected: %1$s (0x%1$X) but was: %2$s (0x%2$X)", expected, actual));
        }
    }

    public static void assertEquals(long expected, long actual) {
        if (expected != actual) {
            fail(String.format("expected: %1$s (0x%1$X) but was: %2$s (0x%2$X)", expected, actual));
        }
    }

    public static void assertEquals(float expected, float actual) {
        //noinspection FloatingPointEquality
        if (expected != actual) {
            fail(String.format("expected: %s (0x%X) but was: %s (0x%X)",
                               expected, Float.floatToRawIntBits(expected),
                               actual, Float.floatToRawIntBits(actual)));
        }
    }

    public static void assertEquals(double expected, double actual) {
        //noinspection FloatingPointEquality
        if (expected != actual) {
            fail(String.format("expected: %s (0x%X) but was: %s (0x%X)",
                               expected, Double.doubleToRawLongBits(expected),
                               actual, Double.doubleToRawLongBits(actual)));
        }
    }
}
