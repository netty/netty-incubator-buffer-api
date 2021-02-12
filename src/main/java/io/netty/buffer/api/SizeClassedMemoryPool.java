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

import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static io.netty.buffer.api.Statics.NO_OP_DROP;
import static java.lang.invoke.MethodHandles.lookup;

class SizeClassedMemoryPool implements BufferAllocator, AllocatorControl, Drop<Buffer> {
    private static final VarHandle CLOSE = Statics.findVarHandle(
            lookup(), SizeClassedMemoryPool.class, "closed", boolean.class);
    private final MemoryManager manager;
    private final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<Send<Buffer>>> pool;
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
        Send<Buffer> send = sizeClassPool.poll();
        if (send != null) {
            return send.receive()
                       .reset()
                       .readOnly(false)
                       .fill((byte) 0)
                       .order(ByteOrder.nativeOrder());
        }
        return createBuf(size, getDrop());
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
                Send<Buffer> send;
                while ((send = v.poll()) != null) {
                    try {
                        send.receive().close();
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
            dispose(buf);
            return;
        }
        var sizeClassPool = getSizeClassPool(buf.capacity());
        sizeClassPool.offer(buf.send());
        if (closed) {
            Send<Buffer> send;
            while ((send = sizeClassPool.poll()) != null) {
                send.receive().close();
            }
        }
    }

    @Override
    public Object allocateUntethered(Buffer originator, int size) {
        var sizeClassPool = getSizeClassPool(size);
        Send<Buffer> send = sizeClassPool.poll();
        Buffer untetheredBuf;
        if (send != null) {
            var transfer = (TransferSend<Buffer, Buffer>) send;
            var owned = transfer.unsafeUnwrapOwned();
            untetheredBuf = owned.transferOwnership(NO_OP_DROP);
        } else {
            untetheredBuf = createBuf(size, NO_OP_DROP);
        }
        return manager.unwrapRecoverableMemory(untetheredBuf);
    }

    @Override
    public void recoverMemory(Object memory) {
        var drop = getDrop();
        var buf = manager.recoverMemory(memory, drop);
        drop.attach(buf);
        buf.close();
    }

    private ConcurrentLinkedQueue<Send<Buffer>> getSizeClassPool(int size) {
        return pool.computeIfAbsent(size, k -> new ConcurrentLinkedQueue<>());
    }

    private void dispose(Buffer buf) {
        manager.drop().drop(buf);
    }
}
