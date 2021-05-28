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

import io.netty.buffer.api.Buffer;
import io.netty.channel.ChannelHandlerContext;

import static io.netty.util.internal.ObjectUtil.checkPositive;

public class FixedLengthFrameDecoder extends ByteToMessageDecoder {
    private final int frameLength;

    /**
     * Creates a new instance.
     *
     * @param frameLength the length of the frame
     */
    public FixedLengthFrameDecoder(int frameLength) {
        checkPositive(frameLength, "frameLength");
        this.frameLength = frameLength;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, Buffer in) throws Exception {
        Object decoded = decode0(ctx, in);
        if (decoded != null) {
            ctx.fireChannelRead(decoded);
        }
    }

    /**
     * Create a frame out of the {@link Buffer} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   in              the {@link Buffer} from which to read data
     * @return  frame           the {@link Buffer} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode0(
            @SuppressWarnings("UnusedParameters") ChannelHandlerContext ctx, Buffer in) throws Exception {
        if (in.readableBytes() < frameLength) {
            return null;
        } else {
            return in.split(in.readerOffset() + frameLength);
        }
    }
}
