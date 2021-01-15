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
 * A view onto the buffer component being processed in a given iteration of
 * {@link Buf#forEachReadable(int, ComponentProcessor)}, or {@link Buf#forEachWritable(int, ComponentProcessor)}.
 * <p>
 * Instances of this interface are allowed to be mutable behind the scenes, and the data is only guaranteed to be
 * consistent within the given iteration.
 */
public interface Component {

    /**
     * Check if this component is backed by a cached byte array than can be accessed cheaply.
     * <p>
     * <strong>Note</strong> that regardless of what this method returns, the array should not be used to modify the
     * contents of this buffer component.
     *
     * @return {@code true} if {@link #array()} is a cheap operation, otherwise {@code false}.
     */
    boolean hasArray();

    /**
     * Get a byte array of the contents of this component.
     * <p>
     * <strong>Note</strong> that the array is meant to be read-only. It may either be a direct reference to the
     * concrete array instance that is backing this component, or it is a fresh copy.
     * Writing to the array may produce undefined behaviour.
     *
     * @return A byte array of the contents of this component.
     */
    byte[] array();

    int arrayOffset();

    /**
     * Give the native memory address backing this buffer, or return 0 if this buffer has no native memory address.
     * <p>
     * <strong>Note</strong> that the address should not be used for writing to the buffer memory, and doing so may
     * produce undefined behaviour.
     *
     * @return The native memory address, if any, otherwise 0.
     */
    long nativeAddress();

    /**
     * Get a {@link ByteBuffer} instance for this memory component.
     * <p>
     * <strong>Note</strong> that the {@link ByteBuffer} is read-only, to prevent write accesses to the memory,
     * when the buffer component is obtained through {@link Buf#forEachReadable(int, ComponentProcessor)}.
     *
     * @return A new {@link ByteBuffer} for this memory component.
     */
    ByteBuffer buffer();
}
