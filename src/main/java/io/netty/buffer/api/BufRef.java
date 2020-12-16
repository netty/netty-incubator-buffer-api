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

/**
 * A mutable reference to a buffer.
 */
public final class BufRef extends BufHolder<BufRef> {
    /**
     * Create a reference to the given {@linkplain Buf buffer}.
     * This increments the reference count of the buffer.
     *
     * @param buf The buffer to reference.
     */
    public BufRef(Buf buf) {
        super(buf);
        // BufRef is meant to be atomic, so we need to add a fence to get the semantics of a volatile store.
        VarHandle.fullFence();
    }

    /**
     * Create a reference that holds the exclusive ownership of the sent buffer.
     *
     * @param send The {@linkplain Send sent} buffer to take ownership of.
     */
    public BufRef(Send<Buf> send) {
        super(send);
        // BufRef is meant to be atomic, so we need to add a fence to get the semantics of a volatile store.
        VarHandle.fullFence();
    }

    @Override
    protected BufRef receive(Buf buf) {
        return new BufRef(buf);
    }

    @Override
    public void replaceVolatile(Buf newBuf) {
        super.replaceVolatile(newBuf);
    }

    @Override
    public void replaceVolatile(Send<Buf> send) {
        super.replaceVolatile(send);
    }

    /**
     * Access the buffer in this reference.
     *
     * @return The buffer held by the reference.
     */
    public Buf contents() {
        return getBufVolatile();
    }
}
