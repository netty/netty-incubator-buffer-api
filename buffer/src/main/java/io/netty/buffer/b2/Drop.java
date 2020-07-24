package io.netty.buffer.b2;

@FunctionalInterface
public interface Drop<T extends Rc<T>> {
    void drop(T obj);
}
