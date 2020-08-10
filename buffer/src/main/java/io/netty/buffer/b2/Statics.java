package io.netty.buffer.b2;

import io.netty.util.internal.PlatformDependent;
import jdk.incubator.foreign.MemorySegment;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.LongAdder;

interface Statics {
    @SuppressWarnings("InstantiatingAThreadWithDefaultRunMethod")
    Thread TRANSFER_OWNER = new Thread("ByteBuf Transfer Owner");
    long SCOPE = fieldOffset("jdk.internal.foreign.AbstractMemorySegmentImpl", "scope");
    long OWNER = fieldOffset("jdk.internal.foreign.MemoryScope", "owner");
    LongAdder MEM_USAGE_NATIVE = new LongAdder();

    static VarHandle findVarHandle(Lookup lookup, Class<?> recv, String name, Class<?> type) {
        try {
            return lookup.findVarHandle(recv, name, type);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static long fieldOffset(String className, String fieldName) {
        try {
            Class<?> cls = Class.forName(className);
            Field field = cls.getDeclaredField(fieldName);
            return PlatformDependent.objectFieldOffset(field);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static void overwriteMemorySegmentOwner(MemorySegment segment, Thread newOwner) {
        Object scope = PlatformDependent.getObject(segment, SCOPE);
        PlatformDependent.putObject(scope, OWNER, newOwner);
        VarHandle.fullFence(); // Attempt to force visibility of overwritten final fields.
    }
}
