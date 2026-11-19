package tsidec.selfcheck;

import tsidec.core.AlternatingHarness;

/**
 * 自校准执行器：不依赖任何候选代码，把同一份代码克隆成 A/B，
 * 用 {@code extraOps} 控制 B 比 A 多做几条**依赖** ALU 指令。
 *
 * 用途：正式测量前，在同一进程/同一负载下测出
 *   (a) 零点偏置 bias0（A/B 逐字同构，真值 0）
 *   (b) 校准斜率（每多一条依赖指令，配对差增加多少 ns）
 * 从而使判定从"p 值小就算泄漏"升级为"效应量是否超出零点偏置 + 最小效应量"。
 *
 * 这是应对即时编译最直接的手段：校准用的 A/B 与真实目标走完全相同的批内循环
 * 与调用形态。若 JIT 把差异优化掉，斜率会变成 0，工具据此报"校准失效"，
 * 而不是给出假阴性。
 *
 * 三个踩过的坑（同样适用于被测代码，故留注释）：
 *  1. A/B 必须逐字同构。曾把两者合并成 batch(buf,k,extra)，内联决策不对称，
 *     凭空产生 1.9ns/次 固定伪影，把 0.015ns 的真实斜率完全掩盖。
 *  2. 分派必须在循环外。switch 留在循环体内会让热点循环每次迭代多一次
 *     不可常量折叠的分支，噪声放大 20 倍。
 *  3. null 探针必须真同构。base() vs plus(x,0) 仍是两个不同方法，
 *     实测残留 0.032ns/次偏置；改成两个逐字相同的 baseA/baseB 后降到 0.001ns。
 */
final class CalibrationHarness implements AlternatingHarness {
    private final int extraOps;
    private final long[] sink;
    /** 预计算输入，只在构造时写一次；1<<20 个 int = 4MB，超出 L2，访存模式稳定。 */
    private final int[] inputs;
    private int cursor;

    CalibrationHarness(int extraOps, long[] sink) {
        if (extraOps != 0 && extraOps != 1 && extraOps != 2 && extraOps != 4 && extraOps != 8) {
            throw new IllegalArgumentException("extraOps must be 0,1,2,4,8");
        }
        this.extraOps = extraOps;
        this.sink = sink;
        this.inputs = new int[1 << 20];
        for (int i = 0; i < inputs.length; i++) inputs[i] = i * 0x9E3779B1;
    }

    // A 侧
    static int baseA(int x) {
        int r = x * 0x9E3779B1;
        r ^= r >>> 15;
        r += 0x85EBCA6B;
        r ^= r >>> 13;
        return r;
    }

    // B 侧：与 baseA 逐字相同
    static int baseB(int x) {
        int r = x * 0x9E3779B1;
        r ^= r >>> 15;
        r += 0x85EBCA6B;
        r ^= r >>> 13;
        return r;
    }

    static int plus1(int x) { int r = baseB(x); r = r * 3 + 1; return r; }
    static int plus2(int x) { int r = baseB(x); r = r * 3 + 1; r = r * 5 + 3; return r; }
    static int plus4(int x) {
        int r = baseB(x);
        r = r * 3 + 1; r = r * 3 + 1; r = r * 5 + 3; r = r * 3 + 1;
        return r;
    }
    static int plus8(int x) {
        int r = baseB(x);
        r = r * 3 + 1; r = r * 3 + 1; r = r * 5 + 3; r = r * 3 + 1;
        r = r * 3 + 1; r = r * 5 + 3; r = r * 3 + 1; r = r * 3 + 1;
        return r;
    }

    private static int batchA(int[] b, int off, int k) {
        int acc = 0;
        for (int i = 0; i < k; i++) acc += baseA(b[off + i]);
        return acc;
    }

    /** 分派在循环外；每个 case 的循环体与 batchA 逐字同构。 */
    private static int batchB(int[] b, int off, int k, int extra) {
        int acc = 0;
        switch (extra) {
            case 0: for (int i = 0; i < k; i++) acc += baseB(b[off + i]); break;
            case 1: for (int i = 0; i < k; i++) acc += plus1(b[off + i]); break;
            case 2: for (int i = 0; i < k; i++) acc += plus2(b[off + i]); break;
            case 4: for (int i = 0; i < k; i++) acc += plus4(b[off + i]); break;
            case 8: for (int i = 0; i < k; i++) acc += plus8(b[off + i]); break;
            default: throw new IllegalArgumentException();
        }
        return acc;
    }

    @Override
    public long[] round(int k, int seed, int which) {
        // 窗口必须完整落在 inputs 内：用显式取模，不用位掩码。
        // 位掩码 (len - k - 1) 只有在 len-k 是 2 的幂时才给出合法下标，
        // 实测在 K=2048 时会截断出越界窗口，令校准噪声暴涨 10 倍、偏置不稳定。
        int span = inputs.length - k;
        int off = (int) Math.floorMod((long) seed * 7919 + cursor, span);
        cursor = (cursor + k) % Math.max(1, span);

        long a, b;
        if ((which & 1) == 0) {
            long t0 = System.nanoTime();
            int acc = batchA(inputs, off, k);
            long t1 = System.nanoTime();
            acc += batchB(inputs, off, k, extraOps);
            long t2 = System.nanoTime();
            sink[0] += acc;
            a = t1 - t0; b = t2 - t1;
        } else {
            long t0 = System.nanoTime();
            int acc = batchB(inputs, off, k, extraOps);
            long t1 = System.nanoTime();
            acc += batchA(inputs, off, k);
            long t2 = System.nanoTime();
            sink[0] += acc;
            b = t1 - t0; a = t2 - t1;
        }
        return new long[]{a, b};
    }
}
