package tsidec.stats;

/**
 * Student t 分布的 CDF。用于计算双样本 Welch t 检验与配对 t 检验的 p 值。
 * 采用正则化不完全 Beta 函数连分式展开（Numerical Recipes 6.4 的 betacf/betai），
 * 双精度下对自由度 1..1e6 都有足够精度。
 */
public final class StudentT {
    private StudentT() {}

    private static final int MAX_ITER = 300;
    private static final double EPS = 1e-14;
    private static final double TINY = 1e-300;

    /** 正则化不完全 Beta 函数 I_x(a,b)。 */
    static double betaInc(double a, double b, double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        double lbt = logBeta(a, b);
        double front = Math.exp(Math.log(x) * a + Math.log(1 - x) * b - lbt);
        if (x < (a + 1) / (a + b + 2)) {
            return front * betaCf(a, b, x) / a;
        }
        return 1 - front * betaCf(b, a, 1 - x) / b;
    }

    private static double logBeta(double a, double b) {
        return logGamma(a) + logGamma(b) - logGamma(a + b);
    }

    /** Lanczos 近似 log Γ(x)，x>0。 */
    public static double logGamma(double x) {
        if (x <= 0) throw new IllegalArgumentException("x must be > 0");
        double[] g = {
            76.18009172947146, -86.50532032941677, 24.01409824083091,
            -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5
        };
        double y = x, tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double c : g) {
            y += 1;
            ser += c / y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    private static double betaCf(double a, double b, double x) {
        double qab = a + b, qap = a + 1, qam = a - 1;
        double c = 1, d = 1 - qab * x / qap;
        if (Math.abs(d) < TINY) d = TINY;
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= MAX_ITER; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < TINY) d = TINY;
            c = 1 + aa / c;
            if (Math.abs(c) < TINY) c = TINY;
            d = 1 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < TINY) d = TINY;
            c = 1 + aa / c;
            if (Math.abs(c) < TINY) c = TINY;
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < EPS) break;
        }
        return h;
    }

    /** P(T <= t)，自由度为 df 的 t 分布。 */
    public static double cdf(double t, double df) {
        if (df <= 0) return Double.NaN;
        double x = df / (df + t * t);
        double p = 0.5 * betaInc(df / 2, 0.5, x);
        return t >= 0 ? 1 - p : p;
    }

    /** 双侧 p 值：P(|T| >= |t|)。 */
    public static double twoSidedP(double t, double df) {
        if (df <= 0 || Double.isNaN(t)) return Double.NaN;
        if (Double.isInfinite(t)) return 0;
        double x = df / (df + t * t);
        return betaInc(df / 2, 0.5, x);
    }

    /** 双侧临界值 t*，使 P(|T|>=t*)=alpha。用二分法（t 分布尾部单调）。 */
    public static double criticalValue(double alpha, double df) {
        if (df <= 0) return Double.NaN;
        if (alpha <= 0) return Double.POSITIVE_INFINITY;
        if (alpha >= 1) return 0;
        double lo = 0, hi = 1;
        while (twoSidedP(hi, df) > alpha && hi < 1e12) hi *= 2;
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (lo + hi);
            if (twoSidedP(mid, df) > alpha) lo = mid; else hi = mid;
        }
        return 0.5 * (lo + hi);
    }
}
