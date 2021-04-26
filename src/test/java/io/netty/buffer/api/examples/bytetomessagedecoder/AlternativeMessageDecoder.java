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
import io.netty.buffer.api.Send;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;

import java.util.Objects;

public abstract class AlternativeMessageDecoder extends ChannelHandlerAdapter {
    public static final int DEFAULT_CHUNK_SIZE = 1 << 13; // 8 KiB
    private Buffer collector;
    private BufferAllocator allocator;

    protected AlternativeMessageDecoder() {
        allocator = initAllocator();
        collector = initCollector(allocator, DEFAULT_CHUNK_SIZE);
    }

    protected BufferAllocator initAllocator() {
        return BufferAllocator.heap();
    }

    protected Buffer initCollector(BufferAllocator allocator, int defaultChunkSize) {
        return allocator.allocate(defaultChunkSize);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        drainCollector(ctx);
        collector.close();
        super.handlerRemoved(ctx);
    }

    private void drainCollector(ChannelHandlerContext ctx) {
        boolean madeProgress;
        do {
            madeProgress = decodeAndFireRead(ctx, collector);
        } while (madeProgress);
    }

    protected abstract boolean decodeAndFireRead(ChannelHandlerContext ctx, Buffer input);

    public BufferAllocator getAllocator() {
        return allocator;
    }

    public void setAllocator(BufferAllocator allocator) {
        this.allocator = Objects.requireNonNull(allocator, "BufferAllocator cannot be null.");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Buffer) {
            try (Buffer input = (Buffer) msg) {
                processRead(ctx, input);
            }
        } else if (msg instanceof Send && ((Send<?>) msg).isInstanceOf(Buffer.class)) {
            //noinspection unchecked
            try (Buffer input = ((Send<Buffer>) msg).receive()) {
                processRead(ctx, input);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private void processRead(ChannelHandlerContext ctx, Buffer input) {
        if (collector.isOwned() && Buffer.isComposite(collector) && input.isOwned()
                && (collector.writableBytes() == 0 || input.writerOffset() == 0)
                && (collector.readableBytes() == 0 || input.readerOffset() == 0)
                && collector.order() == input.order()) {
            Buffer.extendComposite(collector, input);
            drainCollector(ctx);
            return;
        }
        if (collector.isOwned()) {
            collector.ensureWritable(input.readableBytes(), DEFAULT_CHUNK_SIZE, true);
        } else {
            try (Buffer prev = collector) {
                int requiredCapacity = input.capacity() + prev.readableBytes();
                collector = allocator.allocate(Math.max(requiredCapacity, DEFAULT_CHUNK_SIZE), input.order());
                collector.writeBytes(prev);
            }
        }
        collector.writeBytes(input);
        drainCollector(ctx);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
        ctx.fireChannelReadComplete();
    }
}
