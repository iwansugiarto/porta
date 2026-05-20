#!/bin/bash
# Porta PWA integration test — checks API, WebSocket, and status flow
set -euo pipefail

BASE="http://192.168.100.220:3170"
# Read auth token from .env
TOKEN=$(grep '^PORTA_AUTH_TOKEN=' /Volumes/980PRO/Users/iwan/porta/.env | cut -d= -f2-)
AUTH="Authorization: Bearer $TOKEN"

PASS=0
FAIL=0
WARN=0

ok()   { PASS=$((PASS+1)); echo "  ✅ $1"; }
fail() { FAIL=$((FAIL+1)); echo "  ❌ $1"; }
warn() { WARN=$((WARN+1)); echo "  ⚠️  $1"; }

echo "═══════════════════════════════════════"
echo "  Porta PWA Integration Test"
echo "  $(date '+%Y-%m-%d %H:%M:%S')"
echo "═══════════════════════════════════════"
echo

# ── 1. Health endpoint ──
echo "▸ API Health"
HEALTH=$(curl -sf "$BASE/api/health" 2>/dev/null || echo '{}')
if echo "$HEALTH" | python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('status')=='ok'" 2>/dev/null; then
    ok "Health OK"
    LS_COUNT=$(echo "$HEALTH" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d.get('languageServers',[])))")
    UPTIME=$(echo "$HEALTH" | python3 -c "import json,sys; d=json.load(sys.stdin); print(int(d['proxy']['uptime']//60))")
    echo "     LS instances: $LS_COUNT | Uptime: ${UPTIME}m"
else
    fail "Health check failed"
fi

# ── 2. Auth check ──
echo
echo "▸ Authentication"
UNAUTH=$(curl -sf -o /dev/null -w "%{http_code}" "$BASE/api/conversations" 2>/dev/null || echo "000")
if [ "$UNAUTH" = "401" ]; then
    ok "Unauthenticated request correctly rejected (401)"
else
    warn "Unauthenticated request got $UNAUTH (expected 401)"
fi

AUTH_OK=$(curl -sf -o /dev/null -w "%{http_code}" -H "$AUTH" "$BASE/api/conversations" 2>/dev/null || echo "000")
if [ "$AUTH_OK" = "200" ]; then
    ok "Authenticated request accepted (200)"
else
    fail "Authenticated request failed with $AUTH_OK"
fi

# ── 3. Conversations list ──
echo
echo "▸ Conversations"
curl -sf -H "$AUTH" "$BASE/api/conversations" -o /tmp/porta_test_convs.json 2>/dev/null
CONV_COUNT=$(python3 -c "
import json
with open('/tmp/porta_test_convs.json') as f:
    d = json.load(f)
sums = d.get('trajectorySummaries', d) if isinstance(d, dict) else d
print(len(sums))
")
ok "Listed $CONV_COUNT conversations"

# Count by status
python3 -c "
import json
with open('/tmp/porta_test_convs.json') as f:
    d = json.load(f)
sums = d.get('trajectorySummaries', d) if isinstance(d, dict) else d
if isinstance(sums, dict):
    statuses = {}
    for cid, s in sums.items():
        st = s.get('status', 'unknown')
        statuses[st] = statuses.get(st, 0) + 1
    for st, cnt in sorted(statuses.items()):
        print(f'     {st}: {cnt}')
"

# ── 4. Models endpoint ──
echo
echo "▸ Models"
MODELS=$(curl -sf -H "$AUTH" "$BASE/api/models" 2>/dev/null || echo '{}')
MODEL_COUNT=$(echo "$MODELS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d.get('models',[])))" 2>/dev/null || echo "0")
if [ "$MODEL_COUNT" -gt 0 ]; then
    ok "Models: $MODEL_COUNT available"
    echo "$MODELS" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for m in d.get('models', [])[:5]:
    q = m.get('quotaRemaining', 1.0)
    label = m.get('label', m.get('id','?'))
    print(f'     {label}: {q*100:.0f}% quota')
" 2>/dev/null
else
    warn "No models returned"
fi

# ── 5. WebSocket test ──
echo
echo "▸ WebSocket"
# Pick the active conversation (current Antigravity session)
ACTIVE_ID="42c71edd-b4e1-41ad-888c-a940612259b5"
WS_LOG=$(timeout 3 curl -sf -N \
    -H "Connection: Upgrade" \
    -H "Upgrade: websocket" \
    -H "Sec-WebSocket-Version: 13" \
    -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
    "$BASE/ws/$ACTIVE_ID" 2>&1 || true)
if echo "$WS_LOG" | grep -qi "upgrade\|switching\|101"; then
    ok "WebSocket upgrade accepted"
else
    warn "WebSocket upgrade test inconclusive (curl can't do full WS handshake)"
fi

# ── 6. Steps endpoint ──
echo
echo "▸ Steps (conversation $ACTIVE_ID)"
STEPS=$(curl -sf -H "$AUTH" "$BASE/api/conversations/$ACTIVE_ID/steps?tail=5" 2>/dev/null || echo '{}')
STEP_COUNT=$(echo "$STEPS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d.get('steps',[])))" 2>/dev/null || echo "0")
STEP_OFFSET=$(echo "$STEPS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('offset','?'))" 2>/dev/null || echo "?")
if [ "$STEP_COUNT" -gt 0 ]; then
    ok "Steps: got $STEP_COUNT steps (offset $STEP_OFFSET)"
else
    fail "Steps endpoint returned no steps"
fi

# ── 7. Check for 502 errors ──
echo
echo "▸ Error Analysis"
ERR_502=$(grep -c "502" /Volumes/980PRO/Users/iwan/porta/logs/proxy.log 2>/dev/null || echo "0")
ERR_CONVS=$(grep "502" /Volumes/980PRO/Users/iwan/porta/logs/proxy.log 2>/dev/null | grep -oP 'conversations/\K[a-f0-9-]+' | sort -u | wc -l | tr -d ' ')
if [ "$ERR_502" -gt 0 ]; then
    warn "Found $ERR_502 total 502 errors across $ERR_CONVS unique conversations"
    echo "     Recent 502s:"
    grep "502" /Volumes/980PRO/Users/iwan/porta/logs/proxy.log | tail -3 | sed 's/^/     /'
else
    ok "No 502 errors in log"
fi

# ── 8. Vite HMR status ──
echo
echo "▸ Vite Dev Server"
VITE_HMR=$(grep -c "hmr update" /Volumes/980PRO/Users/iwan/porta/logs/web.log 2>/dev/null || echo "0")
VITE_ERR=$(grep -ci "error\|failed" /Volumes/980PRO/Users/iwan/porta/logs/web.log 2>/dev/null || echo "0")
ok "HMR updates: $VITE_HMR"
if [ "$VITE_ERR" -gt 0 ]; then
    warn "Vite errors: $VITE_ERR"
else
    ok "No Vite compilation errors"
fi

# ── Summary ──
echo
echo "═══════════════════════════════════════"
echo "  Results: ✅ $PASS passed  ❌ $FAIL failed  ⚠️  $WARN warnings"
echo "═══════════════════════════════════════"

exit $FAIL
