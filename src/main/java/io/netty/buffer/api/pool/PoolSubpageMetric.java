package io.netty.buffer.api.pool;

/**
 * Metrics for a sub-page.
 */
public interface PoolSubpageMetric {

    /**
     * Return the number of maximal elements that can be allocated out of the sub-page.
     */
    int maxNumElements();

    /**
     * Return the number of available elements to be allocated.
     */
    int numAvailable();

    /**
     * Return the size (in bytes) of the elements that will be allocated.
     */
    int elementSize();

    /**
     * Return the page size (in bytes) of this page.
     */
    int pageSize();
}
