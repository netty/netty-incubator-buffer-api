/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer.b2;

import jdk.incubator.foreign.MemorySegment;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.invoke.MethodHandles.*;

abstract class SizeClassedMemoryPool implements Allocator, Drop<BBuf> {
    private static final VarHandle CLOSE = Statics.findVarHandle(
            lookup(), SizeClassedMemoryPool.class, "closed", boolean.class);
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<Send<Buf>>> pool;
    private final Drop<BBuf> disposer;
    @SuppressWarnings("unused")
    private volatile boolean closed;

    protected SizeClassedMemoryPool(boolean allocatesNativeMemory) {
        pool = new ConcurrentHashMap<>();
        disposer = allocatesNativeMemory ? BBuf.SEGMENT_CLOSE_NATIVE : BBuf.SEGMENT_CLOSE;
    }

    @Override
    public Buf allocate(long size) {
        var sizeClassPool = getSizeClassPool(size);
        Send<Buf> send = sizeClassPool.poll();
        if (send != null) {
            return send.receive();
        }
        var segment = createMemorySegment(size);
        Statics.MEM_USAGE_NATIVE.add(size);
        return createBBuf(segment);
    }

    protected BBuf createBBuf(MemorySegment segment) {
        return new BBuf(segment, getDrop());
    }

    protected SizeClassedMemoryPool getDrop() {
        return this;
    }

    protected abstract MemorySegment createMemorySegment(long size);

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
    public void drop(BBuf buf) {
        var sizeClassPool = getSizeClassPool(buf.capacity());
        sizeClassPool.offer(buf.send());
        if (closed) {
            var send = sizeClassPool.poll();
            if (send != null) {
                dispose(send.receive());
            }
        }
    }

    private ConcurrentLinkedQueue<Send<Buf>> getSizeClassPool(long size) {
        return pool.computeIfAbsent(size, k -> new ConcurrentLinkedQueue<>());
    }

    private void dispose(Buf buf) {
        disposer.drop((BBuf) buf);
    }
}
