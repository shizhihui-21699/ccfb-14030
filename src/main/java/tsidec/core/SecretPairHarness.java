package tsidec.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

/**
 * 秘密变化型执行器：A 批用第 0 类秘密的输入，B 批用第 1 类秘密的输入。
 * 两批调用**同一个方法**，只有实参中承载秘密的部分不同。
 *
 * 这是工具检测"这个函数是否随秘密变化"的核心执行器：
 *   - 若耗时上有配对差 → 时间侧信道；
 *   - 结合插桩得到的基本块计数差 → 结构性泄漏证据。
 *
 * 性能要点：按返回类型分成三条**完全独立的**热循环（int/boolean 与 long），
 * 每条循环里只有 invokeExact 和整型加法，没有装箱、没有 instanceof。
 * 曾用 (Number) 强转统一处理，结果每个调用分配一个 Integer，
 * GC 长尾直接把噪声放大十倍——这正是要消灭的干扰源。
 */
public final class SecretPairHarness implements AlternatingHarness {
    private final Object[][] argsA;
    private final Object[][] argsB;
    private final long[] sink;
    private final int kind;

    private final MethodHandle hInt;      // 返回 int
    private final MethodHandle hLong;     // 返回 long
    private final MethodHandle hBoolean;  // 返回 boolean

    public SecretPairHarness(Target target, TargetBinder.Bound bound, long[] sink, int poolSize) {
        this(target, bound, sink, poolSize, 0, 1);
    }

    /**
     * @param classA 用作"A 批"的秘密类别
     * @param classB 用作"B 批"的秘密类别；== classA 时即零点对照（真值为 0）
     */
    public SecretPairHarness(Target target, TargetBinder.Bound bound, long[] sink,
                             int poolSize, int classA, int classB) {
        this.sink = sink;
        this.kind = bound.returnsIntLike;
        int n = Math.max(1, poolSize);
        this.argsA = new Object[n][];
        this.argsB = new Object[n][];
        for (int i = 0; i < n; i++) {
            argsA[i] = target.argsFor(classA, i);
            argsB[i] = target.argsFor(classB, i);
        }

        Class<?> rt = bound.method.getReturnType();
        // 关键：要把 (byte[],byte[])Z 变成可以统一调用的 (Object[])Z。
        // asType 做不了数组参数类型的转换，必须用 asSpreader(Object[].class, n)：
        // 它把 Object[] 的第 i 个元素拆出来，逐个 cast 成原始参数类型再调用。
        // 这个适配器会被 JIT 内联，实测无额外开销；而且不会分配——不装箱。
        int arity = target.paramTypes().length;
        MethodHandle spread = bound.handle.asType(
                        MethodType.methodType(rt, target.paramTypes()))
                .asSpreader(Object[].class, arity);
        MethodHandle typed = spread.asType(MethodType.methodType(rt, Object[].class));
        if (rt == boolean.class) {
            this.hBoolean = typed;
            this.hInt = null; this.hLong = null;
        } else if (rt == long.class) {
            this.hLong = typed;
            this.hInt = null; this.hBoolean = null;
        } else {
            this.hInt = typed;
            this.hLong = null; this.hBoolean = null;
        }
    }

    @Override
    public long[] round(int k, int seed, int which) {
        Object[][] fa = (which & 1) == 0 ? argsA : argsB;
        Object[][] fb = (which & 1) == 0 ? argsB : argsA;
        int n = argsA.length;

        long t0 = System.nanoTime();
        long acc1 = batchCall(fa, k, seed, n);
        long t1 = System.nanoTime();
        long acc2 = batchCall(fb, k, seed, n);
        long t2 = System.nanoTime();
        sink[0] += acc1 + acc2;

        long first = t1 - t0, second = t2 - t1;
        return (which & 1) == 0 ? new long[]{first, second} : new long[]{second, first};
    }

    private long batchCall(Object[][] pool, int k, int seed, int n) {
        int base = seed % n;
        if (kind == 1) {
            if (hBoolean != null) {
                int acc = 0;
                for (int i = 0; i < k; i++) {
                    try {
                        if ((boolean) hBoolean.invokeExact(pool[(base + i) % n])) acc++;
                    } catch (Throwable t) { throw new IllegalStateException(t); }
                }
                return acc;
            }
            int acc = 0;
            for (int i = 0; i < k; i++) {
                try {
                    acc += (int) hInt.invokeExact(pool[(base + i) % n]);
                } catch (Throwable t) { throw new IllegalStateException(t); }
            }
            return acc;
        } else {
            long acc = 0;
            for (int i = 0; i < k; i++) {
                try {
                    acc += (long) hLong.invokeExact(pool[(base + i) % n]);
                } catch (Throwable t) { throw new IllegalStateException(t); }
            }
            return acc;
        }
    }
}
