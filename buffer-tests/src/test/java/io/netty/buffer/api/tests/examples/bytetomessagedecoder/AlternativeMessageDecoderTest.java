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
import io.netty.buffer.api.BufferAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.SplittableRandom;

import static io.netty.buffer.api.tests.BufferTestSupport.readByteArray;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AlternativeMessageDecoderTest {
    @Test
    public void splitAndParseMessagesDownThePipeline() {
        EmbeddedChannel channel = new EmbeddedChannel(new AlternativeMessageDecoder() {
            @Override
            protected boolean decodeAndFireRead(ChannelHandlerContext ctx, Buffer input) {
                // Can we read our length header?
                if (input.readableBytes() < 4) {
                    return false;
                }

                int start = input.readerOffset();
                int length = input.readInt();
                // Can we read the rest of the message?
                if (input.readableBytes() < length) {
                    input.readerOffset(start);
                    return false;
                }

                // We can read our message in full.
                Buffer messageBuffer = input.split(input.readerOffset() + length);
                ctx.fireChannelRead(messageBuffer);
                return true;
            }
        });

        List<byte[]> messages = new ArrayList<>();
        Buffer messagesBuffer = BufferAllocator.onHeapUnpooled().allocate(132 * 1024);
        SplittableRandom rng = new SplittableRandom(42);
        for (int i = 0; i < 1000; i++) {
            byte[] message = new byte[rng.nextInt(4, 256)];
            rng.nextBytes(message);
            message[0] = (byte) (i >> 24);
            message[1] = (byte) (i >> 16);
            message[2] = (byte) (i >> 8);
            message[3] = (byte) i;
            messages.add(message);
            messagesBuffer.ensureWritable(4 + message.length, 1024, false);
            messagesBuffer.writeInt(message.length);
            for (byte b : message) {
                messagesBuffer.writeByte(b);
            }
        }

        while (messagesBuffer.readableBytes() > 0) {
            int length = rng.nextInt(1, Math.min(500, messagesBuffer.readableBytes() + 1));
            if (length == messagesBuffer.readableBytes()) {
                channel.writeInbound(messagesBuffer);
            } else {
                channel.writeInbound(messagesBuffer.split(length));
            }
        }

        Iterator<byte[]> expectedItr = messages.iterator();
        Buffer actualMessage;
        while ((actualMessage = channel.readInbound()) != null) {
            try (Buffer ignore = actualMessage) {
                assertTrue(expectedItr.hasNext());
                assertThat(readByteArray(actualMessage)).containsExactly(expectedItr.next());
            }
        }
        assertFalse(expectedItr.hasNext());
    }
}
