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

import java.nio.ByteBuffer;

/**
 * This interface contain a collection of APIs used in the {@link Buf#forEachReadable(int, OfReadable)} and
 * {@link Buf#forEachWritable(int, OfWritable)} methods.
 */
public interface ComponentProcessor {
    /**
     * A processor of {@linkplain ReadableComponent readable components}.
     */
    @FunctionalInterface
    interface OfReadable extends ComponentProcessor {
        /**
         * Process the given component at the given index in the {@link Buf#forEachReadable(int, OfReadable) iteration}.
         * <p>
         * The component object itself is only valid during this call, but the {@link ByteBuffer byte buffers}, arrays,
         * and native address pointers obtained from it, will be valid until any
         * {@link Buf#isOwned() ownership} requiring operation is performed on the buffer.
         *
         * @param index The current index of the given buffer component, based on the initial index passed to the
         *              {@link Buf#forEachReadable(int, OfReadable)} method.
         * @param component The current buffer component being processed.
         * @return {@code true} if the iteration should continue and more components should be processed, otherwise
         * {@code false} to stop the iteration early.
         */
        boolean process(int index, ReadableComponent component);
    }

    /**
     * A processor of {@linkplain WritableComponent writable components}.
     */
    @FunctionalInterface
    interface OfWritable extends ComponentProcessor {
        /**
         * Process the given component at the given index in the
         * {@link Buf#forEachWritable(int, OfWritable)}  iteration}.
         * <p>
         * The component object itself is only valid during this call, but the {@link ByteBuffer byte buffers}, arrays,
         * and native address pointers obtained from it, will be valid until any
         * {@link Buf#isOwned() ownership} requiring operation is performed on the buffer.
         *
         * @param index The current index of the given buffer component, based on the initial index passed to the
         *              {@link Buf#forEachWritable(int, OfWritable)} method.
         * @param component The current buffer component being processed.
         * @return {@code true} if the iteration should continue and more components should be processed, otherwise
         * {@code false} to stop the iteration early.
         */
        boolean process(int index, WritableComponent component);
    }

    /**
     * A view onto the buffer component being processed in a given iteration of
     * {@link Buf#forEachReadable(int, OfReadable)}.
     */
    interface ReadableComponent {

        /**
         * Check if this component is backed by a cached byte array than can be accessed cheaply.
         * <p>
         * <strong>Note</strong> that regardless of what this method returns, the array should not be used to modify the
         * contents of this buffer component.
         *
         * @return {@code true} if {@link #readableArray()} is a cheap operation, otherwise {@code false}.
         */
        boolean hasReadableArray();

        /**
         * Get a byte array of the contents of this component.
         * <p>
         * <strong>Note</strong> that the array is meant to be read-only. It may either be a direct reference to the
         * concrete array instance that is backing this component, or it is a fresh copy.
         * Writing to the array may produce undefined behaviour.
         *
         * @return A byte array of the contents of this component.
         * @throws UnsupportedOperationException if {@link #hasReadableArray()} returns {@code false}.
         */
        byte[] readableArray();

        /**
         * An offset into the {@link #readableArray()} where this component starts.
         *
         * @return An offset into {@link #readableArray()}.
         * @throws UnsupportedOperationException if {@link #hasReadableArray()} returns {@code false}.
         */
        int readableArrayOffset();

        /**
         * Give the native memory address backing this buffer, or return 0 if this buffer has no native memory address.
         * <p>
         * <strong>Note</strong> that the address should not be used for writing to the buffer memory, and doing so may
         * produce undefined behaviour.
         *
         * @return The native memory address, if any, otherwise 0.
         */
        long readableNativeAddress();

        /**
         * Get a {@link ByteBuffer} instance for this memory component.
         * <p>
         * <strong>Note</strong> that the {@link ByteBuffer} is read-only, to prevent write accesses to the memory,
         * when the buffer component is obtained through {@link Buf#forEachReadable(int, OfReadable)}.
         *
         * @return A new {@link ByteBuffer} for this memory component.
         */
        ByteBuffer readableBuffer();
    }

    /**
     * A view onto the buffer component being processed in a given iteration of
     * {@link Buf#forEachWritable(int, OfWritable)}.
     */
    interface WritableComponent {

        /**
         * Check if this component is backed by a cached byte array than can be accessed cheaply.
         *
         * @return {@code true} if {@link #writableArray()} is a cheap operation, otherwise {@code false}.
         */
        boolean hasWritableArray();

        /**
         * Get a byte array of the contents of this component.
         *
         * @return A byte array of the contents of this component.
         * @throws UnsupportedOperationException if {@link #hasWritableArray()} returns {@code false}.
         */
        byte[] writableArray();

        /**
         * An offset into the {@link #writableArray()} where this component starts.
         *
         * @return An offset into {@link #writableArray()}.
         * @throws UnsupportedOperationException if {@link #hasWritableArray()} returns {@code false}.
         */
        int writableArrayOffset();

        /**
         * Give the native memory address backing this buffer, or return 0 if this buffer has no native memory address.
         *
         * @return The native memory address, if any, otherwise 0.
         */
        long writableNativeAddress();

        /**
         * Get a {@link ByteBuffer} instance for this memory component, which can be used for modifying the buffer
         * contents.
         *
         * @return A new {@link ByteBuffer} for this memory component.
         */
        ByteBuffer writableBuffer();
    }
}
