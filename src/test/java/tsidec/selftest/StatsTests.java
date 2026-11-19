package tsidec.selftest;

import tsidec.stats.*;

import java.util.Random;
import java.util.List;

/**
 * 统计模块的单元测试。这些都是判定门槛的数学基础，错了整个工具就没有意义。
 * 不做成 JUnit，因为整个项目刻意保持"零外部测试依赖"，一条命令即可跑。
 */
final class StatsTests {
    private StatsTests() {}

    static int failures = 0;

    static void run() {
        System.out.println("== 统计模块 ==");
        tDistribution();
        permutationAndSignFlip();
        trendDetection();
        multipleTesting();
        robustStatistics();
        proportion();
        System.out.println(failures == 0 ? "  统计模块: 全部通过" : "  统计模块: " + failures + " 项失败");
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) { failures++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    private static void tDistribution() {
        // t 分布的双侧临界值应与教科书一致
        double t975_df10 = StudentT.criticalValue(0.05, 10);
        check("t.975(df=10)=2.228", Math.abs(t975_df10 - 2.228) < 0.01, "got " + t975_df10);
        double t975_df1 = StudentT.criticalValue(0.05, 1);
        check("t.975(df=1)=12.706", Math.abs(t975_df1 - 12.706) < 0.02, "got " + t975_df1);
        double t975_df1000 = StudentT.criticalValue(0.05, 1000);
        check("t.975(df=1000)≈1.962", Math.abs(t975_df1000 - 1.962) < 0.01, "got " + t975_df1000);
        // cdf 在 0 处应恰好 0.5，对称性
        check("cdf(0,df)=0.5", Math.abs(StudentT.cdf(0, 7) - 0.5) < 1e-9, "");
        check("cdf 对称", Math.abs(StudentT.cdf(2, 9) + StudentT.cdf(-2, 9) - 1) < 1e-9, "");
        // 已知 t=2.0, df=10 的双侧 p ≈ 0.0734
        double p = StudentT.twoSidedP(2.0, 10);
        check("p(t=2,df=10)≈0.0734", Math.abs(p - 0.0734) < 0.002, "got " + p);
    }

    private static void permutationAndSignFlip() {
        Random rng = new Random(1);
        // 真实无差异：符号翻转 p 应均匀，至少不能系统性偏小
        double[] nullData = new double[500];
        for (int i = 0; i < nullData.length; i++) nullData[i] = rng.nextGaussian();
        double nullP = SignFlipTest.test(nullData, 2000, new Random(2)).pValue;
        check("零假设 p 不应过小", nullP > 0.01, "p=" + nullP);

        // 强差异：p 应极小
        double[] shifted = new double[500];
        for (int i = 0; i < shifted.length; i++) shifted[i] = rng.nextGaussian() + 1.0;
        double strongP = SignFlipTest.test(shifted, 2000, new Random(3)).pValue;
        check("强差异 p<1e-3", strongP < 1e-3, "p=" + strongP);

        // 两样本置换：完全分离的两组
        double[] a = new double[20], b = new double[20];
        for (int i = 0; i < 20; i++) { a[i] = i; b[i] = 100 + i; }
        double p2 = PermutationTest.meanDifference(a, b, 4000, new Random(4)).pValue;
        check("完全分离两组 p 极小", p2 < 0.001, "p=" + p2);
    }

    private static void trendDetection() {
        // 纯上升趋势，应被判显著
        double[] rising = new double[500];
        for (int i = 0; i < rising.length; i++) rising[i] = i * 0.001;
        Trend.Result tr = Trend.spearman(rising);
        check("检测上升趋势", tr.rho > 0.95 && tr.significant(1e-6), "rho=" + tr.rho);
        // 纯噪声，不应被判趋势
        Random r = new Random(5);
        double[] noise = new double[500];
        for (int i = 0; i < noise.length; i++) noise[i] = r.nextGaussian();
        Trend.Result tn = Trend.spearman(noise);
        check("噪声不报趋势", !tn.significant(1e-3), "p=" + tn.pValue);
    }

    private static void multipleTesting() {
        List<String> names = List.of("a", "b", "c", "d");
        double[] p = {0.001, 0.02, 0.5, 0.6};
        var adj = MultipleTesting.holm(names, p, 0.05);
        // a 显著（0.001 <= 0.05/4）
        check("Holm: a 显著", adj.get(0).significant, "");
        // b (0.02) 在 a 通过后与 0.05/3=0.0167 比，不通过
        check("Holm: b 不显著", !adj.get(1).significant, "");
    }

    private static void robustStatistics() {
        // 加入极端离群值后，中位数不应移动，均值应被拉偏
        double[] v = {1, 2, 3, 4, 5, 1_000_000};
        check("中位数抗离群", Math.abs(Descriptive.median(v) - 3.5) < 1e-9, "med=" + Descriptive.median(v));
        check("均值被拉偏", Descriptive.mean(v) > 1000, "");
        // 截尾后应接近真实中心
        double[] t = Descriptive.trimByDeviation(v, 0.6);
        check("截尾后接近 3", Math.abs(Descriptive.mean(t) - 3) < 1.5, "mean=" + Descriptive.mean(t));
    }

    private static void proportion() {
        var w = Proportion.wilson95(8, 10);
        check("Wilson 区间包含 0.8", w.lo < 0.8 && w.hi > 0.8, w.lo + ".." + w.hi);
        // 精确二项：10 次中 9 次成功，H0 p=0.5 的双侧 p = 0.0215
        double p = Proportion.exactBinomialTwoSided(9, 10, 0.5);
        check("二项 p≈0.0215", Math.abs(p - 0.0215) < 0.002, "got " + p);
    }
}
