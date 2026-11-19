package tsidec.pairs;

/**
 * 示例 3：PKCS#7 / 分组密码填充检查。
 *
 * 经典的 "padding oracle" 场景。两个版本都在解密后检查填充，但泄露版的
 * 循环在第一个非法字节处提前退出，且对不同非法位置做不同处理，
 * 使"末尾有多少个合法填充字节"可以被时间差区分。
 */
public final class PaddingCheck {
    private PaddingCheck() {}

    /**
     * 泄露版：从末尾向前扫描，遇到第一个 != pad 的字节立即返回 false。
     * 合法填充长度越长，循环走得越远 → 时间随秘密（明文末尾字节）变化。
     */
    public static boolean unsafeValidPadding(byte[] data, int blockSize) {
        if (data.length == 0) return false;
        int pad = data[data.length - 1] & 0xFF;
        if (pad == 0 || pad > blockSize || pad > data.length) return false;
        for (int i = 0; i < pad; i++) {
            if ((data[data.length - 1 - i] & 0xFF) != pad) return false;
        }
        return true;
    }

    /**
     * 修复版：无论 pad 是多少，**总是**检查整块 blockSize 个字节。
     *
     * 注意这里不能写 {@code for (i = 0; i < pad; i++)} —— 那正是泄露版的写法，
     * 循环边界直接暴露秘密。正确做法是把"第 i 个字节是否是合法填充字节"表达成
     * 一个与秘密无关的掩码谓词，并把它并进累加器。
     *
     * 对每个位置 i（0-indexed，从末尾倒数）：
     *   该位置属于填充区  ⟺  i < pad
     *   该位置的字节值应为  pad
     * 两者都用算术/位运算表达，控制流与内存访问只取决于公开的 blockSize。
     */
    public static boolean constantTimeValidPadding(byte[] data, int blockSize) {
        if (data.length == 0) return false;
        int pad = data[data.length - 1] & 0xFF;
        int bad = (pad == 0 ? 1 : 0)
                | (pad > blockSize ? 1 : 0)
                | (pad > data.length ? 1 : 0);
        int span = Math.min(blockSize, data.length);
        for (int i = 0; i < span; i++) {          // 边界只依赖公开的 span
            int v = data[data.length - 1 - i] & 0xFF;
            // i < pad  ⟺  (pad - i - 1) >= 0。算术右移 31 位得到符号位：
            //   非负 → 0，负数 → -1(=0xFFFFFFFF)。再取反得掩码。
            int inPadMask = ~((pad - i - 1) >> 31);
            // (v ^ pad) == 0 的恒定时间表达：z 为 0 时 ((-z) >>> 31)=0；
            // 这里用 (z | -z) 的符号位判断 z 是否为 0，完全无分支。
            int z = v ^ pad;
            int isMismatch = (z | -z) >>> 31;              // z!=0 → 1，否则 0
            bad |= isMismatch & (inPadMask & 1);
        }
        return bad == 0;
    }
}
