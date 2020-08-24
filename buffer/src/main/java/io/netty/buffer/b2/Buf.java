package io.netty.buffer.b2;

/**
 * A reference counted buffer API with separate reader and writer indexes.
 * @param <T> The concrete runtime buffer type.
 */
public interface Buf<T extends Buf<T>> extends Rc<T> {
    /**
     * Get the current reader index. The next read will happen from this byte index into the buffer.
     *
     * @return The current reader index.
     */
    int readerIndex();

    /**
     * Set the reader index. Make the next read happen from the given index.
     *
     * @param index The reader index to set.
     * @return This Buf.
     */
    T readerIndex(int index);

    /**
     * Get the current writer index. The next write will happen at this byte index into the byffer.
     *
     * @return The current writer index.
     */
    int writerIndex();

    /**
     * Set the writer index. Make the next write happen at the given index.
     *
     * @param index The writer index to set.
     * @return This Buf.
     */
    T writerIndex(int index);
}
