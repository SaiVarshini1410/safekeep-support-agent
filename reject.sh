#!/bin/bash
# Usage: ./reject.sh <investigationId> <proposalId> [user] [reason]
curl -s -u ${3:-sam}:demo -X POST -H "Content-Type: application/json" \
  -d "{\"reason\":\"${4:-not now}\"}" localhost:8080/api/investigations/$1/proposals/$2/reject | python3 -m json.tool
