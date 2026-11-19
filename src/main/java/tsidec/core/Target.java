package tsidec.core;

/**
 * 被测目标 = 一个静态方法 + 一种"秘密如何在两类输入间变化"的构造方式。
 *
 * 关键方法学约束：**两类输入只允许在秘密部分不同，公开部分逐位相同**。
 * 若公开部分也不同，测到的是"输入不同"而非"秘密不同"，是最常见的假阳性来源。
 *
 * 每个目标检验的命题都是同一个：*这个函数的执行时间/执行路径是否依赖于秘密？*
 * 注意这里**不**拿"泄露版"和"修复版"互相比时间——那是两段不同代码，
 * 比较结果没有意义。每个实现都被独立地检验"是否随秘密变化"。
 */
public interface Target {
    /** 简短名，出现在报告里。 */
    String name();

    /** 被测类全限定名（点分隔）。 */
    String className();

    /** 要检验的方法名。 */
    String methodName();

    /** 实参类型。 */
    Class<?>[] paramTypes();

    /** 秘密被分成几类（默认 2）。 */
    default int secretClasses() { return 2; }

    /**
     * 构造第 {@code secretClass} 类、第 {@code i} 次调用的实参。
     * 公开部分必须与其它类别逐位相同。
     */
    Object[] argsFor(int secretClass, int i);

    /** 这段代码的预期结论，仅用于自测断言；工具本身不读取它。 */
    default Expectation expectation() { return Expectation.UNKNOWN; }

    /** 人类可读：两类秘密到底差在哪。 */
    String secretDescription();

    /**
     * 这个目标在真实攻击下的"秘密对比"强度说明。
     * 例如"首字节不匹配 vs 末字节不匹配"，用于解释效应量的含义。
     */
    default String contrastDescription() { return secretDescription(); }

    enum Expectation { LEAK, NO_LEAK, UNKNOWN }
}
