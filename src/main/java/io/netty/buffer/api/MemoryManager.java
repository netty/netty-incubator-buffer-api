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

import io.netty.buffer.api.memseg.HeapMemorySegmentManager;
import io.netty.buffer.api.memseg.NativeMemorySegmentManager;

import java.lang.ref.Cleaner;

public interface MemoryManager {
    static MemoryManager getHeapMemoryManager() {
        return new HeapMemorySegmentManager();
    }

    static MemoryManager getNativeMemoryManager() {
        return new NativeMemorySegmentManager();
    }

    boolean isNative();
    Buf allocateConfined(AllocatorControl alloc, long size, Drop<Buf> drop, Cleaner cleaner);
    Buf allocateShared(AllocatorControl allo, long size, Drop<Buf> drop, Cleaner cleaner);
    Drop<Buf> drop();
    Object unwrapRecoverableMemory(Buf buf);
    Buf recoverMemory(Object recoverableMemory, Drop<Buf> drop);
}
