#!/bin/bash
echo "=== Java ==="
ps aux | grep discord-admin | grep -v grep
echo "=== Log tail ==="
tail -40 /opt/discord-admin/logs/app.log 2>/dev/null
echo "=== Port wait ==="
for i in 1 2 3 4 5 6 7 8 9 10; do
  if ss -tlnp 2>/dev/null | grep -q 8090; then
    echo "LISTEN after ${i}0s"
    break
  fi
  sleep 5
done
echo "=== Local API ==="
curl -s -X POST http://localhost:8090/api/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123\"}" | head -c 300
echo
echo "=== Public API ==="
curl -s -X POST http://101.47.41.149/api/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123\"}" | head -c 300
echo
