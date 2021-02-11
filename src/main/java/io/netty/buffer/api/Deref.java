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

import java.util.function.Supplier;

/**
 * A Deref provides the capability to acquire a reference to a {@linkplain Rc reference counted} object.
 * <p>
 * <strong>Note:</strong> Callers must ensure that they close any references they obtain.
 * <p>
 * Deref itself does not specify if a reference can be obtained more than once.
 * For instance, any {@link Send} object is also a {@code Deref}, but the reference can only be acquired once.
 * Meanwhile, {@link Rc} objects are themselves their own {@code Derefs}, and permit references to be acquired multiple
 * times.
 *
 * @param <T> The concrete type of reference counted object that can be obtained.
 */
public interface Deref<T extends Rc<T>> extends Supplier<T> {
    /**
     * Acquire a reference to the reference counted object.
     * <p>
     * <strong>Note:</strong> This call increments the reference count of the acquired object, and must be paired with
     * a {@link Rc#close()} call.
     * Using a try-with-resources clause is the easiest way to ensure this.
     *
     * @return A reference to the reference counted object.
     */
    @Override
    T get();

    /**
     * Determine if the object in this {@code Deref} is an instance of the given class.
     *
     * @param cls The type to check.
     * @return {@code true} if the object in this {@code Deref} can be assigned fields or variables of the given type.
     */
    boolean isInstanceOf(Class<?> cls);
}
