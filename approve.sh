#!/bin/bash
# Usage: ./approve.sh <investigationId> <proposalId> [user]   (user: alice, sam, adam; default sam)
curl -s -u ${3:-sam}:demo -X POST localhost:8080/api/investigations/$1/proposals/$2/approve | python3 -m json.tool
