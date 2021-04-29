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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Supplier;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BufferReadOnlyTest extends BufferTestSupport {

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
             Buffer buf = CompositeBuffer.compose(allocator)) {
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

    @ParameterizedTest
    @MethodSource("allocators")
    public void readOnlyBuffersCannotChangeWriteOffset(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator();
             Buffer buf = allocator.allocate(8).readOnly(true)) {
            assertThrows(IllegalStateException.class, () -> buf.writerOffset(4));
        }
    }

    @Test
    public void modifyingConstBufferDoesNotImpactSiblings() {
        Supplier<Buffer> supplier = BufferAllocator.heap().constBufferSupplier(new byte[] {1, 2, 3, 4});
        try (Buffer a = supplier.get();
             Buffer b = supplier.get().order(LITTLE_ENDIAN)) {
            a.order(BIG_ENDIAN).readOnly(false).setInt(0, 0xA1A2A3A4);
            a.readerOffset(2);
            assertThat(toByteArray(a)).containsExactly(0xA1, 0xA2, 0xA3, 0xA4);
            assertThat(toByteArray(b)).containsExactly(1, 2, 3, 4);
            assertThat(b.readerOffset()).isZero();
            assertThat(b.order()).isEqualTo(LITTLE_ENDIAN);
            assertThat(a.writerOffset()).isEqualTo(4);
            assertThat(b.writerOffset()).isEqualTo(4);
        }
    }
}
