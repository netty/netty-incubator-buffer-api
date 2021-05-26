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
package io.netty.buffer.api.internal;

import io.netty.buffer.api.Drop;
import io.netty.buffer.api.Owned;
import io.netty.buffer.api.Resource;
import io.netty.buffer.api.Send;

import java.util.Objects;

/**
 * Internal support class for resources.
 *
 * @param <I> The public interface for the resource.
 * @param <T> The concrete implementation of the resource.
 */
public abstract class ResourceSupport<I extends Resource<I>, T extends ResourceSupport<I, T>> implements Resource<I> {
    private int acquires; // Closed if negative.
    private Drop<T> drop;
    private final LifecycleTracer tracer;

    protected ResourceSupport(Drop<T> drop) {
        this.drop = drop;
        tracer = LifecycleTracer.get();
    }

    /**
     * Increment the reference count.
     * <p>
     * Note, this method is not thread-safe because Resources are meant to thread-confined.
     *
     * @return This {@link Resource} instance.
     */
    public final I acquire() {
        if (acquires < 0) {
            throw attachTrace(new IllegalStateException("This resource is closed: " + this + '.'));
        }
        if (acquires == Integer.MAX_VALUE) {
            throw new IllegalStateException("Cannot acquire more references; counter would overflow.");
        }
        acquires++;
        tracer.acquire(acquires);
        return self();
    }

    /**
     * Decrement the reference count, and despose of the resource if the last reference is closed.
     * <p>
     * Note, this method is not thread-safe because Resources are meant to be thread-confined.
     *
     * @throws IllegalStateException If this Resource has already been closed.
     */
    @Override
    public final void close() {
        if (acquires == -1) {
            throw attachTrace(new IllegalStateException("Double-free: Resource already closed and dropped."));
        }
        if (acquires == 0) {
            tracer.drop(acquires);
            drop.drop(impl());
        }
        acquires--;
        tracer.close(acquires);
    }

    /**
     * Send this Resource instance to another Thread, transferring the ownership to the recipient.
     * This method can be used when the receiving thread is not known up front.
     * <p>
     * This instance immediately becomes inaccessible, and all attempts at accessing this resource will throw.
     * Calling {@link #close()} will have no effect, so this method is safe to call within a try-with-resources
     * statement.
     *
     * @throws IllegalStateException if this object has any outstanding acquires; that is, if this object has been
     * {@link #acquire() acquired} more times than it has been {@link #close() closed}.
     */
    @Override
    public final Send<I> send() {
        if (!isOwned()) {
            throw notSendableException();
        }
        var owned = tracer.send(prepareSend(), acquires);
        acquires = -2; // Close without dropping. This also ignore future double-free attempts.
        return new TransferSend<I, T>(owned, drop, getClass());
    }

    protected <E extends Throwable> E attachTrace(E throwable) {
        return tracer.attachTrace(throwable);
    }

    /**
     * Create an {@link IllegalStateException} with a custom message, tailored to this particular
     * {@link Resource} instance, for when the object cannot be sent for some reason.
     * @return An {@link IllegalStateException} to be thrown when this object cannot be sent.
     */
    protected IllegalStateException notSendableException() {
        return new IllegalStateException(
                "Cannot send() a reference counted object with " + countBorrows() + " borrows: " + this + '.');
    }

    public boolean isOwned() {
        return acquires == 0;
    }

    /**
     * Count the number of borrows of this object.
     * Note that even if the number of borrows is {@code 0}, this object might not be {@linkplain #isOwned() owned}
     * because there could be other restrictions involved in ownership.
     *
     * @return The number of borrows, if any, of this object.
     */
    public int countBorrows() {
        return Math.max(acquires, 0);
    }

    @Override
    public boolean isAccessible() {
        return acquires >= 0;
    }

    /**
     * Prepare this instance for ownsership transfer. This method is called from {@link #send()} in the sending thread.
     * This method should put this resource in a deactivated state where it is no longer accessible from the currently
     * owning thread.
     * In this state, the resource instance should only allow a call to {@link Owned#transferOwnership(Drop)} in the
     * recipient thread.
     *
     * @return This resource instance in a deactivated state.
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
