#!/bin/bash
# Usage: ./show.sh 101   -> compact view of an investigation
curl -s -u sam:demo localhost:8080/api/investigations/$1 | python3 -c '
import json,sys
d=json.load(sys.stdin); i=d["investigation"]
print(i["displayId"], i["state"], "| resolution:", i["resolution"], "| tool calls:", i["toolCallsUsed"])
print("\nSUMMARY:", i["summary"])
print("\nEVIDENCE:")
for e in d["evidence"]: print(" ", e["evidenceId"], e["toolName"], e["argsJson"], e["status"], e["errorCode"] or "")
print("\nFINDINGS:")
for f in d["findings"]: print(" ", f["kind"], ("(downgraded)" if f["downgraded"] else ""), f["statement"], f["evidenceIdList"])
print("\nMISSING:", i["missingInformationList"])
print("\nRECOMMENDATIONS:")
for r in d["recommendations"]: print(" ", r["priority"], r["kind"], r["body"])
print("\nPROPOSALS:")
for p in d["proposals"]: print(" ", p["id"], p["actionName"], p["status"], "needs", p["requiredRole"], p["outcome"] or "")
print("\nMESSAGES:")
for m in d["messages"]: print(" ", m["author"], m["type"], ":", m["body"][:200])
'
