package tsidec.pairs;

import tsidec.core.Target;

import java.util.Arrays;

/**
 * 示例目标集合：真实支付系统里的密码学代码，每段都提供"泄露版"与"修复版"。
 *
 * 所有目标遵循同一约定：**两类输入只差秘密部分，公开部分逐位相同**。
 *   - MAC 比对   ：密钥/密文相同，只有"第几个字节开始不同"不同
 *   - 令牌校验   ：令牌等长，只有"第几个字符开始不同"不同
 *   - 填充检查   ：同长度密文，只有"合法填充长度"不同
 *   - 模幂       ：模数/底数相同，只有秘密指数的比特重量不同
 *   - 清零       ：缓冲区相同，只有秘密长度不同
 *
 * 每个实现都被**独立**检验"自己的耗时与路径是否随秘密变化"，
 * 而不是拿泄露版与修复版互相比时间——后者比较的是两段不同代码，没有意义。
 */
public final class SampleTargets {
    private SampleTargets() {}

    /** 生成第 cls 类秘密下的实参。 */
    @FunctionalInterface
    public interface Args {
        Object[] make(int secretClass, int iteration);
    }

    // ================================================================ 1. MAC 比对

    private static final byte[] MAC_EXPECTED = macExpected();
    private static byte[] macExpected() {
        byte[] b = new byte[64];
        Arrays.fill(b, (byte) 0xAB);
        return b;
    }

    /** 类 0：首字节就不同（最早返回）；类 1：仅末字节不同（最晚返回）。 */
    private static Object[] macArgs(int cls, int it) {
        byte[] actual = MAC_EXPECTED.clone();
        actual[cls == 0 ? 0 : MAC_EXPECTED.length - 1] ^= 1;
        return new Object[]{MAC_EXPECTED, actual};
    }

    public static Target macUnsafe() {
        return new Simple("MAC/签名比对（泄露版：首个不同字节即返回）",
                "tsidec.pairs.MacCompare", "unsafeEquals",
                new Class<?>[]{byte[].class, byte[].class},
                SampleTargets::macArgs,
                "密文中首个不匹配字节的位置：首字节 vs 末字节",
                Target.Expectation.LEAK);
    }

    public static Target macConstantTime() {
        return new Simple("MAC/签名比对（修复版：恒定时间比较）",
                "tsidec.pairs.MacCompare", "constantTimeEquals",
                new Class<?>[]{byte[].class, byte[].class},
                SampleTargets::macArgs,
                "密文中首个不匹配字节的位置：首字节 vs 末字节",
                Target.Expectation.NO_LEAK);
    }

    // ================================================================ 2. 令牌校验

    private static final String TOKEN = "S3cr3t-T0ken-Value-For-Payment-Service";

    private static Object[] tokenArgs(int cls, int it) {
        StringBuilder sb = new StringBuilder(TOKEN);
        int pos = cls == 0 ? 0 : TOKEN.length() - 1;
        sb.setCharAt(pos, (char) (sb.charAt(pos) ^ 1));
        return new Object[]{sb.toString(), TOKEN};
    }

    public static Target tokenUnsafe() {
        return new Simple("令牌/口令校验（泄露版：首个不匹配字符即返回）",
                "tsidec.pairs.TokenCheck", "unsafeToken",
                new Class<?>[]{String.class, String.class},
                SampleTargets::tokenArgs,
                "令牌中首个不匹配字符的位置：首字符 vs 末字符",
                Target.Expectation.LEAK);
    }

    public static Target tokenConstantTime() {
        return new Simple("令牌/口令校验（修复版：恒定时间）",
                "tsidec.pairs.TokenCheck", "constantTimeToken",
                new Class<?>[]{String.class, String.class},
                SampleTargets::tokenArgs,
                "令牌中首个不匹配字符的位置：首字符 vs 末字符",
                Target.Expectation.NO_LEAK);
    }

    /**
     * 仅比较长度的变体：两类密文长度不同（1 vs 16），比较逻辑本身恒定时间。
     * 这是"修复了内容比较、却漏掉长度泄露"的典型。本工具只检验固定长度下的
     * 差异，长度差异需要在输入构造里体现，因此这里用长度作为秘密。
     */
    public static Target tokenLengthUnsafe() {
        return new Simple("令牌长度分支（泄露版：长度不同走不同路径）",
                "tsidec.pairs.TokenCheck", "unsafeTokenWithLengthBranch",
                new Class<?>[]{String.class, String.class},
                (cls, it) -> {
                    // 秘密 = 两个字符串的公共前缀长度：0 vs 32
                    int common = cls == 0 ? 0 : 32;
                    String a = "A".repeat(common) + "X";
                    String b = "A".repeat(common) + "Y";
                    return new Object[]{a, b};
                },
                "首个不匹配字符的位置：第 1 个 vs 第 33 个",
                Target.Expectation.LEAK);
    }

    // ================================================================ 3. 填充检查

    /**
     * 类 0：末字节 0x01 → 合法 1 字节填充，循环 1 次。
     * 类 1：16 字节全 0x10 → 合法 16 字节填充，循环 16 次。
     * 两者都返回 true，工作量不同 —— padding oracle 的时间来源。
     */
    private static Object[] paddingArgs(int cls, int it) {
        byte[] data = new byte[16];
        Arrays.fill(data, (byte) 0x10);
        if (cls == 0) data[15] = 0x01;
        return new Object[]{data, 16};
    }

    public static Target paddingUnsafe() {
        return new Simple("PKCS#7 填充检查（泄露版：遇非法字节提前返回）",
                "tsidec.pairs.PaddingCheck", "unsafeValidPadding",
                new Class<?>[]{byte[].class, int.class},
                SampleTargets::paddingArgs,
                "合法填充长度：1 字节 vs 16 字节",
                Target.Expectation.LEAK);
    }

    public static Target paddingConstantTime() {
        return new Simple("PKCS#7 填充检查（修复版：恒定时间）",
                "tsidec.pairs.PaddingCheck", "constantTimeValidPadding",
                new Class<?>[]{byte[].class, int.class},
                SampleTargets::paddingArgs,
                "合法填充长度：1 字节 vs 16 字节",
                Target.Expectation.NO_LEAK);
    }

    // ================================================================ 4. 模幂

    /** 类 0：指数 = 1（1 次平方-乘）；类 1：指数 = 0x7FFFFFFF（31 次）。 */
    private static Object[] modexpArgs(int cls, int it) {
        int e = cls == 0 ? 1 : 0x7FFFFFFF;
        return new Object[]{65537, e, 1_000_000_007};
    }

    public static Target modexpUnsafe() {
        return new Simple("模幂/标量乘（泄露版：平方-乘，轮数依赖指数比特）",
                "tsidec.pairs.ScalarExp", "unsafeModExp",
                new Class<?>[]{int.class, int.class, int.class},
                SampleTargets::modexpArgs,
                "秘密指数的汉明重量：1 个 1 vs 31 个 1",
                Target.Expectation.LEAK);
    }

    /**
     * 半修复版：控制流已恒定，但用 {@code %} 归约。
     * 工具的预期结论是 LEAK_TIMING_ONLY —— 这是"时间侧信道"而非"控制流侧信道"，
     * 是最容易被"我们加了恒定时间循环"这种说法掩盖的一类残留泄漏。
     */
    public static Target modexpLadder() {
        return new Simple("模幂（半修复：阶梯恒定但 % 变延迟）",
                "tsidec.pairs.ScalarExp", "ladderModExp",
                new Class<?>[]{int.class, int.class, int.class},
                SampleTargets::modexpArgs,
                "秘密指数的汉明重量：1 个 1 vs 31 个 1",
                Target.Expectation.LEAK);
    }

    /**
     * 彻底修复版：定延迟移位归约。极慢（约为半修复版的 160 倍），
     * 用来验证工具在"非常慢但完全恒定"的函数上不会误报。
     */
    public static Target modexpFullyConstantTime() {
        return new Simple("模幂（彻底修复：定延迟移位归约，极慢）",
                "tsidec.pairs.ScalarExp", "fullyConstantTimeModExp",
                new Class<?>[]{int.class, int.class, int.class},
                SampleTargets::modexpArgs,
                "秘密指数的汉明重量：1 个 1 vs 31 个 1",
                Target.Expectation.NO_LEAK);
    }

    // ================================================================ 5. 清零 / 长度解析

    /**
     * 注意：两个类各自分配新数组，避免互相污染。
     * 池化调用会反复擦除同一数组，擦除后全 0，工作量仍是确定的。
     */
    private static Object[] wipeArgs(int cls, int it) {
        return new Object[]{new byte[256], cls == 0 ? 1 : 256};
    }

    public static Target wipeUnsafe() {
        return new Simple("敏感缓冲区清零（泄露版：按秘密长度清零）",
                "tsidec.pairs.WipeAndParse", "unsafeWipeLength",
                new Class<?>[]{byte[].class, int.class},
                SampleTargets::wipeArgs,
                "秘密长度：1 字节 vs 256 字节",
                Target.Expectation.LEAK);
    }

    public static Target wipeConstantTime() {
        return new Simple("敏感缓冲区清零（修复版：总是清零整块）",
                "tsidec.pairs.WipeAndParse", "constantTimeWipeLength",
                new Class<?>[]{byte[].class, int.class},
                SampleTargets::wipeArgs,
                "秘密长度：1 字节 vs 256 字节",
                Target.Expectation.NO_LEAK);
    }

    // ================================================================ 通用实现

    static final class Simple implements Target {
        private final String name;
        private final String className;
        private final String methodName;
        private final Class<?>[] params;
        private final Args args;
        private final String secret;
        private final Expectation expectation;

        Simple(String name, String className, String methodName, Class<?>[] params,
               Args args, String secret, Expectation expectation) {
            this.name = name; this.className = className; this.methodName = methodName;
            this.params = params; this.args = args; this.secret = secret;
            this.expectation = expectation;
        }

        @Override public String name() { return name; }
        @Override public String className() { return className; }
        @Override public String methodName() { return methodName; }
        @Override public Class<?>[] paramTypes() { return params; }
        @Override public Expectation expectation() { return expectation; }
        @Override public String secretDescription() { return secret; }
        @Override public Object[] argsFor(int secretClass, int i) { return args.make(secretClass, i); }
    }
}
