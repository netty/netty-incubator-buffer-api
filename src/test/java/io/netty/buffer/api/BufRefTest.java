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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class BufRefTest {
    @Test
    public void closingBufRefMustCloseOwnedBuf() {
        try (Allocator allocator = Allocator.heap()) {
            BufRef ref;
            try (Buf b = allocator.allocate(8)) {
                ref = new BufRef(b);
            }
            ref.contents().writeInt(42);
            assertThat(ref.contents().readInt()).isEqualTo(42);
            ref.close();
            assertThrows(IllegalStateException.class, () -> ref.contents().writeInt(32));
        }
    }

    @Test
    public void closingBufRefMustCloseOwnedBufFromSend() {
        try (Allocator allocator = Allocator.heap();
             Buf buf = allocator.allocate(8)) {
            BufRef ref = new BufRef(buf.send());
            ref.contents().writeInt(42);
            assertThat(ref.contents().readInt()).isEqualTo(42);
            ref.close();
            assertThrows(IllegalStateException.class, () -> ref.contents().writeInt(32));
        }
    }

    @Test
    public void mustCloseOwnedBufferWhenReplaced() {
        try (Allocator allocator = Allocator.heap()) {
            Buf orig;
            BufRef ref;
            try (Buf buf = allocator.allocate(8)) {
                ref = new BufRef(orig = buf);
            }

            orig.writeInt(42);
            assertThat(ref.contents().readInt()).isEqualTo(42);

            try (Buf buf = allocator.allocate(8)) {
                ref.replace(buf); // Pass replacement directly.
            }

            assertThrows(IllegalStateException.class, () -> orig.writeInt(32));
            ref.contents().writeInt(42);
            assertThat(ref.contents().readInt()).isEqualTo(42);
            ref.close();
            assertThrows(IllegalStateException.class, () -> ref.contents().writeInt(32));
        }
    }

    @Test
    public void mustCloseOwnedBufferWhenReplacedFromSend() {
        try (Allocator allocator = Allocator.heap()) {
            Buf orig;
            BufRef ref;
            try (Buf buf = allocator.allocate(8)) {
                ref = new BufRef(orig = buf);
            }

            orig.writeInt(42);
            assertThat(ref.contents().readInt()).isEqualTo(42);

            try (Buf buf = allocator.allocate(8)) {
                ref.replace(buf.send()); // Pass replacement via send().
            }

            assertThrows(IllegalStateException.class, () -> orig.writeInt(32));
            ref.contents().writeInt(42);
            assertThat(ref.contents().readInt()).isEqualTo(42);
            ref.close();
            assertThrows(IllegalStateException.class, () -> ref.contents().writeInt(32));
        }
    }

    @Test
    public void sendingRefMustSendBuffer() {
        try (Allocator allocator = Allocator.heap();
             BufRef refA = new BufRef(allocator.allocate(8).send())) {
            refA.contents().writeInt(42);
            Send<BufRef> send = refA.send();
            assertThrows(IllegalStateException.class, () -> refA.contents().readInt());
            try (BufRef refB = send.receive()) {
                assertThat(refB.contents().readInt()).isEqualTo(42);
            }
        }
    }
}
