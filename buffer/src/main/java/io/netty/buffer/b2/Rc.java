package io.netty.buffer.b2;

import java.util.function.Consumer;

public abstract class Rc<T extends Rc<T>> implements AutoCloseable {
    private int acquires; // closed if negative
    private final Drop<T> drop;

    Rc(Drop<T> drop) {
        this.drop = drop;
    }

    public T acquire() {
        if (acquires < 0) {
            throw new IllegalStateException("Resource is closed.");
        }
        acquires++;
        return self();
    }

    @Override
    public void close() {
        if (acquires == -1) {
            throw new IllegalStateException("Double-free: Already closed and dropped.");
        }
        if (acquires == 0) {
            drop.drop(self());
        }
        acquires--;
    }

    public void sendTo(Consumer<Send<T>> consumer) throws InterruptedException {
        var send = new RendezvousSend<>(self(), drop);
        consumer.accept(send);
        send.finish();
        acquires = -2; // close without dropping (also ignore future double-free attempts)
    }

    /**
     * @implNote Not possible without hacks because we need the receiving thread in order to set the new owner in the
     * currently owning thread.
     */
    public Send<T> send() {
        acquires = -2; // close without dropping (also ignore future double-free attempts)
        return new TransferSend<>(prepareSend(), drop);
    }

    protected abstract T copy(Thread recipient, Drop<T> drop);

    protected T prepareSend() {
        return self();
    }

    @SuppressWarnings("unchecked")
    private T self() {
        return (T) this;
    }
}
