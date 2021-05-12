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
package io.netty.buffer.api.pool;

import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.Drop;

class PooledDrop implements Drop<Buffer> {
    private final PooledAllocatorControl control;
    private PoolArena arena;
    private PoolChunk chunk;
    private PoolThreadCache threadCache;
    private long handle;
    private int normSize;

    PooledDrop(PooledAllocatorControl control, PoolArena arena, PoolChunk chunk, PoolThreadCache threadCache,
               long handle, int normSize) {
        this.control = control;
        this.arena = arena;
        this.chunk = chunk;
        this.threadCache = threadCache;
        this.handle = handle;
        this.normSize = normSize;
    }

    @Override
    public void drop(Buffer obj) {
        arena.free(chunk, handle, normSize, threadCache);
    }

    @Override
    public void attach(Buffer obj) {
        if (control.updates > 0) {
            arena = control.arena;
            chunk = control.chunk;
            threadCache = control.threadCache;
            handle = control.handle;
            normSize = control.normSize;
            control.updates = 0;
        }
    }
}
