package io.netty.buffer.b2;

/**
 * A Send object is a temporary holder of an {@link Rc}, used for transferring the ownership of the Rc from one thread
 * to another.
 * <p>
 * Prior to the Send being created, the originating Rc is invalidated, to prevent access while it is being sent. This
 * means it cannot be accessed, closed, or disposed of, while it is in-flight. Once the Rc is {@linkplain #receive()
 * received}, the new ownership is established.
 * <p>
 * Care must be taken to ensure that the Rc is always received by some thread. Failure to do so can result in a resource
 * leak.
 *
 * @param <T>
 */
@FunctionalInterface
public interface Send<T extends Rc<T>> {
    /**
     * Receive the {@link Rc} instance being sent, and bind its ownership to the calling thread. The invalidation of the
     * sent Rc in the sending thread happens-before the return of this method.
     * <p>
     * This method can only be called once, and will throw otherwise.
     *
     * @return The sent Rc instance.
     * @throws IllegalStateException If this method is called more than once.
     */
    T receive();
}
