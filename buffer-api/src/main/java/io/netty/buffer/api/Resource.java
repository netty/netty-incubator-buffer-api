package io.netty.buffer.api;

/**
 * A resource that has a life-time, and can be {@linkplain #close() closed}.
 * Resources are initially {@linkplain #isAccessible() accessible}, but closing them makes them inaccessible.
 */
public interface Resource<T extends Resource<T>> extends AutoCloseable {
    /**
     * Send this object instance to another Thread, transferring the ownership to the recipient.
     * <p>
     * The object must be in a state where it can be sent, which includes at least being
     * {@linkplain #isAccessible() accessible}.
     * <p>
     * When sent, this instance will immediately become inaccessible, as if by {@linkplain #close() closing} it.
     * All attempts at accessing an object that has been sent, even if that object has not yet been received, should
     * cause an exception to be thrown.
     * <p>
     * Calling {@link #close()} on an object that has been sent will have no effect, so this method is safe to call
     * within a try-with-resources statement.
     */
    Send<T> send();

    /**
     * Close the resource, making it inaccessible.
     * <p>
     * Note, this method is not thread-safe unless otherwise specific.
     *
     * @throws IllegalStateException If this {@code Resource} has already been closed.
     */
    @Override
    void close();

    /**
     * Check if this object is accessible.
     *
     * @return {@code true} if this object is still valid and can be accessed,
     * otherwise {@code false} if, for instance, this object has been dropped/deallocated,
     * or been {@linkplain #send() sent} elsewhere.
     */
    boolean isAccessible();
}
