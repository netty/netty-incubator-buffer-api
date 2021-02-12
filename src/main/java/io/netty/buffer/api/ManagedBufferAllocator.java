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

import java.lang.ref.Cleaner;

import static io.netty.buffer.api.Statics.NO_OP_DROP;

class ManagedBufferAllocator implements BufferAllocator, AllocatorControl {
    private final MemoryManager manager;
    private final Cleaner cleaner;

    ManagedBufferAllocator(MemoryManager manager, Cleaner cleaner) {
        this.manager = manager;
        this.cleaner = cleaner;
    }

    @Override
    public Buffer allocate(int size) {
        BufferAllocator.checkSize(size);
        return manager.allocateShared(this, size, manager.drop(), cleaner);
    }

    @Override
    public Object allocateUntethered(Buffer originator, int size) {
        BufferAllocator.checkSize(size);
        var buf = manager.allocateShared(this, size, NO_OP_DROP, null);
        return manager.unwrapRecoverableMemory(buf);
    }

    @Override
    public void recoverMemory(Object memory) {
        // Free the recovered memory.
        manager.recoverMemory(memory, manager.drop()).close();
    }
}
