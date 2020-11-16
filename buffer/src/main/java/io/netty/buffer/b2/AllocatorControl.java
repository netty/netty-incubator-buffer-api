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
package io.netty.buffer.b2;

/**
 * Methods for accessing and controlling the internals of an allocator.
 * This interface is intended to be used by implementors of the {@link Allocator}, {@link Buf} and
 * {@link MemoryManager} interfaces.
 */
public interface AllocatorControl {
    /**
     * Allocate a buffer that is not tethered to any particular {@link Drop} implementation,
     * and return the recoverable memory object from it.
     * <p>
     * This allows a buffer to implement {@link Buf#ensureWritable(int)} by having new memory allocated to it,
     * without that memory being attached to some other lifetime.
     *
     *  @param originator The buffer that originated the request for an untethered memory allocated.
     * @param size The size of the requested memory allocation, in bytes.
     * @return A "recoverable memory" object that is the requested allocation.
     */
    Object allocateUntethered(Buf originator, int size);
}
