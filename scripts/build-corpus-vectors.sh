#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../backend"
mvn -q -DskipTests compile exec:java \
  -Dexec.mainClass=com.brandsmith.api.embedding.CorpusVectorBuilder \
  ${1:+-Dexec.args="$*"}
