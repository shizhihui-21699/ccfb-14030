package tsidec.pairs;

import java.security.MessageDigest;

/**
 * 示例 1：MAC/签名比对。
 *
 * 这是审计最常见的发现。两个版本在"功能上完全等价"（都返回是否相等），
 * 差别只在比较中途能否提前返回。
 *
 * 为了让被测代码不被 JIT 在没有观测点时整个消掉，方法都返回结果，
 * 由 harness 把结果累加进 sink——与真实支付系统里"比较结果决定后续分支"一致。
 */
public final class MacCompare {
    private MacCompare() {}

    /**
     * 泄露版：发现第一个不同字节立即返回。
     * 攻击者构造 expected 的前缀逐步匹配，观察响应时间即可逐字节恢复标签。
     */
    public static boolean unsafeEquals(byte[] expected, byte[] actual) {
        if (expected.length != actual.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) return false;
        }
        return true;
    }

    /**
     * 修复版：恒定时间比较。先累积所有字节的异或，最后一次性判断。
     * 无论第几位不同，都恰好遍历全部长度。
     */
    public static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        if (expected.length != actual.length) return false;
        int diff = 0;
        for (int i = 0; i < expected.length; i++) {
            diff |= expected[i] ^ actual[i];
        }
        return diff == 0;
    }

    /**
     * 反例（危险修复）：用 MessageDigest.isEqual。
     * 在 OpenJDK 上它内部已是恒定时间实现，作为"第三方修复"示例；
     * 但很多旧 JDK / 其他实现并非恒定时间，所以工具必须能自己测出来。
     */
    public static boolean jdkIsEqual(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }
}
