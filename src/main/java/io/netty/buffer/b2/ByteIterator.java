/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty.buffer.b2;

import io.netty.util.ByteProcessor;

/**
 * The ByteIterator scans through a sequence of bytes.
 * This is similar to {@link ByteProcessor}, but for external iteration rather than internal iteration.
 * The external iteration allows the callers to control the pace of the iteration.
 * The API includes methods for reading {@code long}s as a batch of 8 bytes.
 * The long values are always in big-endian format, so that the highest-order byte in the long value, contain the byte
 * that would otherwise have been returned by the next call to {@link #nextByte()}.
 */
public interface ByteIterator {
    /**
     * Check if the iterator has at least 8 bytes left.
     * Note that when this method returns {@code false}, the {@link #hasNextByte()} can still return {@code true}.
     * It is recommended to have any long-processing loop be followed by a byte-processing loop for the 7 or fewer
     * bytes that might form a tail in the iterator.
     *
     * @return {@code true} if a call to {@link #nextLong()} would succeed, otherwise {@code false}.
     */
    boolean hasNextLong();

    /**
     * Read and return the next 8 bytes, and move the iterator position forward by 8 bytes.
     * The bytes are packed and return as a {@code long} value in big-endian format, such that the highest-order byte
     * in the long, is the byte that would otherwise have been returned by the next call to {@link #nextByte()}.
     *
     * @return The next 8 bytes in big-endian format.
     * @throws java.util.NoSuchElementException If the iterator has fewer than 8 bytes left.
     */
    long nextLong();

    /**
     * Check if the iterator has at least one byte left.
     *
     * @return {@code true} if the next call to {@link #nextByte()} would succeed, otherwise {@code false}.
     */
    boolean hasNextByte();

    /**
     * Read and return the next byte, and move the iterator position] forward by one byte.
     *
     * @return The next byte.
     * @throws java.util.NoSuchElementException If the iterator has no more bytes left.
     */
    byte nextByte();

    /**
     * The current position of this iterator into the underlying sequence of bytes.
     * For instance, if we are iterating a buffer, this would be the iterators current offset into the buffer.
     *
     * @return The current iterator offset into the underlying sequence of bytes.
     */
    int currentOffset();

    /**
     * Get the current number of bytes left in the iterator.
     *
     * @return The number of bytes left in the iterator.
     */
    int bytesLeft();

    /**
     * Process the remaining bytes in this iterator with the given {@link ByteProcessor}.
     * This method consumes the iterator.
     *
     * @param processor The processor to use for processing the bytes in the iterator.
     * @return The number of bytes processed, if the {@link ByteProcessor#process(byte) process} method returned
     * {@code false}, or {@code -1} if the whole iterator was processed.
     */
    default int process(ByteProcessor processor) {
        boolean requestMore = true;
        int index = currentOffset();
        if (hasNextByte()) {
            byte val = nextByte();
            while ((requestMore = processor.process(val)) && hasNextByte()) {
                val = nextByte();
                index++;
            }
        }
        return requestMore? -1 : index;
    }
}
