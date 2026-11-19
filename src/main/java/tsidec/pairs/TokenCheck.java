package tsidec.pairs;

/**
 * 示例 2：令牌/口令校验——按位长度判断与逐字符比较。
 *
 * 常见于 API token、OTP、口令重置码。泄露来自两处：
 *   - 长度检查先于逐字符比较（不同长度走不同路径）
 *   - 逐字符比较在首个不匹配处返回
 */
public final class TokenCheck {
    private TokenCheck() {}

    /** 泄露版：逐字符比较，首个不匹配即返回。 */
    public static boolean unsafeToken(String candidate, String secret) {
        if (candidate.length() != secret.length()) return false;
        for (int i = 0; i < secret.length(); i++) {
            if (candidate.charAt(i) != secret.charAt(i)) return false;
        }
        return true;
    }

    /**
     * 泄露版 2：先比较长度再比较内容，长度差异本身可区分。
     * 攻击者先靠长度侧信道缩小搜索空间，再逐字符恢复。
     */
    public static boolean unsafeTokenWithLengthBranch(String candidate, String secret) {
        int n = Math.min(candidate.length(), secret.length());
        int diff = candidate.length() ^ secret.length();
        for (int i = 0; i < n; i++) {
            diff |= candidate.charAt(i) ^ secret.charAt(i);
        }
        return diff == 0;
    }

    /** 修复版：对完整长度做恒定时间比较，长度差异折叠进同一个累加器。 */
    public static boolean constantTimeToken(String candidate, String secret) {
        int la = candidate.length(), lb = secret.length();
        int diff = la ^ lb;
        int n = Math.max(la, lb);
        for (int i = 0; i < n; i++) {
            char a = i < la ? candidate.charAt(i) : 0;
            char b = i < lb ? secret.charAt(i) : 0;
            diff |= a ^ b;
        }
        return diff == 0;
    }
}
