package io.netty.buffer.b2;

import io.netty.util.internal.PlatformDependent;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

interface Statics {
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
}
