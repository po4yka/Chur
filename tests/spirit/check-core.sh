#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${KOTLIN_COMPILER_LIB:?Set KOTLIN_COMPILER_LIB to a Kotlin compiler lib directory}"
stdlib="$(find "$KOTLIN_COMPILER_LIB" -maxdepth 1 -name 'kotlin-stdlib-[0-9]*.jar' | head -1)"
[[ -n "$stdlib" ]]
out="${TMPDIR:-/tmp}/chur-spirit-core-check"
mkdir -p "$out"
java -Xmx256m -cp "$KOTLIN_COMPILER_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$stdlib" \
  shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/spirit/SpiritInteraction.kt \
  shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/spirit/SpiritAnimation.kt \
  shared/app/src/commonTest/kotlin/dev/po4yka/chur/app/spirit/SpiritCoreAssertions.kt \
  shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/spirit/SpiritPose.kt \
  shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/spirit/SpiritTail.kt \
  tests/spirit/SpiritInteractionCheck.kt -d "$out/check.jar"
java -cp "$out/check.jar:$stdlib" SpiritInteractionCheckKt
