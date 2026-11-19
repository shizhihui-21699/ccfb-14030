package tsidec.stats;

/**
 * 两个比例的检验（用于自检通过率、复测通过率）。
 * 二分数据不需要 t 检验，用 Wilson 区间 + 双侧精确二项检验更稳。
 */
public final class Proportion {
    private Proportion() {}

    public static final class Wilson {
        public final double p;
        public final double lo;
        public final double hi;
        Wilson(double p, double lo, double hi) { this.p = p; this.lo = lo; this.hi = hi; }
    }

    /** Wilson score 区间（95% 默认）。 */
    public static Wilson wilson(int successes, int n, double z) {
        if (n == 0) return new Wilson(Double.NaN, Double.NaN, Double.NaN);
        double p = (double) successes / n;
        double z2 = z * z;
        double denom = 1 + z2 / n;
        double center = (p + z2 / (2.0 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom;
        return new Wilson(p, Math.max(0, center - half), Math.min(1, center + half));
    }

    public static Wilson wilson95(int successes, int n) { return wilson(successes, n, 1.959963984540054); }

    /** 双侧精确二项检验 H0: p = p0。返回 p 值。 */
    public static double exactBinomialTwoSided(int successes, int n, double p0) {
        if (n == 0) return 1;
        double observed = binomialPmf(successes, n, p0);
        double sum = 0;
        for (int k = 0; k <= n; k++) {
            double pk = binomialPmf(k, n, p0);
            if (pk <= observed * (1 + 1e-9)) sum += pk;
        }
        return Math.min(1.0, sum);
    }

    private static double binomialPmf(int k, int n, double p) {
        if (p <= 0) return k == 0 ? 1 : 0;
        if (p >= 1) return k == n ? 1 : 0;
        double logp = logChoose(n, k) + k * Math.log(p) + (n - k) * Math.log(1 - p);
        return Math.exp(logp);
    }

    private static double logChoose(int n, int k) {
        return StudentT.logGamma(n + 1) - StudentT.logGamma(k + 1) - StudentT.logGamma(n - k + 1);
    }
}
