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
package io.netty.buffer.api.internal;

import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.Drop;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.nio.ByteOrder;

public interface Statics {
    Cleaner CLEANER = Cleaner.create();
    Drop<Buffer> NO_OP_DROP = new Drop<Buffer>() {
        @Override
        public void drop(Buffer obj) {
        }

        @Override
        public String toString() {
            return "NO_OP_DROP";
        }
    };

    static VarHandle findVarHandle(Lookup lookup, Class<?> recv, String name, Class<?> type) {
        try {
            return lookup.findVarHandle(recv, name, type);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T, R> Drop<R> convert(Drop<T> drop) {
        return (Drop<R>) drop;
    }

    static void copyToViaReverseCursor(Buffer src, int srcPos, Buffer dest, int destPos, int length) {
        // Iterate in reverse to account for src and dest buffer overlap.
        var itr = src.openReverseCursor(srcPos + length - 1, length);
        ByteOrder prevOrder = dest.order();
        // We read longs in BE, in reverse, so they need to be flipped for writing.
        dest.order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (itr.readLong()) {
                long val = itr.getLong();
                length -= Long.BYTES;
                dest.setLong(destPos + length, val);
            }
            while (itr.readByte()) {
                dest.setByte(destPos + --length, itr.getByte());
            }
        } finally {
            dest.order(prevOrder);
        }
    }

    static IllegalStateException bufferIsClosed() {
        return new IllegalStateException("This buffer is closed.");
    }

    static IllegalStateException bufferIsReadOnly() {
        return new IllegalStateException("This buffer is read-only.");
    }
}
