package io.netty.buffer.b2;

import java.util.function.Consumer;

@FunctionalInterface
public interface Drop<T extends Rc<T>> extends Consumer<T> {
    void drop(T obj);

    @Override
    default void accept(T t) {
    }
}
