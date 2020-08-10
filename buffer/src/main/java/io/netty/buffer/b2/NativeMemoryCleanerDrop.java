package io.netty.buffer.b2;

import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;

import static io.netty.buffer.b2.Statics.*;
import static java.lang.invoke.MethodHandles.*;

class NativeMemoryCleanerDrop implements Drop<BBuf> {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final VarHandle CLEANABLE =
            findVarHandle(lookup(), NativeMemoryCleanerDrop.class, "cleanable", Cleanable.class);
    @SuppressWarnings("unused")
    private volatile Cleanable cleanable;

    @Override
    public void drop(BBuf buf) {
        Cleanable c = (Cleanable) CLEANABLE.getAndSet(this, null);
        if (c != null) {
            c.clean();
        }
    }

    @Override
    public void accept(BBuf buf) {
        drop(null); // Unregister old cleanable, if any, to avoid uncontrolled build-up.
        var segment = buf.segment;
        cleanable = CLEANER.register(this, () -> {
            if (segment.isAlive()) {
                // Clear owner so we can close from cleaner thread.
                overwriteMemorySegmentOwner(segment, null);
                segment.close();
                MEM_USAGE_NATIVE.add(-segment.byteSize());
            }
        });
    }
}
