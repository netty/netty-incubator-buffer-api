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

import java.io.Serial;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

abstract class LifecycleTracer {
    public static LifecycleTracer get() {
        if (Trace.TRACE_LIFECYCLE_DEPTH == 0) {
            return new NoOpTracer();
        }
        StackTracer stackTracer = new StackTracer();
        stackTracer.addTrace(StackTracer.WALKER.walk(new Trace("allocate", 0)));
        return stackTracer;
    }

    abstract void acquire(int acquires);

    abstract void drop(int acquires);

    abstract void close(int acquires);

    abstract <I extends Rc<I>, T extends RcSupport<I, T>> Owned<T> send(Owned<T> send, int acquires);

    abstract <E extends Throwable> E attachTrace(E throwable);

    private static final class NoOpTracer extends LifecycleTracer {
        @Override
        void acquire(int acquires) {
        }

        @Override
        void drop(int acquires) {
        }

        @Override
        void close(int acquires) {
        }

        @Override
        <I extends Rc<I>, T extends RcSupport<I, T>> Owned<T> send(Owned<T> send, int acquires) {
            return send;
        }

        @Override
        <E extends Throwable> E attachTrace(E throwable) {
            return throwable;
        }
    }

    private static final class StackTracer extends LifecycleTracer {
        private static final int MAX_TRACE_POINTS = Math.min(Integer.getInteger("MAX_TRACE_POINTS", 50), 1000);
        private static final StackWalker WALKER;
        static {
            int depth = Trace.TRACE_LIFECYCLE_DEPTH;
            WALKER = depth > 0 ? StackWalker.getInstance(Set.of(), depth + 2) : null;
        }

        private final ArrayDeque<Trace> traces = new ArrayDeque<>();
        private boolean dropped;

        @Override
        void acquire(int acquires) {
            Trace trace = WALKER.walk(new Trace("acquire", acquires));
            addTrace(trace);
        }

        void addTrace(Trace trace) {
            synchronized (traces) {
                if (traces.size() == MAX_TRACE_POINTS) {
                    traces.pollFirst();
                }
                traces.addLast(trace);
            }
        }

        @Override
        void drop(int acquires) {
            dropped = true;
            addTrace(WALKER.walk(new Trace("drop", acquires)));
        }

        @Override
        void close(int acquires) {
            if (!dropped) {
                addTrace(WALKER.walk(new Trace("close", acquires)));
            }
        }

        @Override
        <I extends Rc<I>, T extends RcSupport<I, T>> Owned<T> send(Owned<T> send, int acquires) {
            Trace sendTrace = new Trace("send", acquires);
            sendTrace.sent = true;
            addTrace(WALKER.walk(sendTrace));
            return new Owned<T>() {
                @Override
                public T transferOwnership(Drop<T> drop) {
                    sendTrace.received = WALKER.walk(new Trace("received", acquires));
                    return send.transferOwnership(drop);
                }
            };
        }

        @Override
        <E extends Throwable> E attachTrace(E throwable) {
            synchronized (traces) {
                long timestamp = System.nanoTime();
                for (Trace trace : traces) {
                    trace.attach(throwable, timestamp);
                }
            }
            return throwable;
        }
    }

    private static final class Trace implements Function<Stream<StackWalker.StackFrame>, Trace> {
        private static final int TRACE_LIFECYCLE_DEPTH;
        static {
            int traceDefault = 0;
            //noinspection AssertWithSideEffects
//            assert (traceDefault = 10) > 0;
            TRACE_LIFECYCLE_DEPTH = Math.max(Integer.getInteger("TRACE_LIFECYCLE_DEPTH", traceDefault), 0);
        }

        final String name;
        final int acquires;
        final long timestamp;
        boolean sent;
        volatile Trace received;
        StackWalker.StackFrame[] frames;

        Trace(String name, int acquires) {
            this.name = name;
            this.acquires = acquires;
            timestamp = System.nanoTime();
        }

        @Override
        public Trace apply(Stream<StackWalker.StackFrame> frames) {
            this.frames = frames.limit(TRACE_LIFECYCLE_DEPTH + 1).toArray(StackWalker.StackFrame[]::new);
            return this;
        }

        public <E extends Throwable> void attach(E throwable, long timestamp) {
            Trace recv = received;
            String message = sent && recv == null? name + " (sent but not received)" : name;
            message += " (current acquires = " + acquires + ") T" + (this.timestamp - timestamp) / 1000 + "µs.";
            Traceback exception = new Traceback(message);
            StackTraceElement[] stackTrace = new StackTraceElement[frames.length];
            for (int i = 0; i < frames.length; i++) {
                stackTrace[i] = frames[i].toStackTraceElement();
            }
            exception.setStackTrace(stackTrace);
            if (recv != null) {
                recv.attach(exception, timestamp);
            }
            throwable.addSuppressed(exception);
        }
    }

    private static final class Traceback extends Throwable {
        @Serial
        private static final long serialVersionUID = 941453986194634605L;

        Traceback(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
