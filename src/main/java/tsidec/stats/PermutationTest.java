package tsidec.stats;

/**
 * 置换检验：在“输入 → 类别”标签的零假设下，重排标签得到检验统计量的精确或抽样分布。
 * 对重尾/偏态的时间分布比 t 检验稳健得多（t 检验依赖均值近似正态，
 * 而单个 GC 停顿就能让一轮均值严重失真）。
 */
public final class PermutationTest {
    private PermutationTest() {}

    public static final class Result {
        public final double observed;   // 观测到的统计量（B 均值 - A 均值）
        public final double pValue;     // 双侧置换 p 值
        public final int permutations;
        Result(double observed, double pValue, int permutations) {
            this.observed = observed;
            this.pValue = pValue;
            this.permutations = permutations;
        }
    }

    /**
     * 双侧置换检验，统计量为两组均值差。
     * 标签数很少时（C(n, nA) <= maxExact）做完全枚举，否则做 Monte-Carlo 抽样。
     *
     * @param a     第一组观测
     * @param b     第二组观测
     * @param rounds Monte-Carlo 轮数（仅在无法完全枚举时使用）
     * @param rng    随机源（可复现）
     */
    public static Result meanDifference(double[] a, double[] b, int rounds, java.util.Random rng) {
        int na = a.length, nb = b.length, n = na + nb;
        if (na == 0 || nb == 0) return new Result(Double.NaN, Double.NaN, 0);
        double[] all = new double[n];
        System.arraycopy(a, 0, all, 0, na);
        System.arraycopy(b, 0, all, na, nb);
        double sumA = 0;
        for (double x : a) sumA += x;
        double sumAll = sumA;
        for (double x : b) sumAll += x;

        double observed = (sumAll - sumA) / nb - sumA / na;
        double absObs = Math.abs(observed);

        long combos = binomial(n, Math.min(na, nb));
        int exceed = 0, total = 0;

        if (combos > 0 && combos <= 200_000) {
            // 完全枚举所有大小为 na 的子集
            int[] idx = new int[na];
            for (int i = 0; i < na; i++) idx[i] = i;
            while (true) {
                double s = 0;
                for (int i : idx) s += all[i];
                double d = (sumAll - s) / nb - s / na;
                if (Math.abs(d) >= absObs - 1e-12) exceed++;
                total++;
                if (!nextCombination(idx, n)) break;
            }
        } else {
            int[] perm = new int[n];
            for (int i = 0; i < n; i++) perm[i] = i;
            for (int r = 0; r < rounds; r++) {
                // Fisher-Yates 洗牌
                for (int i = n - 1; i > 0; i--) {
                    int j = rng.nextInt(i + 1);
                    int t = perm[i]; perm[i] = perm[j]; perm[j] = t;
                }
                double s = 0;
                for (int i = 0; i < na; i++) s += all[perm[i]];
                double d = (sumAll - s) / nb - s / na;
                if (Math.abs(d) >= absObs - 1e-12) exceed++;
                total++;
            }
        }
        // +1 平滑，避免 p=0（Monte-Carlo 下 p 不可能严格为 0）
        double p = (exceed + 1.0) / (total + 1.0);
        return new Result(observed, p, total);
    }

    private static boolean nextCombination(int[] idx, int n) {
        int k = idx.length;
        int i = k - 1;
        while (i >= 0 && idx[i] == n - k + i) i--;
        if (i < 0) return false;
        idx[i]++;
        for (int j = i + 1; j < k; j++) idx[j] = idx[j - 1] + 1;
        return true;
    }

    /** 二项系数，溢出时返回 Long.MAX_VALUE。 */
    static long binomial(int n, int k) {
        if (k < 0 || k > n) return 0;
        if (k > n - k) k = n - k;
        double r = 1;
        for (int i = 1; i <= k; i++) {
            r = r * (n - k + i) / i;
            if (r > 9e18) return Long.MAX_VALUE;
        }
        return (long) r;
    }
}
