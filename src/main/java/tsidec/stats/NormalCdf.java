package tsidec.stats;

/** 正态分布 CDF（Abramowitz-Stegun 7.1.26 erf 有理逼近，绝对误差 < 1.5e-7）。 */
public final class NormalCdf {
    private NormalCdf() {}

    public static double cdf(double z) {
        return 0.5 * (1 + erf(z / Math.sqrt(2)));
    }

    public static double erf(double x) {
        double sign = Math.signum(x);
        double ax = Math.abs(x);
        double t = 1 / (1 + 0.3275911 * ax);
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * Math.exp(-ax * ax);
        return sign * y;
    }
}
