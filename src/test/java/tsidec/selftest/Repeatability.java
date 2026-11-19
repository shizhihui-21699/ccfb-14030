package tsidec.selftest;

import tsidec.core.*;
import tsidec.pairs.SampleTargets;
import tsidec.selfcheck.SelfCheck;

import java.nio.file.Paths;

/**
 * 重复性验收：对"修复版"（真值应无泄漏）重复测量多次，统计误报率；
 * 对"泄露版"重复测量，统计漏报率。
 *
 * 这是工具能否上生产的分水岭。单次跑对不算数——统计工具必须在重复实验中
 * 保持第一类/第二类错误率可控。本类由 `AllTests` 之外的入口调用，
 * 因为它是长跑（数十秒），不适合放进默认自测。
 */
public final class Repeatability {

    /** 简化的分析通路，直接复用 Analyzer，但用的轮数更低以控制时长。 */
    public static final class Outcome {
        public final String name;
        public final Target.Expectation expectation;
        public final Analysis.Verdict verdict;
        public Outcome(String n, Target.Expectation e, Analysis.Verdict v) {
            name = n; expectation = e; verdict = v;
        }
        public boolean predictedLeak() {
            return verdict != Analysis.Verdict.NO_LEAK && verdict != Analysis.Verdict.INCONCLUSIVE;
        }
        public boolean correct() {
            if (verdict == Analysis.Verdict.INCONCLUSIVE) return false;
            return predictedLeak() == (expectation == Target.Expectation.LEAK);
        }
    }

    public static void main(String[] args) {
        int repeats = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 3000;

        MeasurementEngine engine = new MeasurementEngine();
        MeasurementEngine.Config cfg = new MeasurementEngine.Config();
        cfg.rounds = rounds;
        cfg.timeBudgetNanos = 3_000_000_000L;

        MeasurementEngine.Config calCfg = cfg.copy();
        calCfg.rounds = Math.max(2000, rounds / 2);
        SelfCheck.Result cal = SelfCheck.run(engine, calCfg);
        System.out.printf("自检: %s  零点偏置 %+.4f ns  每条指令 %.4f ns%n",
                cal.passed ? "PASS" : "FAIL", cal.biasNs, cal.nsPerAluOp);
        if (!cal.passed) { System.out.println("自检未通过，终止"); System.exit(2); return; }

        // 只选有明确预期的目标；跳过的极慢目标单独说明
        Target[] targets = {
            SampleTargets.macUnsafe(), SampleTargets.macConstantTime(),
            SampleTargets.tokenUnsafe(), SampleTargets.tokenConstantTime(),
            SampleTargets.paddingUnsafe(), SampleTargets.paddingConstantTime(),
            SampleTargets.wipeUnsafe(), SampleTargets.wipeConstantTime(),
        };

        Analyzer analyzer = new Analyzer(engine, cal, Paths.get("out"));
        int falsePositive = 0, falseNegative = 0, inconclusive = 0, total = 0;
        int nNoLeak = 0, nLeak = 0;

        for (int r = 0; r < repeats; r++) {
            for (Target t : targets) {
                Analysis a = analyzer.analyze(t, cfg);
                total++;
                boolean should = t.expectation() == Target.Expectation.LEAK;
                if (should) nLeak++; else nNoLeak++;
                if (a.verdict() == Analysis.Verdict.INCONCLUSIVE) {
                    inconclusive++;
                    System.out.printf("  [%s] %-28s INCONCLUSIVE (%s)%n", "r" + r, shortName(t), a.timingVerdict.reason);
                } else if (should && !(a.verdict() != Analysis.Verdict.NO_LEAK)) {
                    falseNegative++;
                    System.out.printf("  [%s] %-28s FALSE NEGATIVE  eff=%+.3f%n", "r" + r, shortName(t), a.timingVerdict.effectNs);
                } else if (!should && a.verdict() != Analysis.Verdict.NO_LEAK) {
                    falsePositive++;
                    System.out.printf("  [%s] %-28s FALSE POSITIVE  eff=%+.3f (%s)%n",
                            "r" + r, shortName(t), a.timingVerdict.effectNs, a.verdict());
                }
            }
        }

        System.out.printf("%n重复 %d 次 × %d 目标 = %d 次测量%n", repeats, targets.length, total);
        System.out.printf("  漏报 (泄露版判无泄漏): %d%n", falseNegative);
        System.out.printf("  误报 (修复版判泄漏):   %d%n", falsePositive);
        System.out.printf("  无法判定:              %d%n", inconclusive);
        System.out.printf("  正确率: %d/%d%n", total - falsePositive - falseNegative - inconclusive, total);
        boolean ok = falsePositive == 0 && falseNegative == 0 && inconclusive == 0;
        System.out.println(ok ? "验收通过：无漏报、无误报、无无法判定" : "验收未通过");
        System.exit(ok ? 0 : 1);
    }

    private static String shortName(Target t) {
        String n = t.className().substring(t.className().lastIndexOf('.') + 1);
        return n + "/" + t.methodName();
    }
}
