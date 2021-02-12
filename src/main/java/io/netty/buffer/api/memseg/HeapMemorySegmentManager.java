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

import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.Drop;
import jdk.incubator.foreign.MemorySegment;

public class HeapMemorySegmentManager extends AbstractMemorySegmentManager {
    @Override
    public boolean isNative() {
        return false;
    }

    @Override
    protected MemorySegment createSegment(long size) {
        return MemorySegment.ofArray(new byte[Math.toIntExact(size)]);
    }

    @Override
    public Drop<Buffer> drop() {
        return convert(buf -> buf.makeInaccessible());
    }

    @SuppressWarnings({ "unchecked", "UnnecessaryLocalVariable" })
    private static Drop<Buffer> convert(Drop<MemSegBuffer> drop) {
        Drop<?> tmp = drop;
        return (Drop<Buffer>) tmp;
    }
}
