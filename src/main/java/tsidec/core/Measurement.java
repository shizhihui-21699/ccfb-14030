package tsidec.core;

import tsidec.stats.Descriptive;
import tsidec.stats.Trend;

import java.util.ArrayList;
import java.util.List;

/** 一次测量的全部原始数据与派生统计。 */
public final class Measurement {
    public final String probeName;
    public final int callsPerBatch;
    public final double[] perRoundDiffNs;      // (B-A)/K，单位 ns/次
    public final double[] perRoundA_ns;        // 每轮 A 批次的绝对耗时（诊断用）
    public final double[] perRoundB_ns;
    public final int warmupRoundsUsed;
    public final Trend.Result drift;

    // 派生统计（构造时计算）
    public final double meanDiff;
    public final double medianDiff;
    public final double sdDiff;
    public final double robustSdDiff;
    public final double seMean;
    public final double trimmedMean;
    public final double trimmedSd;
    public final double trimmedSe;
    public final int trimmedN;
    public final double fracBGreater;   // B 比 A 慢的轮次比例（符号对称性诊断）
    public final boolean driftSuspicious;

    Measurement(String probeName, int k, double[] d, double[] a, double[] b, int warmup, Trend.Result drift) {
        this.probeName = probeName;
        this.callsPerBatch = k;
        this.perRoundDiffNs = d;
        this.perRoundA_ns = a;
        this.perRoundB_ns = b;
        this.warmupRoundsUsed = warmup;
        this.drift = drift;

        this.meanDiff = Descriptive.mean(d);
        this.medianDiff = Descriptive.median(d);
        this.sdDiff = Descriptive.sd(d);
        this.robustSdDiff = Descriptive.robustSd(d);
        this.seMean = sdDiff / Math.sqrt(d.length);

        // 稳健聚合：去掉偏离中位数最远的 10% 轮次（两侧对称），抵消偶发抢占长尾。
        double[] trimmed = Descriptive.trimByDeviation(d, 0.90);
        this.trimmedMean = Descriptive.mean(trimmed);
        this.trimmedSd = Descriptive.sd(trimmed);
        this.trimmedSe = trimmedSd / Math.sqrt(trimmed.length);
        this.trimmedN = trimmed.length;

        int greater = 0, nz = 0;
        for (double v : d) {
            if (v > 0) greater++;
            if (v != 0) nz++;
        }
        this.fracBGreater = nz == 0 ? 0.5 : (double) greater / nz;

        // 漂移危险判据：显著且相对效应 > 三个稳健标准误
        this.driftSuspicious = drift.significant(1e-4)
                && Math.abs(drift.driftPer1k) > 3 * (robustSdDiff / Math.sqrt(Math.max(1, d.length))) * 1000;
    }

    public int rounds() { return perRoundDiffNs.length; }

    public double medianBatchNs() {
        return 0.5 * (Descriptive.median(perRoundA_ns) + Descriptive.median(perRoundB_ns));
    }

    /**
     * 较短那批的中位时长。量化误差由较短批次决定：两类输入工作量可能差几十倍
     * （如清零 1 字节 vs 256 字节），质量判据必须看短的那边。
     */
    public double minBatchNs() {
        return Math.min(Descriptive.median(perRoundA_ns), Descriptive.median(perRoundB_ns));
    }

    /** 拆半复现：两半独立估计的效应量，用于判断效应是否稳定。 */
    public double[] splitHalfEffects() {
        int n = perRoundDiffNs.length;
        int half = n / 2;
        double[] first = new double[half];
        double[] second = new double[n - half];
        System.arraycopy(perRoundDiffNs, 0, first, 0, half);
        System.arraycopy(perRoundDiffNs, half, second, 0, n - half);
        return new double[]{Descriptive.mean(first), Descriptive.mean(second)};
    }

    /** 诊断文本：一行说明本测量的质量。 */
    public String qualityNote() {
        List<String> notes = new ArrayList<>();
        if (driftSuspicious) notes.add("测量期间存在显著漂移（" + drift.direction + "），结论不可靠");
        if (minBatchNs() < 8_000) notes.add("较短批次仅 " + (long) minBatchNs() + "ns，接近时钟粒度");
        if (Descriptive.robustSd(perRoundDiffNs) > 0.5 * Math.abs(meanDiff) && Math.abs(meanDiff) > 0)
            notes.add("离散度与效应量同量级，效应可能被长尾噪声主导");
        return notes.isEmpty() ? "OK" : String.join("；", notes);
    }
}
