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
module netty.incubator.buffer.api {
    requires io.netty.common;
    requires io.netty.buffer;

    // Optional dependencies, needed for some of the examples.
    requires static java.logging;

    exports io.netty.buffer.api;

    exports io.netty.buffer.api.internal to
            netty.incubator.buffer.api.memseg,
            netty.incubator.buffer.api.bytebuffer;

    uses io.netty.buffer.api.MemoryManagers;

    // Permit reflective access to non-public members.
    // Also means we don't have to make all test methods etc. public for JUnit to access them.
    opens io.netty.buffer.api;
}