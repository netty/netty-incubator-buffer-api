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

import java.lang.invoke.VarHandle;
import java.util.Objects;

import static io.netty.buffer.api.Statics.findVarHandle;
import static java.lang.invoke.MethodHandles.lookup;

/**
 * The {@link BufHolder} is an abstract class that simplifies the implementation of objects that themselves contain
 * a {@link Buf} instance.
 * <p>
 * The {@link BufHolder} can only hold on to a single buffer, so objects and classes that need to hold on to multiple
 * buffers will have to do their implementation from scratch, though they can use the code of the {@link BufHolder} as
 * inspiration.
 * <p>
 * If you just want an object that is a reference to a buffer, then the {@link BufRef} can be used for that purpose.
 * If you have an advanced use case where you wish to implement {@link Rc}, and tightly control lifetimes, then
 * {@link RcSupport} can be of help.
 *
 * @param <T> The concrete {@link BufHolder} type.
 */
public abstract class BufHolder<T extends BufHolder<T>> implements Rc<T> {
    private static final VarHandle BUF = findVarHandle(lookup(), BufHolder.class, "buf", Buf.class);
    private Buf buf;

    /**
     * Create a new {@link BufHolder} to hold the given {@linkplain Buf buffer}.
     * <p>
     * <strong>Note:</strong> this increases the reference count of the given buffer.
     *
     * @param buf The {@linkplain Buf buffer} to be held by this holder.
     */
    protected BufHolder(Buf buf) {
        this.buf = Objects.requireNonNull(buf, "The buffer cannot be null.").acquire();
    }

    /**
     * Create a new {@link BufHolder} to hold the {@linkplain Buf buffer} received from the given {@link Send}.
     * <p>
     * The {@link BufHolder} will then be holding exclusive ownership of the buffer.
     *
     * @param send The {@linkplain Buf buffer} to be held by this holder.
     */
    protected BufHolder(Send<Buf> send) {
        buf = Objects.requireNonNull(send, "The send cannot be null.").receive();
    }

    @SuppressWarnings("unchecked")
    @Override
    public T acquire() {
        buf.acquire();
        return (T) this;
    }

    @Override
    public void close() {
        buf.close();
    }

    @Override
    public boolean isOwned() {
        return buf.isOwned();
    }

    @Override
    public int countBorrows() {
        return buf.countBorrows();
    }

    @SuppressWarnings("unchecked")
    @Override
    public Send<T> send() {
        return buf.send().map((Class<T>) getClass(), this::receive);
    }

    /**
     * Called when a {@linkplain #send() sent} {@link BufHolder} is received by the recipient.
     * The {@link BufHolder} should return a new concrete instance, that wraps the given {@link Buf} object.
     *
     * @param buf The {@link Buf} that is {@linkplain Send#receive() received} by the recipient,
     *           and needs to be wrapped in a new {@link BufHolder} instance.
     * @return A new {@linkplain T buffer holder} instance, containing the given {@linkplain Buf buffer}.
     */
    protected abstract T receive(Buf buf);

    /**
     * Replace the underlying referenced buffer with the given buffer.
     * <p>
     * This method is protected to permit advanced use cases of {@link BufHolder} sub-class implementations.
     * <p>
     * <strong>Note:</strong> this method decreases the reference count of the current buffer,
     * and increases the reference count of the new buffer.
     * <p>
     * The buffer assignment is performed using a plain store.
     *
     * @param newBuf The new {@link Buf} instance that is replacing the currently held buffer.
     */
    protected final void replaceBuf(Buf newBuf) {
        try (var ignore = buf) {
            buf = newBuf.acquire();
        }
    }

    /**
     * Replace the underlying referenced buffer with the given buffer.
     * <p>
     * This method is protected to permit advanced use cases of {@link BufHolder} sub-class implementations.
     * <p>
     * <strong>Note:</strong> this method decreases the reference count of the current buffer,
     * and takes exclusive ownership of the sent buffer.
     * <p>
     * The buffer assignment is performed using a plain store.
     *
     * @param send The new {@link Buf} instance that is replacing the currently held buffer.
     */
    protected final void replaceBuf(Send<Buf> send) {
        try (var ignore = buf) {
            buf = send.receive();
        }
    }

    /**
     * Replace the underlying referenced buffer with the given buffer.
     * <p>
     * This method is protected to permit advanced use cases of {@link BufHolder} sub-class implementations.
     * <p>
     * <strong>Note:</strong> this method decreases the reference count of the current buffer,
     * and increases the reference count of the new buffer.
     * <p>
     * The buffer assignment is performed using a volatile store.
     *
     * @param newBuf The new {@link Buf} instance that is replacing the currently held buffer.
     */
    protected final void replaceBufVolatile(Buf newBuf) {
        var prev = (Buf) BUF.getAndSet(this, newBuf.acquire());
        prev.close();
    }

    /**
     * Replace the underlying referenced buffer with the given buffer.
     * <p>
     * This method is protected to permit advanced use cases of {@link BufHolder} sub-class implementations.
     * <p>
     * <strong>Note:</strong> this method decreases the reference count of the current buffer,
     * and takes exclusive ownership of the sent buffer.
     * <p>
     * The buffer assignment is performed using a volatile store.
     *
     * @param send The {@link Send} with the new {@link Buf} instance that is replacing the currently held buffer.
     */
    protected final void replaceBufVolatile(Send<Buf> send) {
        var prev = (Buf) BUF.getAndSet(this, send.receive());
        prev.close();
    }

    /**
     * Access the held {@link Buf} instance.
     * <p>
     * The access is performed using a plain load.
     *
     * @return The {@link Buf} instance being held by this {@linkplain T buffer holder}.
     */
    protected final Buf getBuf() {
        return buf;
    }

    /**
     * Access the held {@link Buf} instance.
     * <p>
     * The access is performed using a volatile load.
     *
     * @return The {@link Buf} instance being held by this {@linkplain T buffer holder}.
     */
    protected final Buf getBufVolatile() {
        return (Buf) BUF.getVolatile(this);
    }
}
