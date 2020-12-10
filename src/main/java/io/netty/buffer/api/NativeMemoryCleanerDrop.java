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
import java.lang.ref.Cleaner.Cleanable;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.netty.buffer.api.Statics.CLEANER;
import static io.netty.buffer.api.Statics.findVarHandle;
import static java.lang.invoke.MethodHandles.lookup;

class NativeMemoryCleanerDrop implements Drop<Buf> {
    private static final VarHandle CLEANABLE =
            findVarHandle(lookup(), NativeMemoryCleanerDrop.class, "cleanable", GatedCleanable.class);
    private final SizeClassedMemoryPool pool;
    private final MemoryManager manager;
    private final Drop<Buf> delegate;
    @SuppressWarnings("unused")
    private volatile GatedCleanable cleanable;

    NativeMemoryCleanerDrop(SizeClassedMemoryPool pool, MemoryManager manager,
                            Drop<Buf> delegate) {
        this.pool = pool;
        this.manager = manager;
        this.delegate = delegate;
    }

    @Override
    public void drop(Buf buf) {
        GatedCleanable c = (GatedCleanable) CLEANABLE.getAndSet(this, null);
        if (c != null) {
            c.clean();
        }
    }

    @Override
    public void attach(Buf buf) {
        // Unregister old cleanable, if any, to avoid uncontrolled build-up.
        GatedCleanable c = (GatedCleanable) CLEANABLE.getAndSet(this, null);
        if (c != null) {
            c.disable();
            c.clean();
        }

        var pool = this.pool;
        var mem = manager.unwrapRecoverableMemory(buf);
        var delegate = this.delegate;
        WeakReference<Buf> ref = new WeakReference<>(buf);
        AtomicBoolean gate = new AtomicBoolean(true);
        cleanable = new GatedCleanable(gate, CLEANER.register(this, () -> {
            if (gate.getAndSet(false)) {
                Buf b = ref.get();
                if (b == null) {
                    pool.recoverMemory(mem);
                } else {
                    delegate.drop(b);
                }
            }
        }));
    }

    private static class GatedCleanable implements Cleanable {
        private final AtomicBoolean gate;
        private final Cleanable cleanable;

        GatedCleanable(AtomicBoolean gate, Cleanable cleanable) {
            this.gate = gate;
            this.cleanable = cleanable;
        }

        public void disable() {
            gate.set(false);
        }

        @Override
        public void clean() {
            cleanable.clean();
        }
    }
}
