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

    @Ignore("new buffers not thread-safe like this")
    @Override
    public void testSliceReadGatheringByteChannelMultipleThreads() throws Exception {
    }

    @Ignore("new buffers not thread-safe like this")
    @Override
    public void testDuplicateReadGatheringByteChannelMultipleThreads() throws Exception {
    }

    @Ignore("new buffers not thread-safe like this")
    @Override
    public void testSliceReadOutputStreamMultipleThreads() throws Exception {
    }

    @Ignore("new buffers not thread-safe like this")
    @Override
    public void testDuplicateReadOutputStreamMultipleThreads() throws Exception {
    }

    @Ignore("new buffers not thread-safe like this")
    @Override
    public void testSliceBytesInArrayMultipleThreads() throws Exception {
    }

    @Ignore("new buffers not thread-safe like this")
    @Override
    public void testDuplicateBytesInArrayMultipleThreads() throws Exception {
    }
}
