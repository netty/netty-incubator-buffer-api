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
package io.netty.buffer.api.examples.bytetomessagedecoder;

import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.BufferAllocator;
import io.netty.buffer.api.adaptor.BufferAdaptor;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import org.junit.Test;

import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.netty.buffer.api.BufferAllocator.heap;
import static io.netty.buffer.api.BufferTestSupport.assertEquals;
import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ByteToMessageDecoderTest {

    @Test
    public void testRemoveItself() {
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            private boolean removed;

            @Override
            protected void decode(ChannelHandlerContext ctx, Buffer in) {
                assertFalse(removed);
                in.readByte();
                ctx.pipeline().remove(this);
                removed = true;
            }
        });

        try (Buffer buf = heap().allocate(4).writeInt(0x01020304)) {
            channel.writeInbound(buf.slice());
            try (Buffer b = channel.readInbound()) {
                buf.readByte();
                assertEquals(b, buf);
            }
        }
    }

    @Test
    public void testRemoveItselfWriteBuffer() {
        final Buffer buf = heap().allocate(5, BIG_ENDIAN).writeInt(0x01020304);
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            private boolean removed;

            @Override
            protected void decode(ChannelHandlerContext ctx, Buffer in) {
                assertFalse(removed);
                in.readByte();
                ctx.pipeline().remove(this);

                // This should not let it keep call decode
                buf.writeByte((byte) 0x05);
                removed = true;
            }
        });

        channel.writeInbound(buf.slice());
        try (Buffer expected = heap().allocate(3, BIG_ENDIAN).writeShort((short) 0x0203).writeByte((byte) 0x04);
             Buffer b = channel.readInbound();
             Buffer actual = b.slice(); // Only compare readable bytes.
             buf) {
            assertEquals(expected, actual);
        }
    }

    /**
     * Verifies that internal buffer of the ByteToMessageDecoder is released once decoder is removed from pipeline. In
     * this case input is read fully.
     */
    @Test
    public void testInternalBufferClearReadAll() {
        Buffer buf = heap().allocate(1).writeByte((byte) 'a');
        EmbeddedChannel channel = newInternalBufferTestChannel();
        assertFalse(channel.writeInbound(buf));
        assertFalse(channel.finish());
    }

    /**
     * Verifies that internal buffer of the ByteToMessageDecoder is released once decoder is removed from pipeline. In
     * this case input was not fully read.
     */
    @Test
    public void testInternalBufferClearReadPartly() {
        final Buffer buf = heap().allocate(2, BIG_ENDIAN).writeShort((short) 0x0102);
        EmbeddedChannel channel = newInternalBufferTestChannel();
        assertTrue(channel.writeInbound(buf));
        assertTrue(channel.finish());
        try (Buffer expected = heap().allocate(1).writeByte((byte) 0x02);
             Buffer b = channel.readInbound();
             Buffer actual = b.slice()) {
            assertEquals(expected, actual);
            assertNull(channel.readInbound());
        }
    }

    private EmbeddedChannel newInternalBufferTestChannel() {
        return new EmbeddedChannel(new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, Buffer in) {
                Buffer buf = internalBuffer();
                assertTrue(buf.isOwned());
                in.readByte();
                // Removal from pipeline should clear internal buffer
                ctx.pipeline().remove(this);
            }

            @Override
            protected void handlerRemoved0(ChannelHandlerContext ctx) {
                assertCumulationReleased(internalBuffer());
            }
        });
    }

    @Test
    public void handlerRemovedWillNotReleaseBufferIfDecodeInProgress() {
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, Buffer in) throws Exception {
                ctx.pipeline().remove(this);
                assertTrue(in.isAccessible());
            }

            @Override
            protected void handlerRemoved0(ChannelHandlerContext ctx) {
                assertCumulationReleased(internalBuffer());
            }
        });
        byte[] bytes = new byte[1024];
        ThreadLocalRandom.current().nextBytes(bytes);

        Buffer buffer = heap().allocate(bytes.length);
        for (byte b : bytes) {
            buffer.writeByte(b);
        }
        assertTrue(channel.writeInbound(buffer));
        assertTrue(channel.finishAndReleaseAll());
    }

    private static void assertCumulationReleased(Buffer buffer) {
        assertTrue("unexpected value: " + buffer,
                buffer == null || buffer.capacity() == 0 || !buffer.isAccessible());
    }

    @Test
    public void testFireChannelReadCompleteOnInactive() throws InterruptedException {
        final BlockingQueue<Integer> queue = new LinkedBlockingDeque<>();
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, Buffer in) {
                int readable = in.readableBytes();
                assertTrue(readable > 0);
                in.readerOffset(in.readerOffset() + readable);
            }

            @Override
            protected void decodeLast(ChannelHandlerContext ctx, Buffer in) {
                assertEquals(0, in.readableBytes());
                ctx.fireChannelRead("data");
            }
        }, new ChannelHandler() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                queue.add(3);
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                queue.add(1);
            }

            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                if (!ctx.channel().isActive()) {
                    queue.add(2);
                }
            }
        });
        Buffer buf = heap().allocate(2, BIG_ENDIAN).writeShort((short) 0x0102);
        assertFalse(channel.writeInbound(buf));
        channel.finish();
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
        assertTrue(queue.isEmpty());
        assertFalse(buf.isAccessible());
    }

    // See https://github.com/netty/netty/issues/4635
    @Test
    public void testRemoveWhileInCallDecode() {
        final Object upgradeMessage = new Object();
        final ByteToMessageDecoder decoder = new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, Buffer in) {
                assertEquals(1, in.readByte());
                ctx.fireChannelRead(upgradeMessage);
            }
        };

        EmbeddedChannel channel = new EmbeddedChannel(decoder, new ChannelHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (msg == upgradeMessage) {
                    ctx.pipeline().remove(decoder);
                    return;
                }
                ctx.fireChannelRead(msg);
            }
        });

        try (Buffer buf = heap().allocate(4, BIG_ENDIAN).writeInt(0x01020304)) {
            assertTrue(channel.writeInbound(buf.slice()));
            try (Buffer expected = buf.slice(1, 3);
                 Buffer b = channel.readInbound();
                 Buffer actual = b.slice()) {
                assertEquals(expected, actual);
                assertFalse(channel.finish());
            }
        }
    }

    @Test
    public void testDecodeLastEmptyBuffer() {
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, Buffer in) {
                assertTrue(in.readableBytes() > 0);
                Buffer slice = in.slice();
                in.readerOffset(in.readerOffset() + in.readableBytes());
                ctx.fireChannelRead(slice);
            }
        });
        byte[] bytes = new byte[1024];
        ThreadLocalRandom.current().nextBytes(bytes);

        try (Buffer buf = heap().allocate(bytes.length)) {
            for (byte b : bytes) {
                buf.writeByte(b);
            }
            assertTrue(channel.writeInbound(buf.slice()));
            try (Buffer b = channel.readInbound()) {
                assertEquals(buf, b);
                assertNull(channel.readInbound());
                assertFalse(channel.finish());
                assertNull(channel.readInbound());
            }
        }
    }

    @Test
    public void testDecodeLastNonEmptyBuffer() {
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            private boolean decodeLast;

            @Override
            protected void decode(ChannelHandlerContext ctx, Buffer in) {
                int readable = in.readableBytes();
                assertTrue(readable > 0);
                if (!decodeLast && readable == 1) {
                    return;
                }
                int read = decodeLast ? readable : readable - 1;
                Buffer slice = in.slice(in.readerOffset(), read);
                in.readerOffset(in.readerOffset() + read);
                ctx.fireChannelRead(slice);
            }

            @Override
            protected void decodeLast(ChannelHandlerContext ctx, Buffer in) throws Exception {
                assertFalse(decodeLast);
                decodeLast = true;
                super.decodeLast(ctx, in);
            }
        });
        byte[] bytes = new byte[1024];
        ThreadLocalRandom.current().nextBytes(bytes);
        try (Buffer buf = heap().allocate(bytes.length, BIG_ENDIAN);
             Buffer part1 = buf.slice(0, bytes.length - 1);
             Buffer part2 = buf.slice(bytes.length - 1, 1)) {
            for (byte b : bytes) {
                buf.writeByte(b);
            }
            assertTrue(channel.writeInbound(buf.slice()));
            try (Buffer actual = channel.readInbound()) {
                assertEquals(part1, actual);
            }
            assertNull(channel.readInbound());
            assertTrue(channel.finish());
            try (Buffer actual = channel.readInbound()) {
                assertEquals(part2, actual);
            }
            assertNull(channel.readInbound());
        }
    }

    @Test
    public void testReadOnlyBuffer() {
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, Buffer in) { }
        });
        assertFalse(channel.writeInbound(heap().allocate(8).writeByte((byte) 1).readOnly(true)));
        assertFalse(channel.writeInbound(heap().allocate(1).writeByte((byte) 2)));
        assertFalse(channel.finish());
    }

    static class WriteFailingByteBuf extends BufferAdaptor {
        private final Error error = new Error();
        private int untilFailure;

        WriteFailingByteBuf(int untilFailure, int capacity) {
            this(untilFailure, heap().allocate(capacity, BIG_ENDIAN));
            this.untilFailure = untilFailure;
        }

        private WriteFailingByteBuf(int untilFailure, Buffer buffer) {
            super(buffer);
            this.untilFailure = untilFailure;
        }

        @Override
        public Buffer order(ByteOrder order) {
            if (order == LITTLE_ENDIAN && --untilFailure <= 0) {
                throw error;
            }
            return super.order(order);
        }

        @Override
        protected BufferAdaptor receive(Buffer buf) {
            return new WriteFailingByteBuf(untilFailure, buf);
        }

        Error writeError() {
            return error;
        }
    }

    @Test
    public void releaseWhenMergeCumulateThrows() {
        WriteFailingByteBuf oldCumulation = new WriteFailingByteBuf(1, 64);
        oldCumulation.writeByte((byte) 0);
        Buffer in = heap().allocate(12, BIG_ENDIAN).writerOffset(12);

        Throwable thrown = null;
        try {
            ByteToMessageDecoder.MERGE_CUMULATOR.cumulate(heap(), oldCumulation, in);
        } catch (Throwable t) {
            thrown = t;
        }

        assertSame(oldCumulation.writeError(), thrown);
        assertFalse(in.isAccessible());
        assertTrue(oldCumulation.isOwned());
        oldCumulation.close();
    }

    @Test
    public void releaseWhenMergeCumulateThrowsInExpand() {
        releaseWhenMergeCumulateThrowsInExpand(1, true);
        releaseWhenMergeCumulateThrowsInExpand(2, true);
        releaseWhenMergeCumulateThrowsInExpand(3, false); // sentinel test case
    }

    private static void releaseWhenMergeCumulateThrowsInExpand(int untilFailure, boolean shouldFail) {
        Buffer oldCumulation = heap().allocate(8, BIG_ENDIAN).writeByte((byte) 0);
        final WriteFailingByteBuf newCumulation = new WriteFailingByteBuf(untilFailure, 16);

        BufferAllocator allocator = new BufferAllocator() {
            @Override
            public Buffer allocate(int capacity) {
                return newCumulation;
            }
        };

        Buffer in = heap().allocate(12, BIG_ENDIAN).writerOffset(12);
        Throwable thrown = null;
        try {
            ByteToMessageDecoder.MERGE_CUMULATOR.cumulate(allocator, oldCumulation, in);
        } catch (Throwable t) {
            thrown = t;
        }

        assertFalse(in.isAccessible());

        if (shouldFail) {
            assertSame(newCumulation.writeError(), thrown);
            assertTrue(oldCumulation.isOwned());
            oldCumulation.close();
            assertFalse(newCumulation.isAccessible());
        } else {
            assertNull(thrown);
            assertFalse(oldCumulation.isAccessible());
            assertTrue(newCumulation.isOwned());
            newCumulation.close();
        }
    }

    @Test
    public void releaseWhenCompositeCumulateThrows() {
        Buffer in = heap().allocate(12, LITTLE_ENDIAN).writerOffset(12);
        try (Buffer cumulation = Buffer.compose(heap(), heap().allocate(1, BIG_ENDIAN).writeByte((byte) 0).send())) {
            ByteToMessageDecoder.COMPOSITE_CUMULATOR.cumulate(heap(), cumulation, in);
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("byte order");
            assertFalse(in.isAccessible());
        }
    }

    @Test
    public void testDoesNotOverRead() {
        class ReadInterceptingHandler implements ChannelHandler {
            private int readsTriggered;

            @Override
            public void read(ChannelHandlerContext ctx) {
                readsTriggered++;
                ctx.read();
            }
        }
        ReadInterceptingHandler interceptor = new ReadInterceptingHandler();

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAutoRead(false);
        channel.pipeline().addLast(interceptor, new FixedLengthFrameDecoder(3));
        assertEquals(0, interceptor.readsTriggered);

        // 0 complete frames, 1 partial frame: SHOULD trigger a read
        channel.writeInbound(heap().allocate(2, BIG_ENDIAN).writeShort((short) 0x0001));
        assertEquals(1, interceptor.readsTriggered);

        // 2 complete frames, 0 partial frames: should NOT trigger a read
        channel.writeInbound(heap().allocate(1).writeByte((byte) 2),
                heap().allocate(3).writeByte((byte) 3).writeByte((byte) 4).writeByte((byte) 5));
        assertEquals(1, interceptor.readsTriggered);

        // 1 complete frame, 1 partial frame: should NOT trigger a read
        channel.writeInbound(heap().allocate(3).writeByte((byte) 6).writeByte((byte) 7).writeByte((byte) 8),
                heap().allocate(1).writeByte((byte) 9));
        assertEquals(1, interceptor.readsTriggered);

        // 1 complete frame, 1 partial frame: should NOT trigger a read
        channel.writeInbound(heap().allocate(2).writeByte((byte) 10).writeByte((byte) 11),
                heap().allocate(1).writeByte((byte) 12));
        assertEquals(1, interceptor.readsTriggered);

        // 0 complete frames, 1 partial frame: SHOULD trigger a read
        channel.writeInbound(heap().allocate(1).writeByte((byte) 13));
        assertEquals(2, interceptor.readsTriggered);

        // 1 complete frame, 0 partial frames: should NOT trigger a read
        channel.writeInbound(heap().allocate(1).writeByte((byte) 14));
        assertEquals(2, interceptor.readsTriggered);

        for (int i = 0; i < 5; i++) {
            try (Buffer read = channel.readInbound()) {
                assertEquals(i * 3, read.getByte(0));
                assertEquals(i * 3 + 1, read.getByte(1));
                assertEquals(i * 3 + 2, read.getByte(2));
            }
        }
        assertFalse(channel.finish());
    }

    @Test
    public void testDisorder() {
        ByteToMessageDecoder decoder = new ByteToMessageDecoder() {
            int count;

            //read 4 byte then remove this decoder
            @Override
            protected void decode(ChannelHandlerContext ctx, Buffer in) {
                ctx.fireChannelRead(in.readByte());
                if (++count >= 4) {
                    ctx.pipeline().remove(this);
                }
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(decoder);
        byte[] bytes = {1, 2, 3, 4, 5};
        Buffer buf = heap().allocate(bytes.length);
        for (byte b : bytes) {
            buf.writeByte(b);
        }
        assertTrue(channel.writeInbound(buf));
        assertEquals((byte) 1, channel.readInbound());
        assertEquals((byte) 2, channel.readInbound());
        assertEquals((byte) 3, channel.readInbound());
        assertEquals((byte) 4, channel.readInbound());
        Buffer buffer5 = channel.readInbound();
        assertEquals((byte) 5, buffer5.readByte());
        assertEquals(0, buffer5.readableBytes());
        buffer5.close();
        assertFalse(buffer5.isAccessible());
        assertFalse(channel.finish());
    }

    @Test
    public void testDecodeLast() {
        final AtomicBoolean removeHandler = new AtomicBoolean();
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {

            @Override
            protected void decode(ChannelHandlerContext ctx, Buffer in) {
                if (removeHandler.get()) {
                    ctx.pipeline().remove(this);
                }
            }
        });
        byte[] bytes = new byte[1024];
        ThreadLocalRandom.current().nextBytes(bytes);
        try (Buffer buf = heap().allocate(bytes.length)) {
            for (byte b : bytes) {
                buf.writeByte(b);
            }

            assertFalse(channel.writeInbound(buf.slice()));
            assertNull(channel.readInbound());
            removeHandler.set(true);
            // This should trigger channelInputClosed(...)
            channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);

            assertTrue(channel.finish());
            try (Buffer actual = channel.readInbound()) {
                assertEquals(buf.slice(), actual);
            }
            assertNull(channel.readInbound());
        }
    }
}
