package io.netty.buffer.b2;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScopeTest {
    @Test
    public void scopeMustCloseContainedRcsInReverseInsertOrder() {
        ArrayList<Integer> closeOrder = new ArrayList<>();
        try (Scope scope = new Scope()) {
            scope.add(new SomeRc(new OrderingDrop(1, closeOrder)));
            scope.add(new SomeRc(new OrderingDrop(2, closeOrder)));
            scope.add(new SomeRc(new OrderingDrop(3, closeOrder)));
        }
        var itr = closeOrder.iterator();
        assertTrue(itr.hasNext());
        assertEquals(3, (int) itr.next());
        assertTrue(itr.hasNext());
        assertEquals(2, (int) itr.next());
        assertTrue(itr.hasNext());
        assertEquals(1, (int) itr.next());
        assertFalse(itr.hasNext());
    }

    private static final class SomeRc extends RcSupport<SomeRc, SomeRc> {
        SomeRc(Drop<SomeRc> drop) {
            super(drop);
        }

        @Override
        protected Owned<SomeRc> prepareSend() {
            return null;
        }
    }

    private static final class OrderingDrop implements Drop<SomeRc> {
        private final int order;
        private final ArrayList<Integer> list;

        private OrderingDrop(int order, ArrayList<Integer> list) {
            this.order = order;
            this.list = list;
        }

        @Override
        public void drop(SomeRc obj) {
            list.add(order);
        }
    }
}