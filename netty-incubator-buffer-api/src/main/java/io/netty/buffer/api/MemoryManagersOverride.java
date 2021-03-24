/*
 * Copyright 2021 The Netty Project
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


import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

final class MemoryManagersOverride {
    private static final boolean PRINT_LOADING_DEFAULT_EXCEPTIONS =
            Boolean.getBoolean("io.netty.buffer.api.MemoryManagers.PRINT_LOADING_DEFAULT_EXCEPTIONS");
    private static final MemoryManagers DEFAULT = pickDefaultManagers();
    private static final AtomicInteger OVERRIDES_AVAILABLE = new AtomicInteger();
    private static final Map<Thread, MemoryManagers> OVERRIDES = Collections.synchronizedMap(new IdentityHashMap<>());

    private MemoryManagersOverride() {
    }

    static MemoryManagers pickDefaultManagers() {
        Optional<MemoryManagers> candidate = MemoryManagers.getAllManagers().flatMap(provider -> {
            try {
                return Stream.of(provider.get());
            } catch (ServiceConfigurationError error) {
                if (PRINT_LOADING_DEFAULT_EXCEPTIONS) {
                    //noinspection CallToPrintStackTrace
                    error.printStackTrace();
                }
                return Stream.empty();
            }
        }).findFirst();
        if (candidate.isPresent()) {
            return candidate.get();
        } else {
            throw new LinkageError("No implementations of " + MemoryManagers.class + " found.");
        }
    }

    static MemoryManagers getManagers() {
        if (OVERRIDES_AVAILABLE.get() > 0) {
            return OVERRIDES.getOrDefault(Thread.currentThread(), DEFAULT);
        }
        return DEFAULT;
    }

    static <T> T using(MemoryManagers managers, Supplier<T> supplier) {
        Thread thread = Thread.currentThread();
        OVERRIDES.put(thread, managers);
        OVERRIDES_AVAILABLE.incrementAndGet();
        try {
            return supplier.get();
        } finally {
            OVERRIDES_AVAILABLE.decrementAndGet();
            OVERRIDES.remove(thread);
        }
    }
}
