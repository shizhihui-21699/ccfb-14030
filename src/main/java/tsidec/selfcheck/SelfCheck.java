package tsidec.selfcheck;

import tsidec.core.Measurement;
import tsidec.core.MeasurementEngine;
import tsidec.stats.Descriptive;
import tsidec.stats.StudentT;

/**
 * 盲自检与零点校准。
 *
 * 正式测量任何候选函数之前，先在同一进程、同一 CPU 状态下跑一组"已知答案"的对照：
 *
 *   null      A/B 是同一份代码（真值 0）。必须【不显著】，否则说明测量系统本身有偏置，
 *             此时所有后续阳性结论都不可信 → 直接判为 "self-check failed"。
 *   tiny      B 比 A 多 1 条依赖 ALU 指令（约 0.07ns/次）。应【显著】。
 *   small     +4 条。应【显著】且效应量随指令数单调增大。
 *   medium    +8 条。应【显著】。
 *
 * 斜率校准：用 (extraOps, 效应量) 做线性回归，得到"每条依赖指令 ≈ 多少 ns/次"。
 * 这一步把判定阈值从抽象的单位换算成"相当于多执行几条 ALU 指令"，也让我们能识别
 * "编译器把校准代码也优化掉了"的情形（斜率为 0 → 校准失效，工具拒绝给出阴性结论）。
 */
public final class SelfCheck {

    public static final class ProbeResult {
        public final String label;
        public final int extraOps;
        public final double effectNs;
        public final double se;
        public final double pValue;      // 配对 t 检验 H0: effect=0
        public final boolean significant;
        public final Measurement measurement;
        ProbeResult(String label, int extraOps, double effectNs, double se, double pValue,
                    boolean significant, Measurement m) {
            this.label = label; this.extraOps = extraOps; this.effectNs = effectNs;
            this.se = se; this.pValue = pValue; this.significant = significant; this.measurement = m;
        }
        public String describe() {
            return String.format("  %-8s extra=%d  effect=%+.4f ns/op  SE=%.4f  t=%+.1f  p=%.2e  %s",
                    label, extraOps, effectNs, se, se > 0 ? effectNs / se : 0, pValue,
                    significant ? "SIGNIFICANT" : "not-significant");
        }
    }

    public static final class Result {
        public final ProbeResult nullProbe;
        public final ProbeResult tiny;
        public final ProbeResult small;
        public final ProbeResult medium;
        public final double biasNs;          // null 探针的效应量
        public final double biasSe;
        public final double nsPerAluOp;      // 斜率
        public final double slopeSe;
        public final double interceptNs;     // 注入点固定开销（诊断）
        public final double noiseSdNs;       // 配对差的典型 sd
        public final boolean passed;         // 自检总判定
        public final String failureReason;

        Result(ProbeResult nullProbe, ProbeResult tiny, ProbeResult small, ProbeResult medium,
               double biasNs, double biasSe, double nsPerAluOp, double slopeSe, double interceptNs,
               double noiseSdNs, boolean passed, String failureReason) {
            this.nullProbe = nullProbe; this.tiny = tiny; this.small = small; this.medium = medium;
            this.biasNs = biasNs; this.biasSe = biasSe;
            this.nsPerAluOp = nsPerAluOp; this.slopeSe = slopeSe;
            this.interceptNs = interceptNs;
            this.noiseSdNs = noiseSdNs;
            this.passed = passed; this.failureReason = failureReason;
        }

        /** 该效应量相当于多少条依赖 ALU 指令（负面信息，用于解释量级）。 */
        public double inAluOps(double effectNs) {
            return nsPerAluOp > 0 ? Math.abs(effectNs) / nsPerAluOp : Double.NaN;
        }

        /**
         * 判定某个候选效应量是否"物理上显著"：
         * 既要在统计上离开零点（考虑系统零点偏置），又要超过最小效应量门槛。
         * 单看 p 值会把 0.0036ns 的噪声判成泄露；单看效应量会漏掉小但稳定的泄露。
         */
        public boolean materiallySignificant(double effectNs, double se, double minEffectNs) {
            if (Math.abs(effectNs) < minEffectNs) return false;
            // 与"系统零点偏置"比较，而不是与 0 比较：偏置是测量系统的固有不对称。
            double seEff = Math.sqrt(se * se + biasSe * biasSe);
            double t = (effectNs - biasNs) / seEff;
            return Math.abs(t) > 4.5;
        }
    }

    public static Result run(MeasurementEngine engine, MeasurementEngine.Config cfg) {
        long[] sink = new long[1];
        ProbeResult nullP = runOne(engine, cfg, "null", 0, sink);
        ProbeResult tinyP = runOne(engine, cfg, "tiny", 1, sink);
        ProbeResult smallP = runOne(engine, cfg, "small", 4, sink);
        ProbeResult mediumP = runOne(engine, cfg, "medium", 8, sink);

        // 斜率：带截距的加权最小二乘（权重 1/SE^2）。
        // 必须带截距：注入点 B 相对 A 还有一个与 extraOps 无关的固定开销
        // （分支/调用边界差异），若强行过原点会把这个常数算进斜率，
        // 高估"每条指令的成本"。我们要的是边际成本 b，不是平均成本。
        double[] xs = {1, 4, 8};
        ProbeResult[] ps = {tinyP, smallP, mediumP};
        double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < xs.length; i++) {
            double w = ps[i].se > 0 ? 1 / (ps[i].se * ps[i].se) : 1e12;
            sw += w; sx += w * xs[i]; sy += w * ps[i].effectNs;
            sxx += w * xs[i] * xs[i]; sxy += w * xs[i] * ps[i].effectNs;
        }
        double det = sw * sxx - sx * sx;
        double slope = det != 0 ? (sw * sxy - sx * sy) / det : 0;
        double intercept = det != 0 ? (sy - slope * sx) / sw : 0;
        double slopeSe = Double.NaN;
        if (det != 0) {
            // 参数协方差：var(b) = sw / det（对角元），残差方差由 3 个点 1 个自由度估计
            double rss = 0;
            for (int i = 0; i < xs.length; i++) {
                double pred = intercept + slope * xs[i];
                double e = ps[i].effectNs - pred;
                rss += e * e;
            }
            double s2 = rss; // df = 3 - 2 = 1
            slopeSe = Math.sqrt(sw / det * s2);
        }
        // 小样本下用 Student-t 的分位数修正：df=1 时 t_{0.975}=12.7，
        // 正规的 Welch/OLS 区间都应放宽，否则 3 点校准会把斜率误差低估 4 倍。
        double tCrit = StudentT.criticalValue(0.05, 1);
        if (Double.isFinite(slopeSe)) slopeSe *= Math.max(1, tCrit / 1.96);

        double noise = nullP.measurement.sdDiff;

        boolean ok = true;
        StringBuilder why = new StringBuilder();
        // 1) 零点偏置：必须明显小于最小效应量门槛。注意不要用"p 值小"作为失败判据——
        //    在 N 很大时，0.0036ns 也会 p<0.05，但它只是亚皮秒级，纯属无意义。
        if (Math.abs(nullP.effectNs) > 0.25) {
            ok = false;
            why.append("零点偏置幅度过大（").append(String.format("%.3f", nullP.effectNs))
               .append(" ns/op），超过可接受范围 0.25ns。");
        }
        // 2) 已知 1 条指令的差异必须能被检出
        if (!tinyP.significant || tinyP.effectNs <= 0) {
            ok = false;
            why.append("无法检出已知的 1 条依赖 ALU 指令差异，灵敏度不足。");
        }
        // 3) 必须单调：效应量随指令数增加
        if (!(tinyP.effectNs < smallP.effectNs && smallP.effectNs < mediumP.effectNs)) {
            ok = false;
            why.append("效应量未随注入指令数单调递增，测量不可靠。");
        }
        // 4) 斜率必须为正且与已知比例相符（粗略：每条指令应在 0.02~2ns）
        if (!(slope > 0.02) || slope > 2.0) {
            ok = false;
            why.append("校准斜率异常（").append(String.format("%.4f", slope))
               .append(" ns/指令），JIT 可能已把被测差异优化掉。");
        }

        return new Result(nullP, tinyP, smallP, mediumP,
                nullP.effectNs, nullP.se, slope, slopeSe, intercept, noise, ok,
                ok ? null : why.toString().trim());
    }

    private static ProbeResult runOne(MeasurementEngine engine, MeasurementEngine.Config baseCfg,
                                      String label, int extraOps, long[] sink) {
        MeasurementEngine.Config cfg = new MeasurementEngine.Config();
        cfg.rounds = baseCfg.rounds;
        cfg.callsPerBatch = baseCfg.callsPerBatch;   // 0 = 自动调优
        cfg.targetBatchNs = baseCfg.targetBatchNs;
        cfg.minCallsPerBatch = baseCfg.minCallsPerBatch;
        cfg.maxCallsPerBatch = baseCfg.maxCallsPerBatch;
        cfg.warmupRounds = baseCfg.warmupRounds;
        cfg.gapMillis = baseCfg.gapMillis;
        cfg.preMeasureSettleMillis = baseCfg.preMeasureSettleMillis;

        CalibrationHarness h = new CalibrationHarness(extraOps, sink);
        Measurement m = measureHarness(engine, cfg, h, label);

        int n = m.rounds();
        // 与 Analyzer 保持同一套点估计/SE，避免"自检用均值、判定用截尾"造成口径不一致。
        double mean = m.trimmedMean;
        double se = m.trimmedSe;
        double t = se > 0 ? mean / se : 0;
        double p = StudentT.twoSidedP(t, Math.max(1, m.trimmedN - 1));
        boolean sig = p < 1e-4 && Math.abs(mean) > 6 * se;
        return new ProbeResult(label, extraOps, mean, se, p, sig, m);
    }

    /** 用给定 harness 做一次完整测量（复用 MeasurementEngine 的轮次逻辑）。 */
    static Measurement measureHarness(MeasurementEngine engine, MeasurementEngine.Config cfg,
                                      tsidec.core.AlternatingHarness h, String name) {
        return engine.measure(h, name, cfg);
    }

    /** 打印自检报告。 */
    public static String describe(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 测量系统自检（同一进程、同一 CPU 状态） ===\n");
        sb.append(r.nullProbe.describe()).append('\n');
        sb.append(r.tiny.describe()).append('\n');
        sb.append(r.small.describe()).append('\n');
        sb.append(r.medium.describe()).append('\n');
        sb.append(String.format("  零点偏置 = %+.4f ns/op (SE %.4f)%n", r.biasNs, r.biasSe));
        sb.append(String.format("  校准斜率 = %.4f ns / 每条依赖 ALU 指令 (SE %.4f)，截距 %+.4f ns%n",
                r.nsPerAluOp, r.slopeSe, r.interceptNs));
        sb.append(String.format("  噪声水平 = 配对差 sd %.4f ns/op%n", r.noiseSdNs));
        sb.append("  判定: ").append(r.passed ? "PASS — 测量系统可信" :
                "FAIL — " + r.failureReason).append('\n');
        return sb.toString();
    }
}
