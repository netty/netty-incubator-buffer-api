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

import static io.netty.buffer.api.internal.Statics.findVarHandle;
import static java.lang.invoke.MethodHandles.lookup;

class TransferSend<I extends Rc<I>, T extends Rc<I>> implements Send<I> {
    private static final VarHandle RECEIVED = findVarHandle(lookup(), TransferSend.class, "received", boolean.class);
    private final Owned<T> outgoing;
    private final Drop<T> drop;
    private final Class<?> concreteType;
    @SuppressWarnings("unused")
    private volatile boolean received; // Accessed via VarHandle

    TransferSend(Owned<T> outgoing, Drop<T> drop, Class<?> concreteType) {
        this.outgoing = outgoing;
        this.drop = drop;
        this.concreteType = concreteType;
    }

    @SuppressWarnings("unchecked")
    @Override
    public I receive() {
        gateReception();
        var copy = outgoing.transferOwnership(drop);
        drop.attach(copy);
        return (I) copy;
    }

    Owned<T> unsafeUnwrapOwned() {
        gateReception();
        return outgoing;
    }

    private void gateReception() {
        if ((boolean) RECEIVED.getAndSet(this, true)) {
            throw new IllegalStateException("This object has already been received.");
        }
    }

    @Override
    public boolean referentIsInstanceOf(Class<?> cls) {
        return cls.isAssignableFrom(concreteType);
    }

    @Override
    public void discard() {
        if (!(boolean) RECEIVED.getAndSet(this, true)) {
            var copy = outgoing.transferOwnership(drop);
            drop.attach(copy);
            copy.close();
        }
    }
}
