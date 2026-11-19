package tsidec.core;

/**
 * 时间证据的判定结果。
 *
 * 判定要同时满足四件事，缺一不可：
 *   1. 统计显著：Welch 配对 t 与置换检验都拒绝"两类相同"（双保险，防止
 *      长尾分布下 t 检验的 p 值失真）；
 *   2. 效应量足够大：超过 max(最小可测效应量, 系统零点偏置 + 5 个标准误)，
 *      防止把亚皮秒级的偏置当成泄漏；
 *   3. 稳定：拆半复现的两半估计同号，且符号一致率不接近 50%；
 *   4. 测量质量合格：无显著漂移、批次足够长、自检通过。
 */
public final class TimingVerdict {
    public final Analysis.Verdict verdict;

    public final double effectNs;        // B - A，单位 ns/次
    public final double se;
    public final double tStat;
    public final double welchP;
    public final double permP;
    public final double trimmedEffectNs;
    public final double trimmedSe;
    public final boolean stable;
    public final boolean qualityOk;
    public final String reason;

    public TimingVerdict(Analysis.Verdict verdict, double effectNs, double se, double tStat,
                         double welchP, double permP, double trimmedEffectNs, double trimmedSe,
                         boolean stable, boolean qualityOk, String reason) {
        this.verdict = verdict;
        this.effectNs = effectNs; this.se = se; this.tStat = tStat;
        this.welchP = welchP; this.permP = permP;
        this.trimmedEffectNs = trimmedEffectNs; this.trimmedSe = trimmedSe;
        this.stable = stable; this.qualityOk = qualityOk; this.reason = reason;
    }
}
