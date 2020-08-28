/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer.b2;

import java.util.function.Consumer;

public abstract class RcSupport<I extends Rc<I>, T extends RcSupport<I, T>> implements Rc<I>, Owned<T> {
    private int acquires; // Closed if negative.
    private final Drop<T> drop;

    RcSupport(Drop<T> drop) {
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
    public I acquire() {
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
            drop.drop(impl());
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
    @Override
    public void sendTo(Consumer<Send<I>> consumer) throws InterruptedException {
        var send = new RendezvousSend<I, T>(impl(), drop);
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
    @Override
    public Send<I> send() {
        acquires = -2; // close without dropping (also ignore future double-free attempts)
        return new TransferSend<I, T>(prepareSend(), drop);
    }

    /**
     * Prepare this instance for ownsership transfer. This method is called from {@link #send()} in the sending thread.
     * This method should put this Rc in a deactivated state where it is no longer accessible from the currently owning
     * thread. In this state, the Rc instance should only allow a call to {@link #transferOwnership(Thread, Drop)} in
     * the recipient thread.
     *
     * @return This Rc instance in a deactivated state.
     */
    protected T prepareSend() {
        return impl();
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
