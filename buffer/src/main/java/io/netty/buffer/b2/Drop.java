package io.netty.buffer.b2;

import java.util.function.Consumer;

/**
 * The Drop interface is used by {@link Rc} instances to implement their resource disposal mechanics. The {@link
 * #drop(Object)} method will be called by the Rc when their last reference is closed.
 *
 * @param <T>
 */
@FunctionalInterface
public interface Drop<T> extends Consumer<T> {
    /**
     * Dispose of the resources in the given Rc.
     *
     * @param obj The Rc instance being dropped.
     */
    void drop(T obj);

    /**
     * Called when the resource changes owner.
     *
     * @param obj The new Rc instance with the new owner.
     */
    @Override
    default void accept(T obj) {
    }
}
