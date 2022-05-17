/*
 * Copyright 2022 The Netty Project
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

import io.netty.buffer.api.internal.Statics;

final class ReduceNativeMemoryUsage implements Runnable {
    private final long size;

    ReduceNativeMemoryUsage(long size) {
        this.size = size;
    }

    @Override
    public void run() {
        Statics.MEM_USAGE_NATIVE.add(-size);
    }

    @Override
    public String toString() {
        return "ReduceNativeMemoryUsage(by " + size + " bytes)";
    }
}