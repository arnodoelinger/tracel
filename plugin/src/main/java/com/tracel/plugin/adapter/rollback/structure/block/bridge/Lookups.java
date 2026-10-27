package com.tracel.plugin.adapter.rollback.structure.block.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Lookup helpers shared by the handle groups.
 */
final class Lookups {
    private Lookups() { }

    static MethodHandle virtual(MethodHandles.Lookup lookup, Class<?> owner, String name, MethodType type)
            throws ReflectiveOperationException {
        return erase(lookup.findVirtual(owner, name, type));
    }

    static MethodHandle statik(MethodHandles.Lookup lookup, Class<?> owner, String name, MethodType type)
            throws ReflectiveOperationException {
        return erase(lookup.findStatic(owner, name, type));
    }

    static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> owner, String name, Class<?> type)
            throws ReflectiveOperationException {
        return erase(lookup.findGetter(owner, name, type));
    }

    static MethodHandle constructor(MethodHandles.Lookup lookup, Class<?> owner, Class<?>... parameters)
            throws ReflectiveOperationException {
        return erase(lookup.findConstructor(owner, MethodType.methodType(void.class, parameters)));
    }

    /**
     * The field when this server has it, else {@code null}.
     */
    static MethodHandle optionalFlag(MethodHandles.Lookup lookup, Class<?> owner, String name) {
        try {
            return getter(lookup, owner, name, boolean.class);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object enumConst(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    private static MethodHandle erase(MethodHandle handle) {
        return handle.asType(handle.type().erase());
    }
}
