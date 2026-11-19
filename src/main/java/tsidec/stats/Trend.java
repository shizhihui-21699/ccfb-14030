package tsidec.stats;

/**
 * 漂移/趋势检验：随测量次序，时间是否单调漂移（比如频率调节、缓存越跑越暖、
 * 后台进程启动）。这是最容易被误判成“泄露”的系统性噪声，必须在判定前剔除。
 *
 * 方法：对 (index, value) 做 Spearman 秩相关；n 较大时用正态近似。
 */
public final class Trend {
    private Trend() {}

    public static final class Result {
        public final double rho;      // Spearman 秩相关 [-1,1]
        public final double pValue;   // 双侧 p
        public final double driftPer1k; // 每 1000 轮的最小二乘斜率（同单位）
        public final String direction;
        Result(double rho, double pValue, double driftPer1k) {
            this.rho = rho;
            this.pValue = pValue;
            this.driftPer1k = driftPer1k;
            this.direction = rho > 0 ? "rising（时间随轮次增加，疑似热身/发热）"
                    : rho < 0 ? "falling（时间随轮次下降，疑似编码器升温/缓存变暖）" : "flat";
        }
        public boolean significant(double alpha) { return pValue < alpha; }
    }

    public static Result spearman(double[] ordered) {
        int n = ordered.length;
        if (n < 8) return new Result(0, 1, 0);
        double[] rank = rankOf(ordered);
        // 次序标签的秩就是 1..n
        double meanRank = (n + 1) / 2.0;
        double sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            double dx = (i + 1) - meanRank;
            double dy = rank[i] - meanRank;
            sumXY += dx * dy;
            sumX2 += dx * dx;
            sumY2 += dy * dy;
        }
        double rho = (sumX2 > 0 && sumY2 > 0) ? sumXY / Math.sqrt(sumX2 * sumY2) : 0;
        double p;
        double z = rho * Math.sqrt(n - 1.0);
        p = 2 * (1 - NormalCdf.cdf(Math.abs(z)));

        // 最小二乘斜率（用于报告“每千轮漂移多少 ns”）
        double mx = (n - 1) / 2.0;
        double my = Descriptive.mean(ordered);
        double sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) {
            double dx = i - mx;
            sxy += dx * (ordered[i] - my);
            sxx += dx * dx;
        }
        double slope = sxx > 0 ? sxy / sxx : 0;
        return new Result(rho, p, slope * 1000.0);
    }

    /** 平均秩（处理并列，取并列位置的平均），返回 0-based 秩。 */
    static double[] rankOf(double[] v) {
        int n = v.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(v[a], v[b]));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && v[idx[j + 1]] == v[idx[i]]) j++;
            double avg = (i + j) / 2.0;
            for (int k = i; k <= j; k++) r[idx[k]] = avg;
            i = j + 1;
        }
        return r;
    }
}
