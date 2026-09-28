#!/usr/bin/env bash
# Cluster-1 CI gate runner: install + validate + test for 14 JS/TS repos.
# Captures exit codes + parses node --test counts. Does NOT modify repo files.
set -u

REPOS="vector-bus vector-registry vector-kg-graph vector-kg-ingest vector-coa-fleet vector-coa-runtime vector-ci vector-security vector-observability vector-agents vector-docs vector-common vector-contracts vector-playground"
ROOT=/home/arm/Vector
OUTDIR="$ROOT/reports"
mkdir -p "$OUTDIR"
SUMMARY="$OUTDIR/cluster1-gate-summary.tsv"

# header
printf 'repo\tinstall_code\tvalidate_code\ttest_code\ttests_run\ttests_pass\ttests_fail\ttests_skipped\tstatus\n' > "$SUMMARY"

for r in $REPOS; do
  echo "============================================================"
  echo ">>> $r"
  cd "$ROOT/$r" || { echo "MISSING REPO DIR"; continue; }

  # npm install
  install_code=0
  echo "--- npm install ---"
  npm install --no-audit --no-fund > "$OUTDIR/${r}.install.log" 2>&1
  install_code=$?
  if [ $install_code -ne 0 ]; then
    # detect network failure
    if grep -qiE "ETIMEDOUT|ENOTFOUND|ECONNRESET|network.*timeout|fetch failed|getaddrinfo|EAI_AGAIN|npm error code EAI" "$OUTDIR/${r}.install.log"; then
      echo "NETWORK FAILURE during npm install"
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$r" "$install_code" "NA" "NA" "NA" "NA" "NA" "NA" "NETWORK-SKIP" >> "$SUMMARY"
      continue
    fi
  fi

  # validate
  echo "--- npm run validate ---"
  validate_code=0
  npm run validate > "$OUTDIR/${r}.validate.log" 2>&1
  validate_code=$?

  # test
  echo "--- npm test ---"
  test_code=0
  npm test > "$OUTDIR/${r}.test.log" 2>&1
  test_code=$?

  # parse node --test summary. node --test prints a per-process summary like:
  #   # tests <n>
  #   # pass <n>
  #   # fail <n>
  #   # skipped <n>
  # Older/newer node may print "tests <n>" inside subtest blocks too. We sum the
  # TOP-LEVEL (last) block counts: take the last occurrence of each line in the log.
  trun="NA"; tpass="NA"; tfail="NA"; tskip="NA"
  if [ -f "$OUTDIR/${r}.test.log" ]; then
    trun=$(grep -E '^# tests ' "$OUTDIR/${r}.test.log" | tail -1 | awk '{print $3}')
    tpass=$(grep -E '^# pass ' "$OUTDIR/${r}.test.log" | tail -1 | awk '{print $3}')
    tfail=$(grep -E '^# fail ' "$OUTDIR/${r}.test.log" | tail -1 | awk '{print $3}')
    tskip=$(grep -E '^# skipped ' "$OUTDIR/${r}.test.log" | tail -1 | awk '{print $3}')
  fi
  [ -z "$trun" ] && trun="NA"
  [ -z "$tpass" ] && tpass="NA"
  [ -z "$tfail" ] && tfail="NA"
  [ -z "$tskip" ] && tskip="NA"

  # status
  if [ "$validate_code" -ne 0 ]; then status="FAIL";
  elif [ "$test_code" -ne 0 ]; then status="FAIL";
  else status="PASS"; fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$r" "$install_code" "$validate_code" "$test_code" "$trun" "$tpass" "$tfail" "$tskip" "$status" >> "$SUMMARY"
  echo "  install=$install_code validate=$validate_code test=$test_code tests=$trun pass=$tpass fail=$tfail skip=$tskip status=$status"
done

echo "============================================================"
echo "DONE. Summary at $SUMMARY"
column -t -s $'\t' "$SUMMARY"
