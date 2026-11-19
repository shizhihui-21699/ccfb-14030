package tsidec.core;

/**
 * 被测函数在运行时的绑定信息：通过反射拿到真实返回类型，再构造对应的 MethodHandle。
 * 与 {@link Target#paramTypes()} 配套使用。
 */
public final class TargetBinder {
    private TargetBinder() {}

    public static final class Bound {
        public final Class<?> declaringClass;
        public final java.lang.reflect.Method method;
        public final java.lang.invoke.MethodHandle handle;
        public final int returnsIntLike; // 1 = boolean/int/short/byte, 2 = long, 0 = 其它（不支持）
        Bound(Class<?> c, java.lang.reflect.Method m, java.lang.invoke.MethodHandle h, int kind) {
            declaringClass = c; method = m; handle = h; returnsIntLike = kind;
        }
    }

    public static Bound bind(Target target, ClassLoader loader) {
        try {
            Class<?> k = Class.forName(target.className(), true, loader);
            java.lang.reflect.Method m = k.getDeclaredMethod(target.methodName(), target.paramTypes());
            m.setAccessible(true);
            java.lang.invoke.MethodType mt = java.lang.invoke.MethodType.methodType(
                    m.getReturnType(), target.paramTypes());
            java.lang.invoke.MethodHandle h = java.lang.invoke.MethodHandles.lookup()
                    .unreflect(m).asType(mt);
            int kind;
            Class<?> rt = m.getReturnType();
            if (rt == boolean.class || rt == int.class || rt == short.class || rt == byte.class) kind = 1;
            else if (rt == long.class) kind = 2;
            else kind = 0;
            if (kind == 0) throw new IllegalArgumentException(
                    "tool only supports int/boolean/long-returning targets, got " + rt);
            return new Bound(k, m, h, kind);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot bind target " + target.name(), e);
        }
    }
}
