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
package io.netty.buffer.b2;

/**
 * An Rc is a reference counted, thread-confined, resource of sorts. Because these resources are thread-confined, the
 * reference counting is NOT atomic. An Rc can only be accessed by one thread at a time - the owner thread that the
 * resource is confined to.
 * <p>
 * When the last reference is closed (accounted for using {@link AutoCloseable} and try-with-resources statements,
 * ideally), then the resource is desposed of, or released, or returned to the pool it came from. The precise action is
 * implemented by the {@link Drop} instance given as an argument to the Rc constructor.
 *
 * @param <I> The concrete subtype.
 */
public interface Rc<I extends Rc<I>> extends AutoCloseable {
    /**
     * Increment the reference count.
     * <p>
     * Note, this method is not thread-safe because Rc's are meant to thread-confined.
     *
     * @return This Rc instance.
     */
    I acquire();

    /**
     * Decrement the reference count, and despose of the resource if the last reference is closed.
     * <p>
     * Note, this method is not thread-safe because Rc's are meant to be thread-confined.
     *
     * @throws IllegalStateException If this Rc has already been closed.
     */
    @Override
    void close();

    /**
     * Send this Rc instance to another Thread, transferring the ownership to the recipient.
     * <p>
     * This instance immediately becomes inaccessible, and all attempts at accessing this Rc will throw. Calling {@link
     * #close()} will have no effect, so this method is safe to call within a try-with-resources statement.
     */
    Send<I> send();
}
