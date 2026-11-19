package tsidec.stats;

import java.util.Arrays;

/**
 * 描述统计。所有方法对空数组/单元素数组都有明确定义，避免调用方重复防御。
 */
public final class Descriptive {
    private Descriptive() {}

    public static double mean(double[] v) {
        if (v.length == 0) return Double.NaN;
        double s = 0;
        for (double x : v) s += x;
        return s / v.length;
    }

    public static double mean(long[] v) {
        if (v.length == 0) return Double.NaN;
        double s = 0;
        for (long x : v) s += x;
        return s / v.length;
    }

    /** 样本方差（n-1 分母）。n<2 时返回 NaN。 */
    public static double variance(double[] v) {
        if (v.length < 2) return Double.NaN;
        double m = mean(v), s = 0;
        for (double x : v) {
            double e = x - m;
            s += e * e;
        }
        return s / (v.length - 1);
    }

    public static double sd(double[] v) {
        return Math.sqrt(variance(v));
    }

    /** 中位数。会复制并排序，不修改入参。 */
    public static double median(double[] v) {
        if (v.length == 0) return Double.NaN;
        double[] c = v.clone();
        Arrays.sort(c);
        int n = c.length;
        return (n & 1) == 1 ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }

    /** 经验分位数，p∈[0,1]，线性插值。 */
    public static double quantile(double[] v, double p) {
        if (v.length == 0) return Double.NaN;
        double[] c = v.clone();
        Arrays.sort(c);
        return quantileSorted(c, p);
    }

    public static double quantileSorted(double[] sorted, double p) {
        int n = sorted.length;
        if (n == 0) return Double.NaN;
        if (n == 1) return sorted[0];
        double pos = Math.max(0, Math.min(1, p)) * (n - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(n - 1, lo + 1);
        double f = pos - lo;
        return sorted[lo] * (1 - f) + sorted[hi] * f;
    }

    /**
     * 稳健离散度：基于中位数绝对偏差的 sd 估计（1.4826*MAD）。
     * 用于在存在偶发抢占中断（重尾）时给出不被少数离群轮次放大的尺度估计。
     */
    public static double robustSd(double[] v) {
        if (v.length < 2) return Double.NaN;
        double med = median(v);
        double[] dev = new double[v.length];
        for (int i = 0; i < v.length; i++) dev[i] = Math.abs(v[i] - med);
        return 1.4826 * median(dev);
    }

    /** 按 |x-median| 截尾：保留最接近中位数的 keepFraction 比例。返回新数组。 */
    public static double[] trimByDeviation(double[] v, double keepFraction) {
        int keep = Math.max(2, (int) Math.round(v.length * Math.max(0.05, Math.min(1.0, keepFraction))));
        Integer[] idx = new Integer[v.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        final double med = median(v);
        Arrays.sort(idx, (a, b) -> Double.compare(Math.abs(v[a] - med), Math.abs(v[b] - med)));
        double[] out = new double[keep];
        for (int i = 0; i < keep; i++) out[i] = v[idx[i]];
        return out;
    }

    /** 标准化（尺度单位为样本 sd）；样本 sd 为 0 时返回全 0。 */
    public static double standardized(double[] v) {
        double m = mean(v), s = sd(v);
        if (!(s > 0)) return 0;
        return (m - 0) / s;
    }
}
