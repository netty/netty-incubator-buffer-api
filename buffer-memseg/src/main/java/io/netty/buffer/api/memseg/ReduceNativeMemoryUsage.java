package io.netty.buffer.api.memseg;

import io.netty.buffer.api.internal.Statics;

final class ReduceNativeMemoryUsage implements Runnable {
    private final long size;

    ReduceNativeMemoryUsage(long size) {
        this.size = size;
    }

    @Override
    public void run() {
        Statics.MEM_USAGE_NATIVE.add(-size);
    }

    @Override
    public String toString() {
        return "ReduceNativeMemoryUsage(by " + size + " bytes)";
    }
}
