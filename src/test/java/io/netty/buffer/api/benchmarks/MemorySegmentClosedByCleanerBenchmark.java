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
package io.netty.buffer.api.benchmarks;

import io.netty.buffer.api.Allocator;
import io.netty.buffer.api.Buf;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.CompletableFuture.completedFuture;

@Warmup(iterations = 30, time = 1)
@Measurement(iterations = 30, time = 1)
@Fork(value = 5, jvmArgsAppend = { "-XX:+UnlockDiagnosticVMOptions", "-XX:+DebugNonSafepoints" })
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class MemorySegmentClosedByCleanerBenchmark {
    private static final Allocator direct = Allocator.direct();
    private static final Allocator withCleaner = Allocator.directWithCleaner();
    private static final Allocator directPooled = Allocator.pooledDirect();
    private static final Allocator pooledWithCleaner = Allocator.pooledDirectWithCleaner();

    @Param({"heavy", "light"})
    public String workload;
    public boolean isHeavy;

    @Setup
    public void setUp() {
        if ("heavy".equals(workload)) {
            isHeavy = true;
        } else if ("light".equals(workload)) {
            isHeavy = false;
        } else {
            throw new IllegalArgumentException("Unsupported workload: " + workload);
        }
    }

    @Benchmark
    public Buf explicitClose() throws Exception {
        try (Buf buf = process(direct.allocate(256))) {
            return buf;
        }
    }

    @Benchmark
    public Buf explicitPooledClose() throws Exception {
        try (Buf buf = process(directPooled.allocate(256))) {
            return buf;
        }
    }

    @Benchmark
    public Buf cleanerClose() throws Exception {
        return process(withCleaner.allocate(256));
    }

    @Benchmark
    public Buf cleanerClosePooled() throws Exception {
        return process(pooledWithCleaner.allocate(256));
    }

    @Benchmark
    public Buf pooledWithCleanerExplicitClose() throws Exception {
        try (Buf buf = pooledWithCleaner.allocate(256)) {
            return process(buf);
        }
    }

    private Buf process(Buf buffer) throws Exception {
        // Simulate some async network server thingy, processing the buffer.
        var tlr = ThreadLocalRandom.current();
        if (isHeavy) {
            return completedFuture(buffer.send()).thenApplyAsync(send -> {
                try (Buf buf = send.receive()) {
                    while (buf.writableBytes() > 0) {
                        buf.writeByte((byte) tlr.nextInt());
                    }
                    return buf.send();
                }
            }).thenApplyAsync(send -> {
                try (Buf buf = send.receive()) {
                    byte b = 0;
                    while (buf.readableBytes() > 0) {
                        b += buf.readByte();
                    }
                    buf.fill(b);
                    return buf.send();
                }
            }).get().receive();
        } else {
            while (buffer.writableBytes() > 0) {
                buffer.writeByte((byte) tlr.nextInt());
            }
            byte b = 0;
            while (buffer.readableBytes() > 0) {
                b += buffer.readByte();
            }
            buffer.fill(b);
            return buffer;
        }
    }
}
