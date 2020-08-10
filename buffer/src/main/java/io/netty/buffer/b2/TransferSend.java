package io.netty.buffer.b2;

import java.lang.invoke.VarHandle;

import static io.netty.buffer.b2.Statics.*;
import static java.lang.invoke.MethodHandles.*;

class TransferSend<T extends Rc<T>> implements Send<T> {
    private static final VarHandle RECEIVED = findVarHandle(lookup(), TransferSend.class, "received", boolean.class);
    private final T outgoing;
    private final Drop<T> drop;
    @SuppressWarnings("unused")
    private volatile boolean received; // Accessed via VarHandle

    TransferSend(T outgoing, Drop<T> drop) {
        this.outgoing = outgoing;
        this.drop = drop;
    }

    @Override
    public T receive() {
        if (!RECEIVED.compareAndSet(this, false, true)) {
            throw new IllegalStateException("This object has already been received.");
        }
        var copy = outgoing.copy(Thread.currentThread(), drop);
        drop.accept(copy);
        return copy;
    }
}
