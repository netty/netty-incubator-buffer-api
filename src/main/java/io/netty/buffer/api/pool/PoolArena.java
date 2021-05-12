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

import io.netty.buffer.api.AllocatorControl;
import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.BufferAllocator;
import io.netty.buffer.api.MemoryManager;
import io.netty.buffer.api.internal.Statics;
import io.netty.util.internal.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static io.netty.buffer.api.pool.PoolChunk.isSubpage;
import static java.lang.Math.max;

class PoolArena extends SizeClasses implements PoolArenaMetric, AllocatorControl {
    enum SizeClass {
        Small,
        Normal
    }

    final PooledBufferAllocator parent;
    final MemoryManager manager;

    final int numSmallSubpagePools;
    final int directMemoryCacheAlignment;
    private final PoolSubpage[] smallSubpagePools;

    private final PoolChunkList q050;
    private final PoolChunkList q025;
    private final PoolChunkList q000;
    private final PoolChunkList qInit;
    private final PoolChunkList q075;
    private final PoolChunkList q100;

    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    private long allocationsNormal;

    // We need to use the LongAdder here as this is not guarded via synchronized block.
    private final LongAdder allocationsSmall = new LongAdder();
    private final LongAdder allocationsHuge = new LongAdder();
    private final LongAdder activeBytesHuge = new LongAdder();

    private long deallocationsSmall;
    private long deallocationsNormal;

    // We need to use the LongAdder here as this is not guarded via synchronized block.
    private final LongAdder deallocationsHuge = new LongAdder();

    // Number of thread caches backed by this arena.
    final AtomicInteger numThreadCaches = new AtomicInteger();

    protected PoolArena(PooledBufferAllocator parent, MemoryManager manager, int pageSize,
                        int pageShifts, int chunkSize, int cacheAlignment) {
        super(pageSize, pageShifts, chunkSize, cacheAlignment);
        this.parent = parent;
        this.manager = manager;
        directMemoryCacheAlignment = cacheAlignment;

        numSmallSubpagePools = nSubpages;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead();
        }

        q100 = new PoolChunkList(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        chunkListMetrics = List.of(qInit, q000, q025, q050, q075, q100);
    }

    private static PoolSubpage newSubpagePoolHead() {
        PoolSubpage head = new PoolSubpage();
        head.prev = head;
        head.next = head;
        return head;
    }

    private static PoolSubpage[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    boolean isDirect() {
        return manager.isNative();
    }

    Buffer allocate(PooledAllocatorControl control, PoolThreadCache cache, int size) {
        final int sizeIdx = size2SizeIdx(size);

        if (sizeIdx <= smallMaxSizeIdx) {
            return tcacheAllocateSmall(control, cache, size, sizeIdx);
        } else if (sizeIdx < nSizes) {
            return tcacheAllocateNormal(control, cache, size, sizeIdx);
        } else {
            int normCapacity = directMemoryCacheAlignment > 0
                    ? normalizeSize(size) : size;
            // Huge allocations are never served via the cache so just call allocateHuge
            return allocateHuge(normCapacity);
        }
    }

    private Buffer tcacheAllocateSmall(PooledAllocatorControl control, PoolThreadCache cache, final int size,
                                       final int sizeIdx) {
        Buffer buffer = cache.allocateSmall(control, size, sizeIdx);
        if (buffer != null) {
            // was able to allocate out of the cache so move on
            return buffer;
        }

        /*
         * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
         * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
         */
        final PoolSubpage head = smallSubpagePools[sizeIdx];
        final boolean needsNormalAllocation;
        synchronized (head) {
            final PoolSubpage s = head.next;
            needsNormalAllocation = s == head;
            if (!needsNormalAllocation) {
                assert s.doNotDestroy && s.elemSize == sizeIdx2size(sizeIdx);
                long handle = s.allocate();
                assert handle >= 0;
                buffer = s.chunk.allocateBufferWithSubpage(handle, size, cache, control);
            }
        }

        if (needsNormalAllocation) {
            synchronized (this) {
                buffer = allocateNormal(size, sizeIdx, cache, control);
            }
        }

        incSmallAllocation();
        return buffer;
    }

    private Buffer tcacheAllocateNormal(PooledAllocatorControl control, PoolThreadCache cache, int size, int sizeIdx) {
        Buffer buffer = cache.allocateNormal(this, control, size, sizeIdx);
        if (buffer != null) {
            // was able to allocate out of the cache so move on
            return buffer;
        }
        synchronized (this) {
            buffer = allocateNormal(size, sizeIdx, cache, control);
            allocationsNormal++;
        }
        return buffer;
    }

    // Method must be called inside synchronized(this) { ... } block
    private Buffer allocateNormal(int size, int sizeIdx, PoolThreadCache threadCache, PooledAllocatorControl control) {
        Buffer buffer = q050.allocate(size, sizeIdx, threadCache, control);
        if (buffer != null) {
            return buffer;
        }
        buffer = q025.allocate(size, sizeIdx, threadCache, control);
        if (buffer != null) {
            return buffer;
        }
        buffer = q000.allocate(size, sizeIdx, threadCache, control);
        if (buffer != null) {
            return buffer;
        }
        buffer = qInit.allocate(size, sizeIdx, threadCache, control);
        if (buffer != null) {
            return buffer;
        }
        buffer = q075.allocate(size, sizeIdx, threadCache, control);
        if (buffer != null) {
            return buffer;
        }

        // Add a new chunk.
        PoolChunk c = newChunk(pageSize, nPSizes, pageShifts, chunkSize);
        buffer = c.allocate(size, sizeIdx, threadCache, control);
        assert buffer != null;
        qInit.add(c);
        return buffer;
    }

    private void incSmallAllocation() {
        allocationsSmall.increment();
    }

    private Buffer allocateHuge(int size) {
        activeBytesHuge.add(size);
        allocationsHuge.increment();
        BufferAllocator allocator = isDirect()? BufferAllocator.direct() : BufferAllocator.heap();
        return allocator.allocate(size);
    }

    void free(PoolChunk chunk, long handle, int normCapacity, PoolThreadCache cache) {
        SizeClass sizeClass = sizeClass(handle);
        if (cache != null && cache.add(this, chunk, handle, normCapacity, sizeClass)) {
            // cached so not free it.
            return;
        }
        freeChunk(chunk, handle, normCapacity, sizeClass);
    }

    private static SizeClass sizeClass(long handle) {
        return isSubpage(handle) ? SizeClass.Small : SizeClass.Normal;
    }

    void freeChunk(PoolChunk chunk, long handle, int normCapacity, SizeClass sizeClass) {
        final boolean destroyChunk;
        synchronized (this) {
            switch (sizeClass) {
                case Normal:
                    ++deallocationsNormal;
                    break;
                case Small:
                    ++deallocationsSmall;
                    break;
                default:
                    throw new Error();
            }
            destroyChunk = !chunk.parent.free(chunk, handle, normCapacity);
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            chunk.destroy();
        }
    }

    PoolSubpage findSubpagePoolHead(int sizeIdx) {
        return smallSubpagePools[sizeIdx];
    }

    @Override
    public Object allocateUntethered(Buffer originator, int size) {
        throw new AssertionError("PoolChunk base buffers should never need to reallocate.");
    }

    @Override
    public void recoverMemory(Object memory) {
        // This means we've lost all strong references to a PoolChunk.
        // Probably means we don't need it anymore, so just free its memory.
        manager.discardRecoverableMemory(memory);
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<>();
        for (PoolSubpage head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage s = head.next;
            do {
                metrics.add(s);
                s = s.next;
            } while (s != head);
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }

        return allocationsSmall.longValue() + allocsNormal + allocationsHuge.longValue();
    }

    @Override
    public long numTinyAllocations() {
        return 0;
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.longValue();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.longValue();
    }

    @Override
    public long numTinyDeallocations() {
        return 0;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.longValue();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.longValue();
    }

    @Override
    public  long numActiveAllocations() {

        long val = allocationsSmall.longValue() + allocationsHuge.longValue()
                - deallocationsHuge.longValue();
        synchronized (this) {
            val += allocationsNormal - (deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return 0;
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.longValue();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    protected final PoolChunk newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize) {
        Buffer base = manager.allocateShared(this, chunkSize, manager.drop(), Statics.CLEANER);
        Object memory = manager.unwrapRecoverableMemory(base);
        return new PoolChunk(
                this, base, memory, pageSize, pageShifts, chunkSize, maxPageIdx);
    }

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage s = head.next;
            do {
                buf.append(s);
                s = s.next;
            } while (s != head);
        }
    }

    public void close() {
        for (PoolSubpage page : smallSubpagePools) {
            page.destroy();
        }
        for (PoolChunkList list : new PoolChunkList[] {qInit, q000, q025, q050, q100}) {
            list.destroy();
        }
    }
}
