package io.netty.buffer.api.pool;

import io.netty.buffer.api.Buffer;
import io.netty.util.internal.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.lang.Math.*;

final class PoolChunkList implements PoolChunkListMetric {
    private static final Iterator<PoolChunkMetric> EMPTY_METRICS = Collections.emptyIterator();
    private final PoolArena arena;
    private final PoolChunkList nextList;
    private final int minUsage;
    private final int maxUsage;
    private final int maxCapacity;
    private PoolChunk head;
    private final int freeMinThreshold;
    private final int freeMaxThreshold;

    // This is only update once when create the linked like list of PoolChunkList in PoolArena constructor.
    private PoolChunkList prevList;

    PoolChunkList(PoolArena arena, PoolChunkList nextList, int minUsage, int maxUsage, int chunkSize) {
        assert minUsage <= maxUsage;
        this.arena = arena;
        this.nextList = nextList;
        this.minUsage = minUsage;
        this.maxUsage = maxUsage;
        maxCapacity = calculateMaxCapacity(minUsage, chunkSize);

        // the thresholds are aligned with PoolChunk.usage() logic:
        // 1) basic logic: usage() = 100 - freeBytes * 100L / chunkSize
        //    so, for example: (usage() >= maxUsage) condition can be transformed in the following way:
        //      100 - freeBytes * 100L / chunkSize >= maxUsage
        //      freeBytes <= chunkSize * (100 - maxUsage) / 100
        //      let freeMinThreshold = chunkSize * (100 - maxUsage) / 100, then freeBytes <= freeMinThreshold
        //
        //  2) usage() returns an int value and has a floor rounding during a calculation,
        //     to be aligned absolute thresholds should be shifted for "the rounding step":
        //       freeBytes * 100 / chunkSize < 1
        //       the condition can be converted to: freeBytes < 1 * chunkSize / 100
        //     this is why we have + 0.99999999 shifts. A example why just +1 shift cannot be used:
        //       freeBytes = 16777216 == freeMaxThreshold: 16777216, usage = 0 < minUsage: 1, chunkSize: 16777216
        //     At the same time we want to have zero thresholds in case of (maxUsage == 100) and (minUsage == 100).
        //
        freeMinThreshold = maxUsage == 100 ? 0 : (int) (chunkSize * (100.0 - maxUsage + 0.99999999) / 100L);
        freeMaxThreshold = minUsage == 100 ? 0 : (int) (chunkSize * (100.0 - minUsage + 0.99999999) / 100L);
    }

    /**
     * Calculates the maximum capacity of a buffer that will ever be possible to allocate out of the {@link PoolChunk}s
     * that belong to the {@link PoolChunkList} with the given {@code minUsage} and {@code maxUsage} settings.
     */
    private static int calculateMaxCapacity(int minUsage, int chunkSize) {
        minUsage = minUsage0(minUsage);

        if (minUsage == 100) {
            // If the minUsage is 100 we can not allocate anything out of this list.
            return 0;
        }

        // Calculate the maximum amount of bytes that can be allocated from a PoolChunk in this PoolChunkList.
        //
        // As an example:
        // - If a PoolChunkList has minUsage == 25 we are allowed to allocate at most 75% of the chunkSize because
        //   this is the maximum amount available in any PoolChunk in this PoolChunkList.
        return  (int) (chunkSize * (100L - minUsage) / 100L);
    }

    void prevList(PoolChunkList prevList) {
        assert this.prevList == null;
        this.prevList = prevList;
    }

    Buffer allocate(int size, int sizeIdx, PoolThreadCache threadCache) {
        int normCapacity = arena.sizeIdx2size(sizeIdx);
        if (normCapacity > maxCapacity) {
            // Either this PoolChunkList is empty, or the requested capacity is larger than the capacity which can
            // be handled by the PoolChunks that are contained in this PoolChunkList.
            return null;
        }

        for (PoolChunk cur = head; cur != null; cur = cur.next) {
            Buffer buffer = cur.allocate(size, sizeIdx, threadCache);
            if (buffer != null) {
                if (cur.freeBytes <= freeMinThreshold) {
                    remove(cur);
                    nextList.add(cur);
                }
                return buffer;
            }
        }
        return null;
    }

    boolean free(PoolChunk chunk, long handle, int normCapacity) {
        chunk.free(handle, normCapacity);
        if (chunk.freeBytes > freeMaxThreshold) {
            remove(chunk);
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }
        return true;
    }

    private boolean move(PoolChunk chunk) {
        if (chunk.freeBytes > freeMaxThreshold) {
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }

        // PoolChunk fits into this PoolChunkList, adding it here.
        add0(chunk);
        return true;
    }

    /**
     * Moves the {@link PoolChunk} down the {@link PoolChunkList} linked-list, so it will end up in the right
     * {@link PoolChunkList} that has the correct minUsage / maxUsage in respect to {@link PoolChunk#usage()}.
     */
    private boolean move0(PoolChunk chunk) {
        if (prevList == null) {
            // There is no previous PoolChunkList so return false which result in having the PoolChunk destroyed and
            // all memory associated with the PoolChunk will be released.
            return false;
        }
        return prevList.move(chunk);
    }

    void add(PoolChunk chunk) {
        if (chunk.freeBytes <= freeMinThreshold) {
            nextList.add(chunk);
            return;
        }
        add0(chunk);
    }

    /**
     * Adds the {@link PoolChunk} to this {@link PoolChunkList}.
     */
    void add0(PoolChunk chunk) {
        chunk.parent = this;
        if (head == null) {
            head = chunk;
            chunk.prev = null;
            chunk.next = null;
        } else {
            chunk.prev = null;
            chunk.next = head;
            head.prev = chunk;
            head = chunk;
        }
    }

    private void remove(PoolChunk cur) {
        if (cur == head) {
            head = cur.next;
            if (head != null) {
                head.prev = null;
            }
        } else {
            PoolChunk next = cur.next;
            cur.prev.next = next;
            if (next != null) {
                next.prev = cur.prev;
            }
        }
    }

    @Override
    public int minUsage() {
        return minUsage0(minUsage);
    }

    @Override
    public int maxUsage() {
        return min(maxUsage, 100);
    }

    private static int minUsage0(int value) {
        return max(1, value);
    }

    @Override
    public Iterator<PoolChunkMetric> iterator() {
        synchronized (arena) {
            if (head == null) {
                return EMPTY_METRICS;
            }
            List<PoolChunkMetric> metrics = new ArrayList<>();
            for (PoolChunk cur = head;;) {
                metrics.add(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
            }
            return metrics.iterator();
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        synchronized (arena) {
            if (head == null) {
                return "none";
            }

            for (PoolChunk cur = head;;) {
                buf.append(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
                buf.append(StringUtil.NEWLINE);
            }
        }
        return buf.toString();
    }

    void destroy(PoolArena arena) {
        PoolChunk chunk = head;
        while (chunk != null) {
            arena.destroyChunk(chunk);
            chunk = chunk.next;
        }
        head = null;
    }
}
