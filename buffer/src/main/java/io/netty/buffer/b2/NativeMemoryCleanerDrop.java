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

import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;

import static io.netty.buffer.b2.Statics.*;
import static java.lang.invoke.MethodHandles.*;

class NativeMemoryCleanerDrop implements Drop<BBuf> {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final VarHandle CLEANABLE =
            findVarHandle(lookup(), NativeMemoryCleanerDrop.class, "cleanable", Cleanable.class);
    @SuppressWarnings("unused")
    private volatile Cleanable cleanable;

    @Override
    public void drop(BBuf buf) {
        Cleanable c = (Cleanable) CLEANABLE.getAndSet(this, null);
        if (c != null) {
            c.clean();
        }
    }

    @Override
    public void accept(BBuf buf) {
        drop(null); // Unregister old cleanable, if any, to avoid uncontrolled build-up.
        var segment = buf.segment;
        cleanable = CLEANER.register(this, () -> {
            if (segment.isAlive()) {
                // TODO return segment to pool, or call out to external drop, instead of closing it directly.
                segment.close();
                MEM_USAGE_NATIVE.add(-segment.byteSize());
            }
        });
    }
}
