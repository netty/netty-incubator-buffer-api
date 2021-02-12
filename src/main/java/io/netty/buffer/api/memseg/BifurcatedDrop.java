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
package io.netty.buffer.api.memseg;

import io.netty.buffer.api.Drop;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

class BifurcatedDrop implements Drop<MemSegBuffer> {
    private static final VarHandle COUNT;
    static {
        try {
            COUNT = MethodHandles.lookup().findVarHandle(BifurcatedDrop.class, "count", int.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final MemSegBuffer originalBuf;
    private final Drop<MemSegBuffer> delegate;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int count;

    BifurcatedDrop(MemSegBuffer originalBuf, Drop<MemSegBuffer> delegate) {
        this.originalBuf = originalBuf;
        this.delegate = delegate;
        count = 2; // These are created by buffer bifurcation, so we initially have 2 references to this drop.
    }

    void increment() {
        int c;
        do {
            c = count;
            checkValidState(c);
        } while (!COUNT.compareAndSet(this, c, c + 1));
    }

    @Override
    public void drop(MemSegBuffer buf) {
        int c;
        int n;
        do {
            c = count;
            n = c - 1;
            checkValidState(c);
        } while (!COUNT.compareAndSet(this, c, n));
        if (n == 0) {
            delegate.attach(originalBuf);
            delegate.drop(originalBuf);
        }
        buf.makeInaccessible();
    }

    @Override
    public void attach(MemSegBuffer obj) {
        delegate.attach(obj);
    }

    Drop<MemSegBuffer> unwrap() {
        return delegate;
    }

    private static void checkValidState(int count) {
        if (count == 0) {
            throw new IllegalStateException("Underlying resources have already been freed.");
        }
    }
}
