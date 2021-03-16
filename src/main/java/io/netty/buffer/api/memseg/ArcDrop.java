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

final class ArcDrop implements Drop<MemSegBuffer> {
    private static final VarHandle COUNT;
    static {
        try {
            COUNT = MethodHandles.lookup().findVarHandle(ArcDrop.class, "count", int.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Drop<MemSegBuffer> delegate;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int count;

    ArcDrop(Drop<MemSegBuffer> delegate) {
        this.delegate = delegate;
        count = 1;
    }

    static Drop<MemSegBuffer> wrap(Drop<MemSegBuffer> drop) {
        if (drop.getClass() == ArcDrop.class) {
            return drop;
        }
        return new ArcDrop(drop);
    }

    static Drop<MemSegBuffer> acquire(Drop<MemSegBuffer> drop) {
        if (drop.getClass() == ArcDrop.class) {
            ((ArcDrop) drop).increment();
            return drop;
        }
        return new ArcDrop(drop);
    }

    ArcDrop increment() {
        int c;
        do {
            c = count;
            checkValidState(c);
        } while (!COUNT.compareAndSet(this, c, c + 1));
        return this;
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
            delegate.drop(buf);
        }
    }

    @Override
    public void attach(MemSegBuffer obj) {
        delegate.attach(obj);
    }

    boolean isOwned() {
        return count <= 1;
    }

    int countBorrows() {
        return count - 1;
    }

    Drop<MemSegBuffer> unwrap() {
        return delegate;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder().append("ArcDrop(").append(count).append(", ");
        Drop<MemSegBuffer> drop = this;
        while ((drop = ((ArcDrop) drop).unwrap()) instanceof ArcDrop) {
            builder.append(((ArcDrop) drop).count).append(", ");
        }
        return builder.append(drop).append(')').toString();
    }

    private static void checkValidState(int count) {
        if (count == 0) {
            throw new IllegalStateException("Underlying resources have already been freed.");
        }
    }
}
