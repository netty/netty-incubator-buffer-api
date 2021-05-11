package io.netty.buffer.api.pool;

/**
 * Expose metrics for an SizeClasses.
 */
public interface SizeClassesMetric {

    /**
     * Computes size from lookup table according to sizeIdx.
     *
     * @return size
     */
    int sizeIdx2size(int sizeIdx);

    /**
     * Computes size according to sizeIdx.
     *
     * @return size
     */
    int sizeIdx2sizeCompute(int sizeIdx);

    /**
     * Computes size from lookup table according to pageIdx.
     *
     * @return size which is multiples of pageSize.
     */
    long pageIdx2size(int pageIdx);

    /**
     * Computes size according to pageIdx.
     *
     * @return size which is multiples of pageSize
     */
    long pageIdx2sizeCompute(int pageIdx);

    /**
     * Normalizes request size up to the nearest size class.
     *
     * @param size request size
     *
     * @return sizeIdx of the size class
     */
    int size2SizeIdx(int size);

    /**
     * Normalizes request size up to the nearest pageSize class.
     *
     * @param pages multiples of pageSizes
     *
     * @return pageIdx of the pageSize class
     */
    int pages2pageIdx(int pages);

    /**
     * Normalizes request size down to the nearest pageSize class.
     *
     * @param pages multiples of pageSizes
     *
     * @return pageIdx of the pageSize class
     */
    int pages2pageIdxFloor(int pages);

    /**
     * Normalizes usable size that would result from allocating an object with the
     * specified size and alignment.
     *
     * @param size request size
     *
     * @return normalized size
     */
    int normalizeSize(int size);
}
