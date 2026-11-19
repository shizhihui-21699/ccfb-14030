package tsidec.pairs;

/**
 * 示例 5：敏感数据清零 / 长度前缀解析。
 *
 * 支付系统里"擦除密钥材料"和"解析长度字段"两处都爱出问题。
 * 泄露点：按秘密长度做不同的内存访问，或按秘密值决定擦除范围。
 */
public final class WipeAndParse {
    private WipeAndParse() {}

    /**
     * 泄露版：按秘密长度清零。长度是秘密时（比如密钥材料的有效长度），
     * 清零耗时可反推长度；更隐蔽的是，按长度做 System.arraycopy 会留下
     * 可被缓存的访问模式。
     */
    public static int unsafeWipeLength(byte[] buf, int secretLen) {
        int total = 0;
        for (int i = 0; i < secretLen; i++) {
            buf[i] = 0;
            total++;
        }
        return total;
    }

    /** 修复版：总是清零整个缓冲区，与秘密长度无关。 */
    public static int constantTimeWipeLength(byte[] buf, int secretLen) {
        for (int i = 0; i < buf.length; i++) {
            buf[i] = 0;
        }
        return buf.length;
    }

    /**
     * 泄露版：解析变长字段，循环边界由数据里的秘密长度决定。
     */
    public static int unsafeParseLength(byte[] data, int limit) {
        int len = 0;
        int shift = 0;
        for (int i = 0; i < 4 && i < limit; i++) {
            len |= (data[i] & 0xFF) << shift;
            shift += 8;
            if ((data[i] & 0x80) == 0) break; // 高位为 0 表示长度结束
        }
        return len;
    }

    /** 修复版：固定读取 4 字节，不因高位提前退出。 */
    public static int constantTimeParseLength(byte[] data, int limit) {
        int len = 0;
        int shift = 0;
        int n = Math.min(4, limit);
        for (int i = 0; i < 4; i++) {
            int b = i < n ? (data[i] & 0xFF) : 0;
            len |= b << shift;
            shift += 8;
        }
        return len;
    }
}
