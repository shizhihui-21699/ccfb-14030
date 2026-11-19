package tsidec.instrument;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 基本块级插桩：在每个基本块入口插入 {@code Counters.block(id)}。
 *
 * 作用：得到"每次调用执行了多少条字节码"（确定性代理），以及"哪一块的执行次数
 * 随秘密变化"（精确定位）。它不改变任何跳转语义——只在块首插一次计数调用，
 * 比逐分支插桩的扰动更小，也更接近"实际工作量"。
 *
 * 洞见：Java 字节码的局部数组访问若越界会抛异常，JIT 无法把它优化成无界访存，
 * 因此"执行了多少条字节码"是有意义的确定性证据；而"每条指令花几纳秒"才是
 * 会被噪声污染的量。前者定性、后者定量，两者结合才能既不错杀也不漏杀。
 */
public final class BasicBlockInstrumenter {

    public static final class BlockMeta {
        public final int id;
        public final String owner;
        public final String method;
        public final int line;
        public final int startBci;
        public final int insnCount;
        public final boolean terminalBranch;
        public final int firstInstIndex;
        public final int lastInstIndex;

        BlockMeta(int id, String owner, String method, int line, int startBci, int insnCount,
                  boolean terminalBranch, int firstInstIndex, int lastInstIndex) {
            this.id = id; this.owner = owner; this.method = method; this.line = line;
            this.startBci = startBci; this.insnCount = insnCount;
            this.terminalBranch = terminalBranch;
            this.firstInstIndex = firstInstIndex; this.lastInstIndex = lastInstIndex;
        }
    }

    public static final class Result {
        public final byte[] bytecode;
        public final List<BlockMeta> blocks;
        Result(byte[] bytecode, List<BlockMeta> blocks) {
            this.bytecode = bytecode; this.blocks = blocks;
        }
    }

    private final int firstBlockId;

    public BasicBlockInstrumenter(int firstBlockId) { this.firstBlockId = firstBlockId; }

    public Result instrument(byte[] original, Predicate<String> methodFilter) {
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, ClassReader.EXPAND_FRAMES);
        List<BlockMeta> blocks = new ArrayList<>();
        int next = firstBlockId;

        for (MethodNode mn : cn.methods) {
            if (mn.instructions.size() == 0) continue;
            if (methodFilter != null && !methodFilter.test(mn.name)) continue;
            next = instrumentMethod(cn, mn, next, blocks);
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        cn.accept(cw);
        return new Result(cw.toByteArray(), blocks);
    }

    private int instrumentMethod(ClassNode cn, MethodNode mn, int next, List<BlockMeta> out) {
        // 去掉调试/帧节点，只保留"真正的指令"，便于按索引分析
        List<AbstractInsnNode> real = new ArrayList<>();
        int[] lineOf = new int[mn.instructions.size()];
        int curLine = 0;
        int bci = 0;
        int[] bciOf = new int[mn.instructions.size()];
        for (int i = 0; i < mn.instructions.size(); i++) {
            AbstractInsnNode in = mn.instructions.get(i);
            bciOf[i] = bci;
            if (in instanceof LineNumberNode) curLine = ((LineNumberNode) in).line;
            lineOf[i] = curLine;
            if (!(in instanceof LineNumberNode) && !(in instanceof FrameNode)) {
                real.add(in);
            }
            bci += 1; // 简化：以指令数为单位，报告里作为稳定顺序号
        }
        if (real.isEmpty()) return next;

        // 1) 识别基本块起点：方法入口、每个跳转目标、每个条件跳转/switch 之后的下一条
        java.util.Set<AbstractInsnNode> leaders = new java.util.HashSet<>();
        leaders.add(real.get(0));
        for (AbstractInsnNode in : real) {
            int op = in.getOpcode();
            if (in instanceof JumpInsnNode) {
                leaders.add(((JumpInsnNode) in).label);
            }
            if (in instanceof TableSwitchInsnNode) {
                var sw = (TableSwitchInsnNode) in;
                leaders.add(sw.dflt);
                for (LabelNode l : sw.labels) leaders.add(l);
            }
            if (in instanceof LookupSwitchInsnNode) {
                var sw = (LookupSwitchInsnNode) in;
                leaders.add(sw.dflt);
                for (LabelNode l : sw.labels) leaders.add(l);
            }
            boolean isCondJump = op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE;
            boolean isSwitch = (op == Opcodes.TABLESWITCH || op == Opcodes.LOOKUPSWITCH);
            if (isCondJump || isSwitch || op == Opcodes.GOTO) {
                AbstractInsnNode nxt = nextReal(in);
                if (nxt != null) leaders.add(nxt);
            }
        }

        // 2) 扫描生成基本块
        List<List<AbstractInsnNode>> blockInsns = new ArrayList<>();
        List<Integer> blockStartBci = new ArrayList<>();
        List<Integer> blockLine = new ArrayList<>();
        List<Boolean> blockBranch = new ArrayList<>();
        List<Integer> blockFirstIdx = new ArrayList<>();
        List<Integer> blockLastIdx = new ArrayList<>();

        List<AbstractInsnNode> cur = null;
        int curStart = 0, curBlockLine = 0, curFirst = 0;
        boolean curBranch = false;
        for (int i = 0; i < mn.instructions.size(); i++) {
            AbstractInsnNode in = mn.instructions.get(i);
            if (in instanceof LineNumberNode) {
                curBlockLine = Math.max(curBlockLine, ((LineNumberNode) in).line);
            }
            if (in instanceof FrameNode || in instanceof LineNumberNode) continue;

            boolean isLeader = leaders.contains(in);
            if (isLeader || cur == null) {
                if (cur != null) {
                    blockInsns.add(cur); blockStartBci.add(curStart);
                    blockLine.add(curBlockLine); blockBranch.add(curBranch);
                    blockFirstIdx.add(curFirst); blockLastIdx.add(i - 1);
                }
                cur = new ArrayList<>();
                curStart = i;
                curBlockLine = lineOf[i];
                curFirst = i;
                curBranch = false;
            }
            cur.add(in);
            int op = in.getOpcode();
            if ((op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE)
                    || op == Opcodes.TABLESWITCH || op == Opcodes.LOOKUPSWITCH) {
                curBranch = true;
            }
        }
        if (cur != null && !cur.isEmpty()) {
            blockInsns.add(cur); blockStartBci.add(curStart);
            blockLine.add(curLine); blockBranch.add(curBranch);
            blockFirstIdx.add(curFirst); blockLastIdx.add(mn.instructions.size() - 1);
        }

        // 3) 逐块在入口插入计数调用
        String owner = cn.name;
        String method = mn.name + mn.desc;
        int[] sizes = new int[blockInsns.size()];
        for (int b = 0; b < blockInsns.size(); b++) {
            int id = next++;
            sizes[b] = blockInsns.get(b).size();
            AbstractInsnNode first = blockInsns.get(b).get(0);
            InsnList rec = new InsnList();
            rec.add(new LdcInsnNode(id));
            rec.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "tsidec/instrument/Counters", "block", "(I)V", false));
            mn.instructions.insertBefore(first, rec);
            out.add(new BlockMeta(id, owner, method, blockLine.get(b), blockStartBci.get(b),
                    blockInsns.get(b).size(), blockBranch.get(b),
                    blockFirstIdx.get(b), blockLastIdx.get(b)));
        }
        return next;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode in) {
        AbstractInsnNode n = in.getNext();
        while (n != null && (n instanceof LineNumberNode || n instanceof FrameNode)) n = n.getNext();
        return n;
    }
}
