package tsidec.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 多重比较控制。对同一函数的多个探针同时做检验时，即使全部为真零假设，
 * 也会有约 alpha*m 个探针“显著”。这里用 Holm-Bonferroni 控制族错误率(FWER)，
 * 并在判定结论里明确是“族内显著”还是“原始显著未过校正”。
 */
public final class MultipleTesting {
    private MultipleTesting() {}

    public static final class Adjusted {
        public final String name;
        public final double rawP;
        public final double adjustedP;
        public final boolean significant; // Holm 校正后
        Adjusted(String name, double rawP, double adjustedP, boolean significant) {
            this.name = name;
            this.rawP = rawP;
            this.adjustedP = adjustedP;
            this.significant = significant;
        }
    }

    /** Holm step-down。输入 (name, rawP)，输出顺序与输入一致。 */
    public static List<Adjusted> holm(List<String> names, double[] rawP, double alpha) {
        int m = rawP.length;
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        java.util.Arrays.sort(order, Comparator.comparingDouble(i -> rawP[i]));

        double[] adj = new double[m];
        boolean[] sig = new boolean[m];
        double runningMax = 0;
        boolean stillSig = true;
        for (int rank = 0; rank < m; rank++) {
            int i = order[rank];
            double factor = m - rank; // Holm: 乘以剩余检验数
            double val = Math.min(1.0, Math.max(runningMax, factor * rawP[i]));
            runningMax = val;
            adj[i] = val;
            if (stillSig && rawP[i] <= alpha / factor) {
                sig[i] = true;
            } else {
                stillSig = false;
                sig[i] = false;
            }
        }
        List<Adjusted> out = new ArrayList<>(m);
        for (int i = 0; i < m; i++) out.add(new Adjusted(names.get(i), rawP[i], adj[i], sig[i]));
        return out;
    }
}
