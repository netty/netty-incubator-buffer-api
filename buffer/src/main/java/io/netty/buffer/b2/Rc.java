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

    /**
     * Transfer the ownership of this Rc, to the given recipient thread.
     * This Rc is invalidated but without disposing of its internal state.
     * Then a new Rc with the given owner is produced in its stead.
     * <p>
     * This method is called by {@link Send} implementations.
     * These implementations will ensure that the transfer of ownership (the calling of this
     * method) happens-before the new owner begins accessing the new object.
     * This ensures that the new Rc is safely published to the new owners.
     *
     * @param recipient The new owner of the state represented by this Rc.
     * @param drop The drop object that knows how to dispose of the state represented by this Rc.
     * @return A new Rc instance that is exactly the same as this Rc, except it has the new owner.
     */
    protected abstract T transferOwnership(Thread recipient, Drop<T> drop);

    protected T prepareSend() {
        return self();
    }

    @SuppressWarnings("unchecked")
    private T self() {
        return (T) this;
    }
}
