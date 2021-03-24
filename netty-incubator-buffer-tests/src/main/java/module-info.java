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
module netty.incubator.buffer.tests {
    requires netty.incubator.buffer.api;
    requires netty.incubator.buffer.api.adaptor;
    requires netty.incubator.buffer.api.bytebuffer;
    requires netty.incubator.buffer.api.memseg;

    requires io.netty.common;
    requires io.netty.buffer;
    requires io.netty.handler;
    requires io.netty.transport;
    requires io.netty.codec;
    requires io.netty.codec.http;

    requires org.junit.jupiter.api;
    requires org.junit.jupiter.params;
    requires org.assertj.core;
    requires junit;
    requires jmh.core;
    requires jmh.generator.annprocess;

    requires jdk.incubator.foreign;

    // Optional dependencies, needed for some of the examples.
    requires static java.logging;
}