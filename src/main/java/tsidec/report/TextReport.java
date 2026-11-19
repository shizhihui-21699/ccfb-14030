package tsidec.report;

import tsidec.core.Analysis;
import tsidec.core.Target;
import tsidec.selfcheck.SelfCheck;

import java.util.List;

/** 生成人读报告。完整机器可读版本见 {@link JsonReport}。 */
public final class TextReport {
    private TextReport() {}

    public static String header(String jvm, String os, String cpu, MeasurementSummary cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════════════╗\n");
        sb.append("║  Java 时间侧信道检测工具 (tsidec)                                ║\n");
        sb.append("╚══════════════════════════════════════════════════════════════════╝\n");
        sb.append("JVM   : ").append(jvm).append('\n');
        sb.append("OS    : ").append(os).append('\n');
        sb.append("CPU   : ").append(cpu).append('\n');
        sb.append("参数  : 每批 ").append(cfg.callsPerBatch <= 0 ? "自动" : String.valueOf(cfg.callsPerBatch))
          .append(" 次 × ").append(cfg.rounds).append(" 轮（ABBA 交替，慢函数按预算自动减轮）\n");
        return sb.toString();
    }

    public static final class MeasurementSummary {
        public int callsPerBatch;
        public int rounds;
        public MeasurementSummary(int k, int r) { callsPerBatch = k; rounds = r; }
    }

    public static String selfCheck(SelfCheck.Result r) {
        return SelfCheck.describe(r);
    }

    public static String target(Analysis a) {
        Target t = a.target;
        StringBuilder sb = new StringBuilder();
        sb.append("\n──────────────────────────────────────────────────────────────────\n");
        sb.append("目标: ").append(t.name()).append('\n');
        sb.append("位置: ").append(t.className()).append('#').append(t.methodName()).append('\n');
        sb.append("秘密: ").append(t.secretDescription()).append('\n');
        sb.append("结论: ").append(verdictLabel(a.verdict())).append('\n');

        // 时间证据
        sb.append("  时间: 效应 ").append(fmt(a.timingVerdict.effectNs)).append(" ns/次");
        sb.append("，95%CI ±").append(fmt(1.96 * a.timingVerdict.se));
        sb.append("，t=").append(String.format("%.1f", a.timingVerdict.tStat));
        sb.append("，p=").append(sci(a.timingVerdict.welchP));
        sb.append("，符号翻转 p=").append(sci(a.timingVerdict.permP)).append('\n');
        sb.append("        实际 ").append(a.timing.rounds()).append(" 轮 × ")
          .append(a.timing.callsPerBatch).append(" 次/批，较短批次 ")
          .append(String.format("%.0f", a.timing.minBatchNs())).append(" ns\n");

        // 结构证据
        if (!a.instrumented) {
            sb.append("  结构: 插桩失败（").append(a.instrumentError).append("）\n");
        } else if (a.blockDiffs.isEmpty()) {
            sb.append("  结构: 两类秘密下执行路径完全一致（每次调用 ")
              .append(a.insnsClassA).append(" 条字节码）\n");
        } else {
            sb.append("  结构: 每次调用执行 ")
              .append(a.insnsClassA).append(" vs ").append(a.insnsClassB)
              .append(" 条字节码（相差 ").append(String.format("%.0f%%", a.insnRatio() * 100)).append("）\n");
            int shown = 0;
            for (Analysis.BlockDiff d : a.blockDiffs) {
                if (shown++ >= 4) { sb.append("        ...\n"); break; }
                sb.append("        ").append(loc(d)).append("  执行 ")
                  .append(d.hitsA).append(" → ").append(d.hitsB).append(" 次")
                  .append(d.meta.terminalBranch ? "（分支块）" : "").append('\n');
            }
        }
        sb.append("  说明: ").append(a.timingVerdict.reason).append('\n');
        return sb.toString();
    }

    private static String loc(Analysis.BlockDiff d) {
        String cls = d.meta.owner.replace('/', '.');
        String meth = d.meta.method.split("\\(")[0];
        return (d.meta.line > 0 ? cls + ".java:" + d.meta.line : cls + "#" + meth)
                + " (" + meth + ")";
    }

    public static String summary(List<Analysis> all, int expectedLeaks) {
        int leaks = 0, clean = 0, inconclusive = 0, wrong = 0;
        for (Analysis a : all) {
            switch (a.verdict()) {
                case LEAK_CONFIRMED:
                case LEAK_STRUCTURAL:
                case LEAK_TIMING_ONLY: leaks++; break;
                case NO_LEAK: clean++; break;
                default: inconclusive++; break;
            }
            Target.Expectation e = a.target.expectation();
            if (e != Target.Expectation.UNKNOWN) {
                boolean predictedLeak = a.verdict() != Analysis.Verdict.NO_LEAK
                        && a.verdict() != Analysis.Verdict.INCONCLUSIVE;
                boolean shouldLeak = e == Target.Expectation.LEAK;
                if (predictedLeak != shouldLeak) wrong++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n══════════════════════════════════════════════════════════════════\n");
        sb.append("汇总: 检测出泄漏 ").append(leaks)
          .append("，判定无泄漏 ").append(clean)
          .append("，无法判定 ").append(inconclusive).append('\n');
        if (expectedLeaks >= 0) {
            sb.append("自测: ").append(all.size() - wrong).append("/").append(all.size())
              .append(" 个目标与预期一致");
            if (wrong > 0) sb.append("  ← 有 ").append(wrong).append(" 个误判！");
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String verdictLabel(Analysis.Verdict v) {
        switch (v) {
            case LEAK_CONFIRMED:   return "★ 泄漏（时间 + 结构双重证据）";
            case LEAK_STRUCTURAL:  return "★ 泄漏（结构性证据明确；时间差可能被噪声淹没）";
            case LEAK_TIMING_ONLY: return "⚠ 疑似泄漏（仅有时间证据，需人工复核是否有缓存/数据依赖）";
            case NO_LEAK:          return "✓ 未发现泄漏";
            default:               return "? 无法判定（测量质量不达标）";
        }
    }

    private static String fmt(double v) {
        return String.format("%+.3f", v);
    }

    private static String sci(double p) {
        if (p == 0) return "0";
        if (p < 1e-4) return String.format("%.1e", p);
        return String.format("%.3f", p);
    }
}
