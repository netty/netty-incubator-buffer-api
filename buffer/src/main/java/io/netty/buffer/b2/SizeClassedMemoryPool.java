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

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.invoke.MethodHandles.lookup;

class SizeClassedMemoryPool implements Allocator, Drop<Buf> {
    private static final VarHandle CLOSE = Statics.findVarHandle(
            lookup(), SizeClassedMemoryPool.class, "closed", boolean.class);
    private final MemoryManager manager;
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<Send<Buf>>> pool;
    @SuppressWarnings("unused")
    private volatile boolean closed;

    protected SizeClassedMemoryPool(MemoryManager manager) {
        this.manager = manager;
        pool = new ConcurrentHashMap<>();
    }

    @Override
    public Buf allocate(long size) {
        Allocator.checkSize(size);
        var sizeClassPool = getSizeClassPool(size);
        Send<Buf> send = sizeClassPool.poll();
        if (send != null) {
            return send.receive();
        }
        return createBuf(size, getDrop());
    }

    protected MemoryManager getMemoryManager() {
        return manager;
    }

    protected Buf createBuf(long size, Drop<Buf> drop) {
        var buf = manager.allocateShared(size, drop, null);
        drop.accept(buf);
        return buf;
    }

    protected Drop<Buf> getDrop() {
        return this;
    }

    @Override
    public void close() {
        if (CLOSE.compareAndSet(this, false, true)) {
            var capturedExceptions = new ArrayList<Exception>(4);
            pool.forEach((k, v) -> {
                Send<Buf> send;
                while ((send = v.poll()) != null) {
                    try {
                        dispose(send.receive());
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
    public void drop(Buf buf) {
        var sizeClassPool = getSizeClassPool(buf.capacity());
        sizeClassPool.offer(buf.send());
        if (closed) {
            Send<Buf> send;
            while ((send = sizeClassPool.poll()) != null) {
                dispose(send.receive());
            }
        }
    }

    void recoverLostMemory(Object memory) {
        var drop = getDrop();
        var buf = manager.recoverMemory(memory, drop);
        drop.accept(buf);
        buf.close();
    }

    private ConcurrentLinkedQueue<Send<Buf>> getSizeClassPool(long size) {
        return pool.computeIfAbsent(size, k -> new ConcurrentLinkedQueue<>());
    }

    private static void dispose(Buf buf) {
        MemSegBuf.SEGMENT_CLOSE.drop((MemSegBuf) buf);
    }
}
