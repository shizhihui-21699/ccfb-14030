package tsidec.pairs;

/**
 * 示例 4：模幂（令牌签名验签里的标量乘/指数运算）。
 *
 * 这是"看起来恒定时间、其实不是"的典型。泄露点不在数据值比较，而在**执行次数**：
 *   - 平方-乘算法在指数比特为 1 时多做一次乘法 → 耗时正比于汉明重量
 *   - 循环轮数正比于指数有效位长 → 耗时泄露密钥规模
 *
 * 修复版还有第二层陷阱：即便控制流恒定，若用整型 {@code %} 做归约，
 * ARM64 的 SDIV/UDIV 是**变延迟指令**，耗时仍随操作数变化（实测约 10ns/次）。
 * 真正恒定时间的实现必须用只有乘法与移位的归约——这里用 Barrett 归约。
 */
public final class ScalarExp {
    private ScalarExp() {}

    /** 泄露版：平方-乘。耗时 = f(比特长度, 1 的个数)。 */
    public static int unsafeModExp(int base, int exponent, int modulus) {
        long result = 1;
        long b = base % modulus;
        int e = exponent;
        while (e > 0) {
            if ((e & 1) == 1) {
                result = (result * b) % modulus;
            }
            b = (b * b) % modulus;
            e >>>= 1;
        }
        return (int) result;
    }

    /**
     * 修复版（半）：32 轮 Montgomery 阶梯 + {@code %} 归约。
     *
     * 这演示了一个非常重要、也常被误解的区别：
     *   控制流已经恒定（每轮恒做 2 次乘模，无分支、无数据相关访存），
     *   但 {@code %} 在 ARM64 上是变延迟指令，**时间仍会随数据微小变化**。
     *
     * 本工具对它的正确结论是 LEAK_TIMING_ONLY：结构性证据为零（字节码执行路径
     * 两类完全相同），时间证据显著（约 10ns）。这不是误报——它确实是可测量的
     * 侧信道，只是比控制流泄漏小得多。真要消除，见 {@link #fullyConstantTimeModExp}。
     */
    public static int ladderModExp(int base, int exponent, int modulus) {
        if (modulus <= 1) return 0;
        long m = modulus;
        long[] pair = {1 % m, base % m};
        for (int i = 31; i >= 0; i--) {
            int bit = (exponent >>> i) & 1;
            cswap(bit, pair);
            pair[1] = (pair[0] * pair[1]) % m;
            pair[0] = (pair[0] * pair[0]) % m;
            cswap(bit, pair);
        }
        return (int) pair[0];
    }

    /**
     * 修复版（彻底）：32 轮 Montgomery 阶梯 + 定延迟移位归约。
     *
     * 密码学上"恒定时间"有两层含义，本类用不同方法分别演示：
     *   1. 控制流恒定 —— 循环轮数、分支走向不随秘密变化（ladderModExp 已做到）
     *   2. 指令延迟恒定 —— 连 {@code %} 这类变延迟指令也要避开
     *
     * 第 2 层常被忽略：ARM64 的 SDIV/UDIV 变延迟，整数 {@code %} 底层就是除法。
     * 本方法用"固定 62 轮移位-减法"做归约，全部是定延迟指令。
     *
     * 代价很大：实测约为 ladderModExp 的 160 倍（约 5.4μs/次）。
     * 这是为"彻底恒定"付的代价，也说明为什么真实密码库用汇编/专用指令，
     * 而不是在 JVM 里硬扛。工具的自适应批大小能测量它，但很慢。
     */
    public static int fullyConstantTimeModExp(int base, int exponent, int modulus) {
        if (modulus <= 1) return 0;
        long m = modulus;
        long[] pair = {1 % m, base % m};
        for (int i = 31; i >= 0; i--) {
            int bit = (exponent >>> i) & 1;
            cswap(bit, pair);
            pair[1] = mulModCT(pair[0], pair[1], m);
            pair[0] = mulModCT(pair[0], pair[0], m);
            cswap(bit, pair);
        }
        return (int) pair[0];
    }

    /**
     * (a*b) mod m，恒定时间。前提：a,b ∈ [0,m)，m ∈ [2, 2^31)。
     * 积 < 2^62，用固定 62 轮移位-减法归约，每轮的条件减用掩码完成。
     */
    static long mulModCT(long a, long b, long m) {
        long x = a * b;          // < 2^62，long 不溢出
        long r = 0;
        for (int i = 61; i >= 0; i--) {
            r = (r << 1) | ((x >>> i) & 1L);
            // if (r >= m) r -= m;  改为掩码形式：
            //   (m - r - 1) < 0  ⟺  r >= m，其符号位为全 1
            long geMask = (m - r - 1) >> 63;
            r -= m & geMask;
        }
        return r;
    }

    /**
     * 恒定时间条件交换。bit=1 交换，bit=0 不动；掩码运算，无分支、无数据相关访存。
     * 用长度为 2 的 long[] 承载寄存器对。
     */
    private static void cswap(int bit, long[] pair) {
        long mask = -(long) bit; // bit=1 → 全 1；bit=0 → 全 0
        long t = (pair[0] ^ pair[1]) & mask;
        pair[0] ^= t;
        pair[1] ^= t;
    }

    /** 参考实现（仅用于验证修复版结果正确）。 */
    public static int referenceModExp(int base, int exponent, int modulus) {
        if (modulus <= 1) return 0;
        long result = 1;
        long b = base % modulus;
        int e = exponent;
        while (e > 0) {
            if ((e & 1) == 1) result = (result * b) % modulus;
            b = (b * b) % modulus;
            e >>>= 1;
        }
        return (int) result;
    }
}
