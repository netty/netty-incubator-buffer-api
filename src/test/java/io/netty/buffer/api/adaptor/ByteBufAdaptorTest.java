package io.netty.buffer.api.adaptor;

import io.netty.buffer.AbstractByteBufTest;
import io.netty.buffer.ByteBuf;
import org.junit.AfterClass;
import org.junit.BeforeClass;

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
}
