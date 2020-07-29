package io.netty.buffer.b2;

import jdk.incubator.foreign.MemorySegment;

import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.invoke.MethodHandles.*;

abstract class SizeClassedMemoryPool implements Allocator, Drop<BBuf> {
    private static final VarHandle CLOSE = Statics.findVarHandle(lookup(), SizeClassedMemoryPool.class, "closed", boolean.class);
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<Send<BBuf>>> pool;
    @SuppressWarnings("unused")
    private volatile boolean closed;

    protected SizeClassedMemoryPool() {
        pool = new ConcurrentHashMap<>();
    }

    @Override
    public BBuf allocate(long size) {
        var sizeClassPool = getSizeClassPool(size);
        Send<BBuf> send = sizeClassPool.poll();
        if (send != null) {
            return send.receive();
        }
        return new BBuf(createMemorySegment(size), this);
    }

    protected abstract MemorySegment createMemorySegment(long size);

    @Override
    public void close() {
        if (CLOSE.compareAndSet(this, false, true)) {
            pool.forEach((k,v) -> {
                Send<BBuf> send;
                while ((send = v.poll()) != null) {
                    dispose(send.receive());
                }
            });
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

    private static void dispose(BBuf buf) {
        BBuf.SEGMENT_CLOSE.drop(buf);
    }
}
