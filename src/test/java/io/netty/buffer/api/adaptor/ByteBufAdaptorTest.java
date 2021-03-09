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
package io.netty.buffer.api.adaptor;

import io.netty.buffer.AbstractByteBufTest;
import io.netty.buffer.ByteBuf;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;

public class ByteBufAdaptorTest extends AbstractByteBufTest {
    static ByteBufAllocatorAdaptor alloc;

    @BeforeClass
    public static void setUpAllocator() {
        alloc = new ByteBufAllocatorAdaptor();
    }

    @AfterClass
    public static void tearDownAllocator() throws Exception {
        alloc.close();
    }

    @Override
    protected ByteBuf newBuffer(int capacity, int maxCapacity) {
        return alloc.buffer(capacity, capacity);
    }

    @Ignore("New buffers not thread-safe like this.")
    @Override
    public void testSliceReadGatheringByteChannelMultipleThreads() throws Exception {
    }

    @Ignore("New buffers not thread-safe like this.")
    @Override
    public void testDuplicateReadGatheringByteChannelMultipleThreads() throws Exception {
    }

    @Ignore("New buffers not thread-safe like this.")
    @Override
    public void testSliceReadOutputStreamMultipleThreads() throws Exception {
    }

    @Ignore("New buffers not thread-safe like this.")
    @Override
    public void testDuplicateReadOutputStreamMultipleThreads() throws Exception {
    }

    @Ignore("New buffers not thread-safe like this.")
    @Override
    public void testSliceBytesInArrayMultipleThreads() throws Exception {
    }

    @Ignore("New buffers not thread-safe like this.")
    @Override
    public void testDuplicateBytesInArrayMultipleThreads() throws Exception {
    }

    @Ignore("This test codifies that asking to reading 0 bytes from an empty but unclosed stream should return -1, " +
            "which is just weird.")
    @Override
    public void testStreamTransfer1() throws Exception {
    }

    @Ignore("Relies on capacity and max capacity being separate things.")
    @Override
    public void testCapacityIncrease() {
    }

    @Ignore("Decreasing capacity not supported in new API.")
    @Override
    public void testCapacityDecrease() {
    }

    @Ignore("Decreasing capacity not supported in new API.")
    @Override
    public void testCapacityNegative() {
        throw new IllegalArgumentException(); // Can't ignore tests annotated with throws expectation?
    }

    @Ignore("Decreasing capacity not supported in new API.")
    @Override
    public void testCapacityEnforceMaxCapacity() {
        throw new IllegalArgumentException(); // Can't ignore tests annotated with throws expectation?
    }

    @Ignore("Decreasing capacity not supported in new API.")
    @Override
    public void testMaxFastWritableBytes() {
    }

    @Ignore("Impossible to expose entire memory as a ByteBuffer using new API.")
    @Override
    public void testNioBufferExposeOnlyRegion() {
    }

    @Ignore("Impossible to expose entire memory as a ByteBuffer using new API.")
    @Override
    public void testToByteBuffer2() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testRetainedDuplicateUnreleasable3() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testRetainedDuplicateUnreleasable4() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testRetainedDuplicateAndRetainedSliceContentIsExpected() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testMultipleRetainedSliceReleaseOriginal2() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testMultipleRetainedSliceReleaseOriginal3() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testMultipleRetainedSliceReleaseOriginal4() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testReadRetainedSliceUnreleasable3() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testReadRetainedSliceUnreleasable4() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testRetainedSliceUnreleasable3() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testRetainedSliceUnreleasable4() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testRetainedSliceReleaseOriginal2() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testRetainedSliceReleaseOriginal3() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testRetainedSliceReleaseOriginal4() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testMultipleRetainedDuplicateReleaseOriginal2() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testMultipleRetainedDuplicateReleaseOriginal3() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testMultipleRetainedDuplicateReleaseOriginal4() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testRetainedDuplicateReleaseOriginal2() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testRetainedDuplicateReleaseOriginal3() {
    }

    @Ignore("This assumes a single reference count for the memory, but all buffers (views of memory) have " +
            "independent reference counts now. Also, this plays tricks with reference that we cannot support.")
    @Override
    public void testRetainedDuplicateReleaseOriginal4() {
    }

    @Ignore("No longer allowed to allocate 0 sized buffers, except for composite buffers with no components.")
    @Override
    public void testLittleEndianWithExpand() {
    }

    @Ignore("Test seems to inherently have double-free bug?")
    @Override
    public void testRetainedSliceAfterReleaseRetainedSliceDuplicate() {
    }

    @Ignore("Test seems to inherently have double-free bug?")
    @Override
    public void testRetainedSliceAfterReleaseRetainedDuplicateSlice() {
    }

    @Ignore("Test seems to inherently have double-free bug?")
    @Override
    public void testSliceAfterReleaseRetainedSliceDuplicate() {
    }

    @Ignore("Test seems to inherently have double-free bug?")
    @Override
    public void testDuplicateAfterReleaseRetainedSliceDuplicate() {
    }

    @Ignore("Test seems to inherently have double-free bug?")
    @Override
    public void testDuplicateAfterReleaseRetainedDuplicateSlice() {
    }

    @Ignore("Test seems to inherently have double-free bug?")
    @Override
    public void testSliceAfterReleaseRetainedDuplicateSlice() {
    }
}
