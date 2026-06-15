#!/usr/bin/env bash
set -euo pipefail

curl -s -X POST http://localhost:8080/api/assets/import/zld \
  | jq -r '
      "Import completed",
      "Source: \(.sourceDatabase).\(.sourceTable)",
      "Records read: \(.recordsRead)",
      "Records processed: \(.recordsProcessed)"
    '
