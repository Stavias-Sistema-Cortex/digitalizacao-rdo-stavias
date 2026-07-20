#!/usr/bin/env bash
set -euo pipefail

LIMIT="${1:-10}"

curl -s "http://localhost:8080/api/assets/import/runs?limit=${LIMIT}" \
  | jq -r '
      if length == 0 then
        "No sync runs found"
      else
        ["STATUS", "READ", "INSERTED", "UPDATED", "DEACTIVATED", "STARTED", "FINISHED", "RUN_ID"],
        (.[] | [
          .status,
          (.recordsRead | tostring),
          (.recordsInserted | tostring),
          (.recordsUpdated | tostring),
          (.recordsDeactivated | tostring),
          (.startedAt // "-"),
          (.finishedAt // "-"),
          .id
        ])
        | @tsv
      end
    ' \
  | column -t -s $'\t'
