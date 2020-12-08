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

public class BifurcatedDrop<T> implements Drop<T> {
    private final T originalBuf;
    private final Drop<T> delegate;
    private int count;
    private Exception closeTrace;

    public BifurcatedDrop(T originalBuf, Drop<T> delegate) {
        this.originalBuf = originalBuf;
        this.delegate = delegate;
        count = 2; // These are created by buffer bifurcation, so we initially have 2 references to this drop.
    }

    public synchronized void increment() {
        checkValidState();
        count++;
    }

    @Override
    public synchronized void drop(T obj) {
        checkValidState();
        if (--count == 0) {
            closeTrace = new Exception("close: " + delegate);
            delegate.reconnect(originalBuf);
            delegate.drop(originalBuf);
        }
    }

    @Override
    public void reconnect(T obj) {
        delegate.reconnect(obj);
    }

    Drop<T> unwrap() {
        return delegate;
    }

    private void checkValidState() {
        if (count == 0) {
            throw new IllegalStateException("Underlying resources have already been freed.", closeTrace);
        }
    }
}
