package tsidec.report;

import tsidec.core.Analysis;
import tsidec.selfcheck.SelfCheck;

import java.util.List;

/**
 * JSON 报告。手写序列化而不引第三方库：输出是构建产物，
 * 少一个依赖就少一处需要审计的供应链面。
 */
public final class JsonReport {
    private JsonReport() {}

    public static String render(SelfCheck.Result cal, List<Analysis> analyses,
                                MeasurementEngineMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"tool\": \"tsidec\",\n");
        sb.append("  \"version\": \"1.0\",\n");
        sb.append("  \"jvm\": ").append(q(meta.jvm)).append(",\n");
        sb.append("  \"os\": ").append(q(meta.os)).append(",\n");
        sb.append("  \"cpu\": ").append(q(meta.cpu)).append(",\n");
        sb.append("  \"config\": {\"callsPerBatch\": ").append(meta.callsPerBatch)
          .append(", \"rounds\": ").append(meta.rounds).append("},\n");

        sb.append("  \"calibration\": {\n");
        sb.append("    \"passed\": ").append(cal.passed).append(",\n");
        sb.append("    \"biasNs\": ").append(num(cal.biasNs)).append(",\n");
        sb.append("    \"biasSe\": ").append(num(cal.biasSe)).append(",\n");
        sb.append("    \"nsPerAluOp\": ").append(num(cal.nsPerAluOp)).append(",\n");
        sb.append("    \"noiseSdNs\": ").append(num(cal.noiseSdNs)).append(",\n");
        sb.append("    \"failureReason\": ").append(q(cal.failureReason)).append("\n");
        sb.append("  },\n");

        sb.append("  \"targets\": [\n");
        for (int i = 0; i < analyses.size(); i++) {
            Analysis a = analyses.get(i);
            sb.append("    {\n");
            sb.append("      \"name\": ").append(q(a.target.name())).append(",\n");
            sb.append("      \"class\": ").append(q(a.target.className())).append(",\n");
            sb.append("      \"method\": ").append(q(a.target.methodName())).append(",\n");
            sb.append("      \"secret\": ").append(q(a.target.secretDescription())).append(",\n");
            sb.append("      \"expectation\": ").append(q(a.target.expectation().name())).append(",\n");
            sb.append("      \"verdict\": ").append(q(a.verdict().name())).append(",\n");
            sb.append("      \"timing\": {");
            sb.append("\"effectNs\": ").append(num(a.timingVerdict.effectNs));
            sb.append(", \"seNs\": ").append(num(a.timingVerdict.se));
            sb.append(", \"tStat\": ").append(num(a.timingVerdict.tStat));
            sb.append(", \"welchP\": ").append(num(a.timingVerdict.welchP));
            sb.append(", \"signFlipP\": ").append(num(a.timingVerdict.permP));
            sb.append(", \"significant\": ").append(a.timingVerdict.verdict == Analysis.Verdict.LEAK_TIMING_ONLY);
            sb.append("},\n");
            sb.append("      \"structure\": {");
            sb.append("\"instrumented\": ").append(a.instrumented);
            sb.append(", \"insnsClassA\": ").append(a.insnsClassA);
            sb.append(", \"insnsClassB\": ").append(a.insnsClassB);
            sb.append(", \"blocks\": [");
            for (int j = 0; j < a.blockDiffs.size(); j++) {
                Analysis.BlockDiff d = a.blockDiffs.get(j);
                if (j > 0) sb.append(", ");
                sb.append("{\"line\": ").append(d.meta.line)
                  .append(", \"method\": ").append(q(d.meta.method.split("\\(")[0]))
                  .append(", \"hitsA\": ").append(d.hitsA)
                  .append(", \"hitsB\": ").append(d.hitsB)
                  .append(", \"insnCount\": ").append(d.meta.insnCount)
                  .append(", \"branch\": ").append(d.meta.terminalBranch).append('}');
            }
            sb.append("]},\n");
            sb.append("      \"reason\": ").append(q(a.timingVerdict.reason)).append('\n');
            sb.append("    }").append(i + 1 < analyses.size() ? "," : "").append('\n');
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    public static final class MeasurementEngineMeta {
        public String jvm, os, cpu;
        public int callsPerBatch, rounds;
    }

    private static String num(double v) {
        if (Double.isNaN(v)) return "null";
        if (Double.isInfinite(v)) return v > 0 ? "1e999" : "-1e999";
        return String.format("%.6g", v);
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
