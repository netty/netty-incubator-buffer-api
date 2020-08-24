package io.netty.buffer.b2;

import jdk.incubator.foreign.MemorySegment;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.invoke.MethodHandles.*;

abstract class SizeClassedMemoryPool implements Allocator, Drop<BBuf> {
    private static final VarHandle CLOSE = Statics.findVarHandle(lookup(), SizeClassedMemoryPool.class, "closed", boolean.class);
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<Send<BBuf>>> pool;
    private final Drop<BBuf> disposer;
    @SuppressWarnings("unused")
    private volatile boolean closed;

    protected SizeClassedMemoryPool(boolean allocatesNativeMemory) {
        pool = new ConcurrentHashMap<>();
        disposer = allocatesNativeMemory ? BBuf.SEGMENT_CLOSE_NATIVE : BBuf.SEGMENT_CLOSE;
    }

    @Override
    public BBuf allocate(long size) {
        var sizeClassPool = getSizeClassPool(size);
        Send<BBuf> send = sizeClassPool.poll();
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
            pool.forEach((k,v) -> {
                Send<BBuf> send;
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
        var sizeClassPool = getSizeClassPool(buf.size());
        sizeClassPool.offer(buf.send());
        if (closed) {
            var send = sizeClassPool.poll();
            if (send != null) {
                dispose(send.receive());
            }
        }
    }

    private ConcurrentLinkedQueue<Send<BBuf>> getSizeClassPool(long size) {
        return pool.computeIfAbsent(size, k -> new ConcurrentLinkedQueue<>());
    }

    private void dispose(BBuf buf) {
        disposer.drop(buf);
    }
}
