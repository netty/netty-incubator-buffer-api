package io.netty.buffer.b2;

@FunctionalInterface
public interface Send<T extends Rc<T>> {
    T receive();
}
