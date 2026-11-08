#!/bin/bash
# Usage: ./send.sh S-1A   -> sends that scenario's event through the real API
SCN=${1:-S-1A}
curl -s -u sam:demo localhost:8080/api/simulator/scenarios > /tmp/scn.json
python3 - "$SCN" <<'PY'
import json, sys, subprocess, uuid, datetime
scn = sys.argv[1]
s = next(x for x in json.load(open('/tmp/scn.json')) if x['id'] == scn)
key = {'backup-server': 'demo-backup-key', 'storage-server': 'demo-storage-key'}[s['application']]
body = {"eventId": "evt-" + uuid.uuid4().hex[:8], "application": s['application'], "eventType": s['eventType'],
        "resource": s['resource'], "timestamp": datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
        "details": s['details']}
out = subprocess.run(['curl', '-s', '-X', 'POST', 'localhost:8080/api/events', '-H', 'X-Api-Key: ' + key,
                      '-H', 'X-Demo-Scenario: ' + scn, '-H', 'Content-Type: application/json', '-d', json.dumps(body)],
                     capture_output=True, text=True).stdout
print(out)
PY
