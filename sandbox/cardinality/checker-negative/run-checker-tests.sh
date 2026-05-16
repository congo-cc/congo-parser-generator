#!/bin/sh
# Runs congocc against negative cardinality checker fixtures.
# Usage: run-checker-tests.sh <path-to-congocc.jar>
set -e
CONGOCC_JAR="${1:?usage: run-checker-tests.sh path/to/congocc.jar}"
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
FAIL=0

expect_error() {
  file=$1
  needle=$2
  echo "== expect error: $file ($needle)"
  set +e
  out=$(java -jar "$CONGOCC_JAR" -n -q "$file" 2>&1)
  code=$?
  set -e
  if [ "$code" -eq 0 ]; then
    echo "FAIL: $file expected failure (exit 0)"
    echo "$out"
    FAIL=1
    return
  fi
  if ! echo "$out" | grep -q "$needle"; then
    echo "FAIL: $file expected message containing: $needle"
    echo "$out"
    FAIL=1
  fi
}

expect_warning() {
  file=$1
  needle=$2
  echo "== expect warning: $file ($needle)"
  set +e
  out=$(java -jar "$CONGOCC_JAR" -n -q "$file" 2>&1)
  code=$?
  set -e
  if [ "$code" -ne 0 ]; then
    echo "FAIL: $file expected success with warning (exit $code)"
    echo "$out"
    FAIL=1
    return
  fi
  if ! echo "$out" | grep -q "$needle"; then
    echo "FAIL: $file expected warning containing: $needle"
    echo "$out"
    FAIL=1
  fi
}

expect_error OrphanRCA.ccc "not within a ZeroOrMore or OneOrMore"
expect_error RCAInZeroOrOne.ccc "ZeroOrOne"
expect_warning TelescopedSequence.ccc "effective minimum exceeds effective maximum"

if [ "$FAIL" -ne 0 ]; then
  echo "Checker negative tests FAILED"
  exit 1
fi
echo "Checker negative tests passed."
