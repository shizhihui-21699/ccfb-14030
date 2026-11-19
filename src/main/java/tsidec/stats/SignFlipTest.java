package tsidec.stats;

import java.util.Random;

/**
 * 配对符号翻转置换检验。
 *
 * 场景：我们有 n 个**配对差** d_i = B_i - A_i。零假设是"两类无可区分差异"，
 * 即每个 d_i 关于 0 对称。在此零假设下，随机翻转每个 d_i 的符号不改变分布。
 *
 * 为什么不用"前一半 vs 后一半"的两样本置换：两半各自有漂移和共同分量，
 * 分组比较会把漂移当成组间差异，或把组间差异稀释（实测 t=1041 的确定泄漏
 * 被算成 p=0.1）。符号翻转直接以"差值为 0"为零假设，是配对设计的正确检验。
 */
public final class SignFlipTest {
    private SignFlipTest() {}

    public static final class Result {
        public final double observedMean;
        public final double pValue;
        public final int rounds;
        Result(double observedMean, double pValue, int rounds) {
            this.observedMean = observedMean; this.pValue = pValue; this.rounds = rounds;
        }
    }

    /**
     * 双侧符号翻转检验。
     *
     * @param diffs  配对差
     * @param rounds Monte-Carlo 轮数（n 很小时做完全枚举）
     * @param rng    随机源
     */
    public static Result test(double[] diffs, int rounds, Random rng) {
        int n = diffs.length;
        if (n == 0) return new Result(Double.NaN, Double.NaN, 0);
        double sum = 0;
        for (double d : diffs) sum += d;
        double observed = sum / n;
        double absObs = Math.abs(observed);

        // n <= 20 时完全枚举 2^n 种符号
        if (n <= 20) {
            long total = 1L << n;
            long exceed = 0;
            for (long mask = 0; mask < total; mask++) {
                double s = 0;
                for (int i = 0; i < n; i++) {
                    s += ((mask >>> i) & 1L) == 1L ? -diffs[i] : diffs[i];
                }
                if (Math.abs(s / n) >= absObs - 1e-12) exceed++;
            }
            return new Result(observed, (double) exceed / total, (int) total);
        }

        long exceed = 0;
        for (int r = 0; r < rounds; r++) {
            double s = 0;
            for (int i = 0; i < n; i++) {
                s += rng.nextBoolean() ? -diffs[i] : diffs[i];
            }
            if (Math.abs(s / n) >= absObs - 1e-12) exceed++;
        }
        return new Result(observed, (exceed + 1.0) / (rounds + 1.0), rounds);
    }
}
