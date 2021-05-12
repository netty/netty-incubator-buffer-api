package io.netty.buffer.api.pool;

import io.netty.buffer.api.AllocatorControl;
import io.netty.buffer.api.Buffer;

class PooledAllocatorControl implements AllocatorControl {
    public PoolArena arena;
    public PoolChunk chunk;
    public PoolThreadCache threadCache;
    public long handle;
    public int normSize;
    public Object memory;
    public int offset;
    public int size;

    @Override
    public Object allocateUntethered(Buffer originator, int size) {
        Buffer allocate = arena.parent.allocate(this, size);
        return arena.manager.unwrapRecoverableMemory(allocate);
    }

    @Override
    public void recoverMemory(Object memory) {
        arena.free(chunk, handle, normSize, threadCache);
    }
}
