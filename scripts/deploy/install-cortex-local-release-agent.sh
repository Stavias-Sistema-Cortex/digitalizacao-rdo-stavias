#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

[[ "${EUID:-$(id -u)}" -eq 0 ]] || {
  echo "Run the local release agent installer as root." >&2
  exit 1
}
repo_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
gh_version=2.97.0
gh_archive="gh_${gh_version}_linux_amd64.tar.gz"
gh_sha256=a2c9b8497e1f85b1ad0dfcb78b5a622e098801b8e461e459e88e1ee12f018112
install_root=/usr/local/libexec/cortex
config_root=/etc/cortex
unit_root=/etc/systemd/system

# Close the only rename boundary before validating or later copying the
# privileged installer sources from the immutable release tree.
install -d -o root -g root -m 700 \
  /home/sistema/cortex/releases \
  /srv/cortex/runtime/local-release-agent \
  /srv/cortex/runtime/local-release-agent/home

for source in \
  "$repo_root/scripts/deploy/cortex-local-release-agent.sh" \
  "$repo_root/scripts/deploy/verify-local-release-handoff.sh" \
  "$repo_root/deploy/production/systemd/cortex-local-release-update.service" \
  "$repo_root/deploy/production/systemd/cortex-local-release-update.timer"; do
  [[ -f "$source" && ! -L "$source" ]] || exit 1
done
python3 - "$repo_root" <<'PY'
import os
import pathlib
import stat
import sys

root = pathlib.Path(sys.argv[1])
paths = [
    root,
    root / "scripts",
    root / "scripts/deploy",
    root / "scripts/deploy/cortex-local-release-agent.sh",
    root / "scripts/deploy/verify-local-release-handoff.sh",
    root / "deploy",
    root / "deploy/production",
    root / "deploy/production/systemd",
    root / "deploy/production/systemd/cortex-local-release-update.service",
    root / "deploy/production/systemd/cortex-local-release-update.timer",
]
for path in paths:
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise SystemExit(
            "Installer sources must be root-owned and protected from non-root writes."
        )
    if path.suffix in {".sh", ".service", ".timer"} and not stat.S_ISREG(info.st_mode):
        raise SystemExit(
            "Installer sources must be root-owned and protected from non-root writes."
        )
    if path.suffix not in {".sh", ".service", ".timer"} and not stat.S_ISDIR(info.st_mode):
        raise SystemExit(
            "Installer sources must be root-owned and protected from non-root writes."
        )
PY
[[ -f /home/sistema/.docker/config.json && ! -L /home/sistema/.docker/config.json ]] || {
  echo "The existing private GitHub/GHCR credential is unavailable." >&2
  exit 1
}

install -d -o root -g root -m 700 \
  "$install_root" "$config_root"
temporary="$(mktemp -d /tmp/cortex-gh-install.XXXXXX)"
cleanup() { rm -rf -- "$temporary"; }
trap cleanup EXIT
curl --disable --fail --silent --show-error --location \
  --noproxy '*' --proxy '' --connect-timeout 10 --max-time 180 \
  "https://github.com/cli/cli/releases/download/v${gh_version}/${gh_archive}" \
  --output "$temporary/$gh_archive"
[[ "$(sha256sum "$temporary/$gh_archive" | awk '{print $1}')" == "$gh_sha256" ]] || {
  echo "The pinned GitHub CLI archive checksum does not match." >&2
  exit 1
}
tar -xzf "$temporary/$gh_archive" -C "$temporary"
install -o root -g root -m 700 "$temporary/gh_${gh_version}_linux_amd64/bin/gh" "$install_root/gh"
install -o root -g root -m 700 "$repo_root/scripts/deploy/cortex-local-release-agent.sh" "$install_root/cortex-local-release-agent.sh"
install -o root -g root -m 700 "$repo_root/scripts/deploy/verify-local-release-handoff.sh" "$install_root/verify-local-release-handoff.sh"
install -o root -g root -m 600 "$repo_root/deploy/production/systemd/cortex-local-release-update.service" "$unit_root/cortex-local-release-update.service"
install -o root -g root -m 600 "$repo_root/deploy/production/systemd/cortex-local-release-update.timer" "$unit_root/cortex-local-release-update.timer"

cat > "$config_root/local-release-agent.env" <<'EOF'
CORTEX_GITHUB_REPOSITORY=Stavias-Sistema-Cortex/digitalizacao-rdo-stavias
CORTEX_GITHUB_WORKFLOW=production.yml
CORTEX_GITHUB_BRANCH=develop
CORTEX_DOCKER_CONFIG_FILE=/home/sistema/.docker/config.json
CORTEX_RUNTIME_ENV_FILE=/srv/cortex/runtime/production.env
CORTEX_RELEASES_ROOT=/home/sistema/cortex/releases
CORTEX_AGENT_STATE_DIR=/srv/cortex/runtime/local-release-agent
CORTEX_AGENT_LOCK_FILE=/srv/cortex/runtime/local-release-agent/agent.lock
EOF
chmod 600 "$config_root/local-release-agent.env"
chown root:root "$config_root/local-release-agent.env"

systemctl daemon-reload
systemctl enable --now cortex-local-release-update.timer
systemctl start cortex-local-release-update.service
echo "Automatic signed local release updates installed and started."
