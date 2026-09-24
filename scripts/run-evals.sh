#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../backend"

# Pattern matches scripts/build-corpus-vectors.sh (exec-maven-plugin is in backend/pom.xml).
MAIN_CLASS=com.brandsmith.api.eval.EvalHarness
MAIN_SRC=src/main/java/com/brandsmith/api/eval/EvalHarness.java

if [[ ! -f "$MAIN_SRC" ]]; then
  echo "Eval harness not implemented: $MAIN_SRC not found." >&2
  exit 1
fi

# -o: offline mode — deps are local; no network needed (no LLM key required either).
mvn -q -o -DskipTests compile exec:java -Dexec.mainClass="$MAIN_CLASS" \
  ${1:+-Dexec.args="$*"}
