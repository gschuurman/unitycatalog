#!/usr/bin/env bash
# End-to-end check of credential vending against a real S3-compatible store (RustFS).
#
# Expects RustFS on $S3_ENDPOINT with root keys $S3_ACCESS_KEY / $S3_SECRET_KEY, a built server
# (build/sbt package) and python3 with boto3. Starts the UC server with both a per-bucket config
# and a master-role config pointing at RustFS, then runs vending_checks.py against it. The repo's
# server.properties and H2 database are restored afterwards.
set -euo pipefail

export S3_ENDPOINT="${S3_ENDPOINT:-http://127.0.0.1:9000}"
export S3_ACCESS_KEY="${S3_ACCESS_KEY:-rustfsadmin}"
export S3_SECRET_KEY="${S3_SECRET_KEY:-rustfsadmin}"
export BUCKET="${BUCKET:-lake}"
UC_PORT="${UC_PORT:-8080}"
export UC_URL="http://127.0.0.1:${UC_PORT}/api/2.1/unity-catalog"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$(mktemp -d)"

cp "$ROOT/etc/conf/server.properties" "$WORK/server.properties.orig"
cp "$ROOT/etc/db/h2db.mv.db" "$WORK/h2db.mv.db.orig"
restore() {
  cp "$WORK/server.properties.orig" "$ROOT/etc/conf/server.properties"
  cp "$WORK/h2db.mv.db.orig" "$ROOT/etc/db/h2db.mv.db"
}
stop_uc() {
  [ -n "${UC_PID:-}" ] || return 0
  kill -- "-$UC_PID" 2>/dev/null || true
  for _ in $(seq 1 30); do kill -0 -- "-$UC_PID" 2>/dev/null || return 0; sleep 1; done
  kill -9 -- "-$UC_PID" 2>/dev/null || true
}
trap 'stop_uc; restore; rm -rf "$WORK"' EXIT

cat >>"$ROOT/etc/conf/server.properties" <<EOF
aws.masterRoleArn=arn:aws:iam::000000000000:user/uc
aws.accessKey=${S3_ACCESS_KEY}
aws.secretKey=${S3_SECRET_KEY}
aws.region=us-east-1
aws.endpointUrl=${S3_ENDPOINT}
s3.bucketPath.0=s3://${BUCKET}
s3.region.0=us-east-1
s3.awsRoleArn.0=arn:aws:iam::000000000000:role/uc
s3.accessKey.0=${S3_ACCESS_KEY}
s3.secretKey.0=${S3_SECRET_KEY}
s3.endpointUrl.0=${S3_ENDPOINT}
EOF

# Own process group, so the java child dies with the start script.
setsid bash -c 'cd "$1" && exec bin/start-uc-server -p "$2"' _ "$ROOT" "$UC_PORT" \
  >"$WORK/uc.log" 2>&1 &
UC_PID=$!
for _ in $(seq 1 90); do
  curl -sf "$UC_URL/catalogs" >/dev/null 2>&1 && break
  sleep 2
done

if ! curl -sf "$UC_URL/catalogs" >/dev/null || ! python3 "$HERE/vending_checks.py"; then
  echo "--- UC server log (tail)" >&2
  tail -80 "$WORK/uc.log" >&2
  exit 1
fi
