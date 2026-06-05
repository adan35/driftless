#!/usr/bin/env bash
#
# Spec 09 demo — drive the RUNNING composed stack and prove zero drift under faults.
#
# It calls the app's seed-only demo endpoint (POST /demo/run), which seeds funded accounts, drives a
# realistic authorize/capture/reverse load mix against the live saga (whose partner leg is a real
# network hop to the partner-simulator container), injects partner faults via the simulator's control
# plane, settles the recovery sweep, then reconciles. The headline a reviewer reads: zero drift.
#
# Usage:  scripts/demo.sh [count] [concurrency] [fault-mix]
#   fault-mix ∈ NONE | TIMEOUT | FAIL_BEFORE_RESPONSE | DECLINE | DUPLICATE | MIXED  (default MIXED)
#
# Env:    APP_URL (default http://localhost:8080)

set -euo pipefail

APP_URL="${APP_URL:-http://localhost:8080}"
COUNT="${1:-60}"
CONCURRENCY="${2:-8}"
FAULT_MIX="${3:-MIXED}"

echo "==> Driftless demo against ${APP_URL}"
echo "    count=${COUNT} concurrency=${CONCURRENCY} faultMix=${FAULT_MIX}"
echo

echo "==> Waiting for the app to report healthy ..."
for _ in $(seq 1 60); do
  if curl -fsS "${APP_URL}/actuator/health" >/dev/null 2>&1; then
    echo "    app is UP"
    break
  fi
  sleep 2
done

echo
echo "==> POST /demo/run (seed -> load -> fault injection -> settle -> reconcile) ..."
RESPONSE="$(curl -fsS -X POST "${APP_URL}/demo/run" \
  -H 'Content-Type: application/json' \
  -d "{\"count\":${COUNT},\"concurrency\":${CONCURRENCY},\"faultMix\":\"${FAULT_MIX}\"}")"

echo
echo "==> Demo result:"
python3 - "$RESPONSE" <<'PY'
import json, sys
data = json.loads(sys.argv[1])
load = data["load"]
recon = data["reconciliation"]
print(f"    accounts seeded     : {data['accountsSeeded']} (funded {data['fundingMinor']} minor each)")
print(f"    faults injected     : {', '.join(data['faultsInjected']) or 'none'}")
print(f"    operations          : {load['totalOperations']}")
print(f"    approved/declined   : {load['approved']} / {load['declined']}")
print(f"    captures/reversals  : {load['captures']} / {load['reversals']}")
print(f"    compensated (faults): {load['compensated']}")
print(f"    failed              : {load['failed']}")
print(f"    throughput          : {load['throughputPerSecond']:.1f}/s  p99={load['p99LatencyMillis']}ms")
print()
print(f"    RECONCILIATION      : passed={recon['passed']}  totalDriftMinor={recon['totalDriftMinor']}")
if not (recon["passed"] and recon["totalDriftMinor"] == 0):
    print("    !! drift detected -- demo FAILED", file=sys.stderr)
    sys.exit(1)
print("    [OK] ZERO DRIFT -- the books summed to zero even though faults fired")
PY

echo
echo "==> GET /reconciliation/latest:"
curl -fsS "${APP_URL}/reconciliation/latest" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("    id=%s  passed=%s  totalDriftMinor=%s" % (d["id"], d["passed"], d["totalDriftMinor"]))'

echo
echo "==> Headline metrics (/actuator/prometheus):"
curl -fsS "${APP_URL}/actuator/prometheus" \
  | grep -E 'driftless_recon_drift_amount|driftless_auth_compensating_reversals_total' \
  | grep -v '^#' || true

echo
echo "==> Done. Watch the live proof at http://localhost:3000 -> Zero Drift."
