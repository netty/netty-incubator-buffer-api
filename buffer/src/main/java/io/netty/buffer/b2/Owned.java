/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer.b2;

/**
 * This interface encapsulates the ownership of an {@link Rc}, and exposes a method that may be used to transfer this
 * ownership to the specified recipient thread.
 *
 * @param <T> The concrete type of {@link Rc} that is owned.
 */
@SuppressWarnings("InterfaceMayBeAnnotatedFunctional")
public interface Owned<T> {
    /**
     * Transfer the ownership of the owned Rc, to the given recipient thread. The owned Rc is invalidated but without
     * disposing of its internal state. Then a new Rc with the given owner is produced in its stead.
     * <p>
     * This method is called by {@link Send} implementations. These implementations will ensure that the transfer of
     * ownership (the calling of this method) happens-before the new owner begins accessing the new object. This ensures
     * that the new Rc is safely published to the new owners.
     *
     * @param recipient The new owner of the state represented by this Rc.
     * @param drop      The drop object that knows how to dispose of the state represented by this Rc.
     * @return A new Rc instance that is exactly the same as this Rc, except it has the new owner.
     */
    T transferOwnership(Thread recipient, Drop<T> drop);
}
