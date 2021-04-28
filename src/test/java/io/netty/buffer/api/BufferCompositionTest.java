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

import java.nio.ByteOrder;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BufferCompositionTest extends BufferTestSupport {

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
                    composite = CompositeBuffer.compose(allocator, a, b);
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
            var e = assertThrows(IllegalArgumentException.class, () -> CompositeBuffer.compose(allocator, a, a));
            assertThat(e).hasMessageContaining("duplicate");

            try (CompositeBuffer composite = CompositeBuffer.compose(allocator, a)) {
                a.close();
                try {
                    e = assertThrows(IllegalArgumentException.class,
                            () -> composite.extendWith(a));
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
             Buffer composite = CompositeBuffer.compose(allocator,
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
            CompositeBuffer buf = CompositeBuffer.compose(allocator, a);
            try (buf; a) {
                a.close();
                try {
                    assertThrows(IllegalArgumentException.class, () -> buf.extendWith(buf));
                    assertTrue(buf.isOwned());
                    try (Buffer composite = CompositeBuffer.compose(allocator, buf)) {
                        // the composing increments the reference count of constituent buffers...
                        // counter-act this, so it can be extended:
                        a.close(); // buf is now owned, so it can be extended.
                        try {
                            assertThrows(IllegalArgumentException.class,
                                    () -> buf.extendWith(composite));
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
    public void ensureWritableOnCompositeBuffersMustRespectExistingBigEndianByteOrder(Fixture fixture) {
        try (BufferAllocator allocator = fixture.createAllocator()) {
            Buffer composite;
            try (Buffer a = allocator.allocate(4, BIG_ENDIAN)) {
                composite = CompositeBuffer.compose(allocator, a);
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
                composite = CompositeBuffer.compose(allocator, a);
            }
            try (composite) {
                composite.writeInt(0x05060708);
                composite.ensureWritable(4);
                composite.writeInt(0x01020304);
                assertEquals(0x0102030405060708L, composite.readLong());
            }
        }
    }

    @Test
    public void emptyCompositeBufferMustUseNativeByteOrder() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer composite = CompositeBuffer.compose(allocator)) {
            assertThat(composite.order()).isEqualTo(ByteOrder.nativeOrder());
        }
    }

    @Test
    public void extendOnNonCompositeBufferMustThrow() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer a = allocator.allocate(8);
             Buffer b = allocator.allocate(8)) {
            assertThrows(ClassCastException.class, () -> ((CompositeBuffer) a).extendWith(b));
        }
    }

    @Test
    public void extendingNonOwnedCompositeBufferMustThrow() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer a = allocator.allocate(8);
             Buffer b = allocator.allocate(8);
             CompositeBuffer composed = CompositeBuffer.compose(allocator, a)) {
            try (Buffer ignore = composed.acquire()) {
                var exc = assertThrows(IllegalStateException.class, () -> composed.extendWith(b));
                assertThat(exc).hasMessageContaining("owned");
            }
        }
    }

    @Test
    public void extendingCompositeBufferWithItselfMustThrow() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            CompositeBuffer composite;
            try (Buffer a = allocator.allocate(8)) {
                composite = CompositeBuffer.compose(allocator, a);
            }
            try (composite) {
                var exc = assertThrows(IllegalArgumentException.class,
                        () -> composite.extendWith(composite));
                assertThat(exc).hasMessageContaining("cannot be extended");
            }
        }
    }

    @Test
    public void extendingWithZeroCapacityBufferHasNoEffect() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             CompositeBuffer composite = CompositeBuffer.compose(allocator)) {
            composite.extendWith(composite);
            assertThat(composite.capacity()).isZero();
            assertThat(composite.countComponents()).isZero();
        }
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            Buffer a = allocator.allocate(1);
            CompositeBuffer composite = CompositeBuffer.compose(allocator, a);
            a.close();
            assertTrue(composite.isOwned());
            assertThat(composite.capacity()).isOne();
            assertThat(composite.countComponents()).isOne();
            try (Buffer b = CompositeBuffer.compose(allocator)) {
                composite.extendWith(b);
            }
            assertTrue(composite.isOwned());
            assertThat(composite.capacity()).isOne();
            assertThat(composite.countComponents()).isOne();
        }
    }

    @Test
    public void extendingCompositeBufferWithNullMustThrow() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             CompositeBuffer composite = CompositeBuffer.compose(allocator)) {
            assertThrows(NullPointerException.class, () -> composite.extendWith(null));
        }
    }

    @Test
    public void extendingCompositeBufferMustIncreaseCapacityByGivenBigEndianBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             CompositeBuffer composite = CompositeBuffer.compose(allocator)) {
            assertThat(composite.capacity()).isZero();
            try (Buffer buf = allocator.allocate(8, BIG_ENDIAN)) {
                composite.extendWith(buf);
            }
            assertThat(composite.capacity()).isEqualTo(8);
            composite.writeLong(0x0102030405060708L);
            assertThat(composite.readLong()).isEqualTo(0x0102030405060708L);
        }
    }

    @Test
    public void extendingCompositeBufferMustIncreaseCapacityByGivenLittleEndianBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             CompositeBuffer composite = CompositeBuffer.compose(allocator)) {
            assertThat(composite.capacity()).isZero();
            try (Buffer buf = allocator.allocate(8, LITTLE_ENDIAN)) {
                composite.extendWith(buf);
            }
            assertThat(composite.capacity()).isEqualTo(8);
            composite.writeLong(0x0102030405060708L);
            assertThat(composite.readLong()).isEqualTo(0x0102030405060708L);
        }
    }

    @Test
    public void extendingBigEndianCompositeBufferMustThrowIfExtensionIsLittleEndian() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            CompositeBuffer composite;
            try (Buffer a = allocator.allocate(8, BIG_ENDIAN)) {
                composite = CompositeBuffer.compose(allocator, a);
            }
            try (composite) {
                try (Buffer b = allocator.allocate(8, LITTLE_ENDIAN)) {
                    var exc = assertThrows(IllegalArgumentException.class,
                            () -> composite.extendWith(b));
                    assertThat(exc).hasMessageContaining("byte order");
                }
            }
        }
    }

    @Test
    public void extendingLittleEndianCompositeBufferMustThrowIfExtensionIsBigEndian() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            CompositeBuffer composite;
            try (Buffer a = allocator.allocate(8, LITTLE_ENDIAN)) {
                composite = CompositeBuffer.compose(allocator, a);
            }
            try (composite) {
                try (Buffer b = allocator.allocate(8, BIG_ENDIAN)) {
                    var exc = assertThrows(IllegalArgumentException.class,
                            () -> composite.extendWith(b));
                    assertThat(exc).hasMessageContaining("byte order");
                }
            }
        }
    }

    @Test
    public void emptyCompositeBufferMustAllowExtendingWithBufferWithBigEndianByteOrder() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            try (CompositeBuffer composite = CompositeBuffer.compose(allocator)) {
                try (Buffer b = allocator.allocate(8, BIG_ENDIAN)) {
                    composite.extendWith(b);
                    assertThat(composite.order()).isEqualTo(BIG_ENDIAN);
                }
            }
        }
    }

    @Test
    public void emptyCompositeBufferMustAllowExtendingWithBufferWithLittleEndianByteOrder() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            try (CompositeBuffer composite = CompositeBuffer.compose(allocator)) {
                try (Buffer b = allocator.allocate(8, LITTLE_ENDIAN)) {
                    composite.extendWith(b);
                    assertThat(composite.order()).isEqualTo(LITTLE_ENDIAN);
                }
            }
        }
    }

    @Test
    public void emptyCompositeBufferMustAllowExtendingWithReadOnlyBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            try (CompositeBuffer composite = CompositeBuffer.compose(allocator)) {
                try (Buffer b = allocator.allocate(8).readOnly(true)) {
                    composite.extendWith(b);
                    assertTrue(composite.readOnly());
                }
            }
        }
    }

    @Test
    public void whenExtendingCompositeBufferWithWriteOffsetAtCapacityExtensionWriteOffsetCanBeNonZero() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            CompositeBuffer composite;
            try (Buffer a = allocator.allocate(8)) {
                composite = CompositeBuffer.compose(allocator, a);
            }
            try (composite) {
                composite.writeLong(0);
                try (Buffer b = allocator.allocate(8)) {
                    b.writeInt(1);
                    composite.extendWith(b);
                    assertThat(composite.capacity()).isEqualTo(16);
                    assertThat(composite.writerOffset()).isEqualTo(12);
                }
            }
        }
    }

    @Test
    public void whenExtendingCompositeBufferWithWriteOffsetLessThanCapacityExtensionWriteOffsetMustZero() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            CompositeBuffer composite;
            try (Buffer a = allocator.allocate(8)) {
                composite = CompositeBuffer.compose(allocator, a);
            }
            try (composite) {
                composite.writeInt(0);
                try (Buffer b = allocator.allocate(8)) {
                    b.writeInt(1);
                    var exc = assertThrows(IllegalArgumentException.class,
                            () -> composite.extendWith(b));
                    assertThat(exc).hasMessageContaining("unwritten gap");
                    b.writerOffset(0);
                    composite.extendWith(b);
                    assertThat(composite.capacity()).isEqualTo(16);
                    assertThat(composite.writerOffset()).isEqualTo(4);
                }
            }
        }
    }

    @Test
    public void whenExtendingCompositeBufferWithReadOffsetAtCapacityExtensionReadOffsetCanBeNonZero() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            CompositeBuffer composite;
            try (Buffer a = allocator.allocate(8)) {
                composite = CompositeBuffer.compose(allocator, a);
            }
            try (composite) {
                composite.writeLong(0);
                composite.readLong();
                try (Buffer b = allocator.allocate(8)) {
                    b.writeInt(1);
                    b.readInt();
                    composite.extendWith(b);
                    assertThat(composite.capacity()).isEqualTo(16);
                    assertThat(composite.writerOffset()).isEqualTo(12);
                }
            }
        }
    }

    @Test
    public void whenExtendingCompositeBufferWithReadOffsetLessThanCapacityExtensionReadOffsetMustZero() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            CompositeBuffer composite;
            try (Buffer a = allocator.allocate(8)) {
                composite = CompositeBuffer.compose(allocator, a);
            }
            try (composite) {
                composite.writeLong(0);
                composite.readInt();
                try (Buffer b = allocator.allocate(8)) {
                    b.writeInt(1);
                    b.readInt();
                    var exc = assertThrows(IllegalArgumentException.class,
                            () -> composite.extendWith(b));
                    assertThat(exc).hasMessageContaining("unread gap");
                    b.readerOffset(0);
                    composite.extendWith(b);
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
            assertThrows(IllegalArgumentException.class, () -> CompositeBuffer.compose(allocator, a, b));
        }
    }

    @Test
    public void composingReadOnlyBuffersMustCreateReadOnlyCompositeBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer a = allocator.allocate(4).readOnly(true);
             Buffer b = allocator.allocate(4).readOnly(true);
             Buffer composite = CompositeBuffer.compose(allocator, a, b)) {
            assertTrue(composite.readOnly());
            verifyWriteInaccessible(composite);
        }
    }

    @Test
    public void composingReadOnlyAndWritableBuffersMustThrow() {
        try (BufferAllocator allocator = BufferAllocator.heap();
             Buffer a = allocator.allocate(8).readOnly(true);
             Buffer b = allocator.allocate(8)) {
            assertThrows(IllegalArgumentException.class, () -> CompositeBuffer.compose(allocator, a, b));
            assertThrows(IllegalArgumentException.class, () -> CompositeBuffer.compose(allocator, b, a));
            assertThrows(IllegalArgumentException.class, () -> CompositeBuffer.compose(allocator, a, b, a));
            assertThrows(IllegalArgumentException.class, () -> CompositeBuffer.compose(allocator, b, a, b));
        }
    }

    @Test
    public void compositeWritableBufferCannotBeExtendedWithReadOnlyBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            CompositeBuffer composite;
            try (Buffer a = allocator.allocate(8)) {
                composite = CompositeBuffer.compose(allocator, a);
            }
            try (composite; Buffer b = allocator.allocate(8).readOnly(true)) {
                assertThrows(IllegalArgumentException.class, () -> composite.extendWith(b));
            }
        }
    }

    @Test
    public void compositeReadOnlyBufferCannotBeExtendedWithWritableBuffer() {
        try (BufferAllocator allocator = BufferAllocator.heap()) {
            CompositeBuffer composite;
            try (Buffer a = allocator.allocate(8).readOnly(true)) {
                composite = CompositeBuffer.compose(allocator, a);
            }
            try (composite; Buffer b = allocator.allocate(8)) {
                assertThrows(IllegalArgumentException.class, () -> composite.extendWith(b));
            }
        }
    }
}
