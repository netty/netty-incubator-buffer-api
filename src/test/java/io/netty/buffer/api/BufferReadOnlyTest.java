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
}
