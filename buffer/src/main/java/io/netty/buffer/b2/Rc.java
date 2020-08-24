package io.netty.buffer.b2;

import java.util.function.Consumer;

/**
 * An Rc is a reference counted, thread-confined, resource of sorts. Because these resources are thread-confined, the
 * reference counting is NOT atomic. An Rc can only be accessed by one thread at a time - the owner thread that the
 * resource is confined to.
 * <p>
 * When the last reference is closed (accounted for using {@link AutoCloseable} and try-with-resources statements,
 * ideally), then the resource is desposed of, or released, or returned to the pool it came from. The precise action is
 * implemented by the {@link Drop} instance given as an argument to the Rc constructor.
 *
 * @param <T> The concrete subtype.
 */
public abstract class Rc<T extends Rc<T>> implements AutoCloseable {
    private int acquires; // Closed if negative.
    private final Drop<T> drop;

    Rc(Drop<T> drop) {
        this.drop = drop;
    }

    /**
     * Increment the reference count.
     * <p>
     * Note, this method is not thread-safe because Rc's are meant to thread-confined.
     *
     * @return This Rc instance.
     */
    public T acquire() {
        if (acquires < 0) {
            throw new IllegalStateException("Resource is closed.");
        }
        acquires++;
        return self();
    }

    /**
     * Decrement the reference count, and despose of the resource if the last reference is closed.
     * <p>
     * Note, this method is not thread-safe because Rc's are meant to be thread-confined.
     *
     * @throws IllegalStateException If this Rc has already been closed.
     */
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

    /**
     * Send this Rc instance ot another Thread, transferring the ownsership fo the recipient, using a rendesvouz
     * protocol. This method can be used when the sender wishes to block until the transfer completes. This requires
     * that both threads be alive an running for the transfer to complete.
     *
     * @param consumer The consumer encodes the mechanism by which the recipient recieves the Rc instance.
     * @throws InterruptedException If this thread was interrupted
     */
    public void sendTo(Consumer<Send<T>> consumer) throws InterruptedException {
        var send = new RendezvousSend<>(self(), drop);
        consumer.accept(send);
        send.finish();
        acquires = -2; // close without dropping (also ignore future double-free attempts)
    }

    /**
     * Send this Rc instance to another Thread, transferring the ownership to the recipient. This method can be used
     * when the receiving thread is not known up front.
     * <p>
     * This instance immediately becomes inaccessible, and all attempts at accessing this Rc will throw. Calling {@link
     * #close()} will have no effect, so this method is safe to call within a try-with-resources statement.
     *
     * @implNote Not possible without hacks because we need the receiving thread in order to set the new owner in the
     * currently owning thread.
     */
    public Send<T> send() {
        acquires = -2; // close without dropping (also ignore future double-free attempts)
        return new TransferSend<>(prepareSend(), drop);
    }

    /**
     * Transfer the ownership of this Rc, to the given recipient thread. This Rc is invalidated but without disposing of
     * its internal state. Then a new Rc with the given owner is produced in its stead.
     * <p>
     * This method is called by {@link Send} implementations. These implementations will ensure that the transfer of
     * ownership (the calling of this method) happens-before the new owner begins accessing the new object. This ensures
     * that the new Rc is safely published to the new owners.
     *
     * @param recipient The new owner of the state represented by this Rc.
     * @param drop      The drop object that knows how to dispose of the state represented by this Rc.
     * @return A new Rc instance that is exactly the same as this Rc, except it has the new owner.
     */
    protected abstract T transferOwnership(Thread recipient, Drop<T> drop);

    /**
     * Prepare this instance for ownsership transfer. This method is called from {@link #send()} in the sending thread.
     * This method should put this Rc in a deactivated state where it is no longer accessible from the currently owning
     * thread. In this state, the Rc instance should only allow a call to {@link #transferOwnership(Thread, Drop)} in
     * the recipient thread.
     *
     * @return This Rc instance in a deactivated state.
     */
    protected T prepareSend() {
        return self();
    }

    @SuppressWarnings("unchecked")
    private T self() {
        return (T) this;
    }
}
