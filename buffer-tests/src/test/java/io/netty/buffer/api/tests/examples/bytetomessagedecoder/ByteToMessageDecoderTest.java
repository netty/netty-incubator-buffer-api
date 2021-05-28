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
package io.netty.buffer.api.tests.examples.bytetomessagedecoder;

import io.netty.buffer.api.BufferAllocator;
import io.netty.buffer.api.Buffer;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import org.junit.Test;
import org.mockito.stubbing.Answer;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.buffer.api.tests.BufferTestSupport.assertEquals;
import static io.netty.buffer.api.CompositeBuffer.compose;
import static io.netty.buffer.api.tests.BufferTestSupport.assertReadableEquals;
import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

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

        channel.writeInbound(BufferAllocator.heap().allocate(4, BIG_ENDIAN).writeInt(0x01020304));
        try (Buffer b = channel.readInbound()) {
            assertEquals(3, b.readableBytes());
            assertEquals(0x02, b.readByte());
            assertEquals(0x03, b.readByte());
            assertEquals(0x04, b.readByte());
        }
    }

    @Test
    public void testRemoveItselfWriteBuffer() {
        try (Buffer buf = BufferAllocator.heap().allocate(5, BIG_ENDIAN).writeInt(0x01020304)) {
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

            channel.writeInbound(buf.copy());
            try (Buffer expected = BufferAllocator.heap().allocate(3, BIG_ENDIAN).writeShort((short) 0x0203).writeByte((byte) 0x04);
                 Buffer actual = channel.readInbound()) {
                assertReadableEquals(expected, actual);
            }
        }
    }

    @Test
    public void testRemoveItselfWriteBuffer2() {
        Buffer buf = BufferAllocator.heap().allocate(5, BIG_ENDIAN).writeInt(0x01020304);
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

        channel.writeInbound(buf);
        try (Buffer expected = BufferAllocator.heap().allocate(4, BIG_ENDIAN).writeInt(0x02030405);
             Buffer actual = channel.readInbound()) {
            assertReadableEquals(expected, actual);
        }
    }

    /**
     * Verifies that internal buffer of the ByteToMessageDecoder is released once decoder is removed from pipeline. In
     * this case input is read fully.
     */
    @Test
    public void testInternalBufferClearReadAll() {
        Buffer buf = BufferAllocator.heap().allocate(1).writeByte((byte) 'a');
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
        final Buffer buf = BufferAllocator.heap().allocate(2, BIG_ENDIAN).writeShort((short) 0x0102);
        EmbeddedChannel channel = newInternalBufferTestChannel();
        assertTrue(channel.writeInbound(buf));
        assertTrue(channel.finish());
        try (Buffer expected = BufferAllocator.heap().allocate(1).writeByte((byte) 0x02);
             Buffer actual = channel.readInbound()) {
            assertReadableEquals(expected, actual);
            assertNull(channel.readInbound());
        }
    }

    private EmbeddedChannel newInternalBufferTestChannel() {
        return new EmbeddedChannel(new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, Buffer in) {
                Buffer buf = internalBuffer();
                buf.ensureWritable(8, 8, false); // Verify we have full access to the buffer.
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

        Buffer buffer = BufferAllocator.heap().allocate(bytes.length);
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
        Buffer buf = BufferAllocator.heap().allocate(2, BIG_ENDIAN).writeShort((short) 0x0102);
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

        try (Buffer buf = BufferAllocator.heap().allocate(4, BIG_ENDIAN).writeInt(0x01020304)) {
            assertTrue(channel.writeInbound(buf.copy()));
            try (Buffer expected = buf.copy(1, 3);
                 Buffer actual = channel.readInbound()) {
                assertReadableEquals(expected, actual);
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
                Buffer chunk = in.split();
                ctx.fireChannelRead(chunk);
            }
        });
        byte[] bytes = new byte[1024];
        ThreadLocalRandom.current().nextBytes(bytes);

        try (Buffer buf = BufferAllocator.heap().allocate(bytes.length)) {
            for (byte b : bytes) {
                buf.writeByte(b);
            }
            assertTrue(channel.writeInbound(buf.copy()));
            try (Buffer b = channel.readInbound()) {
                assertReadableEquals(buf, b);
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
                Buffer chunk = in.split(in.readerOffset() + read);
                ctx.fireChannelRead(chunk);
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
        Buffer buf = BufferAllocator.heap().allocate(bytes.length, BIG_ENDIAN).writeBytes(bytes);
        try (Buffer part1 = buf.copy(0, bytes.length - 1);
             Buffer part2 = buf.copy(bytes.length - 1, 1)) {
            assertTrue(channel.writeInbound(buf));
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
        assertFalse(channel.writeInbound(BufferAllocator.heap().allocate(8).writeByte((byte) 1).makeReadOnly()));
        assertFalse(channel.writeInbound(BufferAllocator.heap().allocate(1).writeByte((byte) 2)));
        assertFalse(channel.finish());
    }

    @Test
    public void releaseWhenMergeCumulateThrows() {
        Buffer oldCumulation = writeFailingCumulation(1, 64);
        oldCumulation.writeByte((byte) 0);
        Buffer in = BufferAllocator.heap().allocate(12, BIG_ENDIAN).writerOffset(12);

        Throwable thrown = null;
        try {
            ByteToMessageDecoder.MERGE_CUMULATOR.cumulate(BufferAllocator.heap(), oldCumulation, in);
        } catch (Throwable t) {
            thrown = t;
        }

        assertThat(thrown).hasMessage("boom");
        assertFalse(in.isAccessible());
        oldCumulation.ensureWritable(8, 8, false); // Will throw if we don't have full access to the buffer.
        oldCumulation.close();
    }

    private static Buffer writeFailingCumulation(int untilFailure, int capacity) {
        Buffer realBuffer = BufferAllocator.heap().allocate(capacity, BIG_ENDIAN);
        Answer<Object> callRealBuffer = inv -> {
            Object result = inv.getMethod().invoke(realBuffer, inv.getArguments());
            if (result == realBuffer) {
                // Preserve mock wrapper for methods that returns the callee ('this') buffer instance.
                return inv.getMock();
            }
            return result;
        };
        Buffer buffer = mock(Buffer.class, withSettings().defaultAnswer(callRealBuffer));
        AtomicInteger countDown = new AtomicInteger(untilFailure);
        doAnswer(inv -> {
            if (countDown.decrementAndGet() <= 0) {
                throw new Error("boom");
            }
            return callRealBuffer.answer(inv);
        }).when(buffer).writeBytes(any(Buffer.class));
        return buffer;
    }

    @Test
    public void releaseWhenMergeCumulateThrowsInExpand() {
        releaseWhenMergeCumulateThrowsInExpand(1, true);
        releaseWhenMergeCumulateThrowsInExpand(2, true);
        releaseWhenMergeCumulateThrowsInExpand(3, false); // sentinel test case
    }

    private static void releaseWhenMergeCumulateThrowsInExpand(int untilFailure, boolean shouldFail) {
        Buffer oldCumulation = BufferAllocator.heap().allocate(8, BIG_ENDIAN).writeByte((byte) 0);
        Buffer newCumulation = writeFailingCumulation(untilFailure, 16);

        BufferAllocator allocator = new BufferAllocator() {
            @Override
            public Buffer allocate(int capacity) {
                return newCumulation;
            }
        };

        Buffer in = BufferAllocator.heap().allocate(12, BIG_ENDIAN).writerOffset(12);
        Throwable thrown = null;
        try {
            ByteToMessageDecoder.MERGE_CUMULATOR.cumulate(allocator, oldCumulation, in);
        } catch (Throwable t) {
            thrown = t;
        }

        assertFalse(in.isAccessible());

        if (shouldFail) {
            assertThat(thrown).hasMessage("boom");
            oldCumulation.ensureWritable(8, 8, false); // Will throw if we don't have full access to the buffer.
            oldCumulation.close();
            assertFalse(newCumulation.isAccessible());
        } else {
            assertNull(thrown);
            assertFalse(oldCumulation.isAccessible());
            newCumulation.ensureWritable(8, 8, false); // Will throw if we don't have full access to the buffer.
            newCumulation.close();
        }
    }

    @Test
    public void releaseWhenCompositeCumulateThrows() {
        Buffer in = BufferAllocator.heap().allocate(12, LITTLE_ENDIAN).writerOffset(12);
        try (Buffer cumulation = compose(BufferAllocator.heap(), BufferAllocator.heap().allocate(1, BIG_ENDIAN).writeByte((byte) 0).send())) {
            ByteToMessageDecoder.COMPOSITE_CUMULATOR.cumulate(BufferAllocator.heap(), cumulation, in);
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
        channel.writeInbound(BufferAllocator.heap().allocate(2, BIG_ENDIAN).writeShort((short) 0x0001));
        assertEquals(1, interceptor.readsTriggered);

        // 2 complete frames, 0 partial frames: should NOT trigger a read
        channel.writeInbound(BufferAllocator.heap().allocate(1).writeByte((byte) 2),
                BufferAllocator.heap().allocate(3).writeByte((byte) 3).writeByte((byte) 4).writeByte((byte) 5));
        assertEquals(1, interceptor.readsTriggered);

        // 1 complete frame, 1 partial frame: should NOT trigger a read
        channel.writeInbound(BufferAllocator.heap().allocate(3).writeByte((byte) 6).writeByte((byte) 7).writeByte((byte) 8),
                BufferAllocator.heap().allocate(1).writeByte((byte) 9));
        assertEquals(1, interceptor.readsTriggered);

        // 1 complete frame, 1 partial frame: should NOT trigger a read
        channel.writeInbound(BufferAllocator.heap().allocate(2).writeByte((byte) 10).writeByte((byte) 11),
                BufferAllocator.heap().allocate(1).writeByte((byte) 12));
        assertEquals(1, interceptor.readsTriggered);

        // 0 complete frames, 1 partial frame: SHOULD trigger a read
        channel.writeInbound(BufferAllocator.heap().allocate(1).writeByte((byte) 13));
        assertEquals(2, interceptor.readsTriggered);

        // 1 complete frame, 0 partial frames: should NOT trigger a read
        channel.writeInbound(BufferAllocator.heap().allocate(1).writeByte((byte) 14));
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
        Buffer buf = BufferAllocator.heap().allocate(bytes.length);
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
        try (Buffer buf = BufferAllocator.heap().allocate(bytes.length).writeBytes(bytes)) {
            assertFalse(channel.writeInbound(buf.copy()));
            assertNull(channel.readInbound());
            removeHandler.set(true);
            // This should trigger channelInputClosed(...)
            channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);

            assertTrue(channel.finish());
            try (Buffer actual = channel.readInbound()) {
                assertReadableEquals(buf, actual);
            }
            assertNull(channel.readInbound());
        }
    }
}
