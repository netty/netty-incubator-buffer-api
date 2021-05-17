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

import io.netty.buffer.api.internal.Statics;

import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import static io.netty.buffer.api.internal.Statics.NO_OP_DROP;
import static java.lang.invoke.MethodHandles.lookup;

class SizeClassedMemoryPool implements BufferAllocator, AllocatorControl, Drop<Buffer> {
    private static final VarHandle CLOSE = Statics.findVarHandle(
            lookup(), SizeClassedMemoryPool.class, "closed", boolean.class);
    private final MemoryManager manager;
    private final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<Object>> pool;
    @SuppressWarnings("unused")
    private volatile boolean closed;

    protected SizeClassedMemoryPool(MemoryManager manager) {
        this.manager = manager;
        pool = new ConcurrentHashMap<>();
    }

    @Override
    public Buffer allocate(int size) {
        BufferAllocator.checkSize(size);
        var sizeClassPool = getSizeClassPool(size);
        Object memory = sizeClassPool.poll();
        if (memory != null) {
            return recoverMemoryIntoBuffer(memory)
                       .fill((byte) 0)
                       .order(ByteOrder.nativeOrder());
        }
        return createBuf(size, getDrop());
    }

    @Override
    public Supplier<Buffer> constBufferSupplier(byte[] bytes) {
        Buffer constantBuffer = manager.allocateShared(this, bytes.length, manager.drop(), Statics.CLEANER);
        constantBuffer.writeBytes(bytes).makeReadOnly();
        return () -> manager.allocateConstChild(constantBuffer);
    }

    protected MemoryManager getMemoryManager() {
        return manager;
    }

    protected Buffer createBuf(int size, Drop<Buffer> drop) {
        var buf = manager.allocateShared(this, size, drop, null);
        drop.attach(buf);
        return buf;
    }

    protected Drop<Buffer> getDrop() {
        return new CleanerPooledDrop(this, getMemoryManager(), this);
    }

    @Override
    public void close() {
        if (CLOSE.compareAndSet(this, false, true)) {
            var capturedExceptions = new ArrayList<Exception>(4);
            pool.forEach((k, v) -> {
                Object memory;
                while ((memory = v.poll()) != null) {
                    try {
                        manager.discardRecoverableMemory(memory);
                    } catch (Exception e) {
                        capturedExceptions.add(e);
                    }
                }
            });
            if (!capturedExceptions.isEmpty()) {
                var exception = new ResourceDisposeFailedException();
                capturedExceptions.forEach(exception::addSuppressed);
                throw exception;
            }
        }
    }

    @Override
    public void drop(Buffer buf) {
        if (closed) {
            manager.drop().drop(buf);
            return;
        }
        Object mem = manager.unwrapRecoverableMemory(buf);
        var sizeClassPool = getSizeClassPool(manager.capacityOfRecoverableMemory(mem));
        sizeClassPool.offer(mem);
        if (closed) {
            Object memory;
            while ((memory = sizeClassPool.poll()) != null) {
                manager.discardRecoverableMemory(memory);
            }
        }
    }

    @Override
    public String toString() {
        return "SizeClassedMemoryPool";
    }

    @SuppressWarnings("unchecked")
    @Override
    public UntetheredMemory allocateUntethered(Buffer originator, int size) {
        var sizeClassPool = getSizeClassPool(size);
        Object candidateMemory = sizeClassPool.poll();
        if (candidateMemory == null) {
            Buffer untetheredBuf = createBuf(size, NO_OP_DROP);
            candidateMemory = manager.unwrapRecoverableMemory(untetheredBuf);
        }
        Object memory = candidateMemory;

        return new UntetheredMemory() {

            @Override
            public <Memory> Memory memory() {
                return (Memory) memory;
            }

            @Override
            public <BufferType extends Buffer> Drop<BufferType> drop() {
                return (Drop<BufferType>) getDrop();
            }
        };
    }

    @Override
    public void recoverMemory(Object memory) {
        Buffer buf = recoverMemoryIntoBuffer(memory);
        buf.close();
    }

    private Buffer recoverMemoryIntoBuffer(Object memory) {
        var drop = getDrop();
        var buf = manager.recoverMemory(this, memory, drop);
        drop.attach(buf);
        return buf;
    }

    private ConcurrentLinkedQueue<Object> getSizeClassPool(int size) {
        return pool.computeIfAbsent(size, k -> new ConcurrentLinkedQueue<>());
    }
}
