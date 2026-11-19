#!/bin/sh
# tsidec 构建脚本。只用 JDK + 仓库内自带的 ASM jar，不依赖网络或包管理器。
set -eu

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
OUT="${OUT:-out}"
ASM="lib/asm-9.9.1.jar:lib/asm-tree-9.9.jar"

case "${1:-build}" in
  build)
    rm -rf "$OUT"
    mkdir -p "$OUT"
    $JAVAC -encoding UTF-8 --release 21 -cp "$ASM" -d "$OUT" $(find src/main/java -name '*.java')
    echo "built -> $OUT"
    ;;
  run)
    shift || true
    exec $JAVA -Xms2g -Xmx2g -cp "$OUT:$ASM" tsidec.cli.Main "$@"
    ;;
  test)
    $JAVAC -encoding UTF-8 --release 21 -cp "$ASM" -d "$OUT" $(find src/main/java -name '*.java')
    $JAVAC -encoding UTF-8 -cp "$OUT" -d out-test $(find src/test/java -name '*.java')
    exec $JAVA -Xms2g -Xmx2g -cp "$OUT:out-test:$ASM" tsidec.selftest.AllTests
    ;;
  *)
    echo "usage: $0 [build|run|test] [args...]" >&2
    exit 64
    ;;
esac
