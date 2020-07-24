package io.netty.buffer.b2;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CountDownLatch;

import static io.netty.buffer.b2.Statics.*;
import static java.lang.invoke.MethodHandles.*;

class RendezvousSend<T extends Rc<T>> implements Send<T> {
    private static final VarHandle RECEIVED = findVarHandle(lookup(), RendezvousSend.class, "received", boolean.class);
    private final CountDownLatch recipientLatch;
    private final CountDownLatch sentLatch;
    private final Drop<T> drop;
    private final T outgoing;
    @SuppressWarnings("unused")
    private volatile boolean received; // Accessed via VarHandle
    private volatile Thread recipient;
    private volatile T incoming;

    RendezvousSend(T outgoing, Drop<T> drop) {
        this.outgoing = outgoing;
        this.drop = drop;
        recipientLatch = new CountDownLatch(1);
        sentLatch = new CountDownLatch(1);
    }

    @Override
    public T receive() {
        if (!RECEIVED.compareAndSet(this, false, true)) {
            throw new IllegalStateException("This object has already been received.");
        }
        recipient = Thread.currentThread();
        recipientLatch.countDown();
        try {
            sentLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return incoming;
    }

    void finish() throws InterruptedException {
        if (incoming != null) {
            throw new IllegalStateException("Already sent.");
        }
        recipientLatch.await();
        incoming = outgoing.copy(recipient, drop);
        sentLatch.countDown();
    }
}
