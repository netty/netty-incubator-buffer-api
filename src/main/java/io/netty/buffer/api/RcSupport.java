/*
 * Copyright 2020 The Netty Project
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
package io.netty.buffer.api;

import java.util.Objects;

public abstract class RcSupport<I extends Rc<I>, T extends RcSupport<I, T>> implements Rc<I> {
    private int acquires; // Closed if negative.
    private Drop<T> drop;

    protected RcSupport(Drop<T> drop) {
        this.drop = drop;
    }

    /**
     * Increment the reference count.
     * <p>
     * Note, this method is not thread-safe because Rc's are meant to thread-confined.
     *
     * @return This Rc instance.
     */
    @Override
    public final I acquire() {
        if (acquires < 0) {
            throw new IllegalStateException("Resource is closed.");
        }
        if (acquires == Integer.MAX_VALUE) {
            throw new IllegalStateException("Cannot acquire more references; counter would overflow.");
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
    public final void close() {
        if (acquires == -1) {
            throw new IllegalStateException("Double-free: Resource already closed and dropped.");
        }
        if (acquires == 0) {
            drop.drop(impl());
        }
        acquires--;
    }

    /**
     * Send this Rc instance to another Thread, transferring the ownership to the recipient. This method can be used
     * when the receiving thread is not known up front.
     * <p>
     * This instance immediately becomes inaccessible, and all attempts at accessing this Rc will throw. Calling {@link
     * #close()} will have no effect, so this method is safe to call within a try-with-resources statement.
     *
     * @throws IllegalStateException if this object has any outstanding acquires; that is, if this object has been
     * {@link #acquire() acquired} more times than it has been {@link #close() closed}.
     */
    @Override
    public final Send<I> send() {
        if (!isOwned()) {
            throw notSendableException();
        }
        var owned = prepareSend();
        acquires = -2; // Close without dropping. This also ignore future double-free attempts.
        return new TransferSend<I, T>(owned, drop);
    }

    /**
     * Create an {@link IllegalStateException} with a custom message, tailored to this particular {@link Rc} instance,
     * for when the object cannot be sent for some reason.
     * @return An {@link IllegalStateException} to be thrown when this object cannot be sent.
     */
    protected IllegalStateException notSendableException() {
        return new IllegalStateException(
                "Cannot send() a reference counted object with " + acquires + " outstanding acquires: " + this + '.');
    }

    @Override
    public boolean isOwned() {
        return acquires == 0;
    }

    @Override
    public int countBorrows() {
        return Math.max(acquires, 0);
    }

    /**
     * Prepare this instance for ownsership transfer. This method is called from {@link #send()} in the sending thread.
     * This method should put this Rc in a deactivated state where it is no longer accessible from the currently owning
     * thread. In this state, the Rc instance should only allow a call to {@link Owned#transferOwnership(Drop)} in
     * the recipient thread.
     *
     * @return This Rc instance in a deactivated state.
     */
    protected abstract Owned<T> prepareSend();

    /**
     * Get access to the underlying {@link Drop} object.
     * This method is unsafe because it open the possibility of bypassing and overriding resource lifetimes.
     *
     * @return The {@link Drop} object used by this reference counted object.
     */
    protected Drop<T> unsafeGetDrop() {
        return drop;
    }

    /**
     * Replace the current underlying {@link Drop} object with the given one.
     * This method is unsafe because it open the possibility of bypassing and overring resource lifetimes.
     *
     * @param replacement The new {@link Drop} object to use instead of the current one.
     */
    protected void unsafeSetDrop(Drop<T> replacement) {
        drop = Objects.requireNonNull(replacement, "Replacement drop cannot be null.");
    }

    @SuppressWarnings("unchecked")
    private I self() {
        return (I) this;
    }

    @SuppressWarnings("unchecked")
    private T impl() {
        return (T) this;
    }
}
