#!/usr/bin/env bash
# Explicit opt-in installation, called by setup.sh --systemd --install-node.
set -euo pipefail
[[ $(uname -s) == Linux && $EUID == 0 ]] || { echo "Requires Linux and root" >&2; exit 1; }
for tool in curl sha256sum tar xz; do command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }; done
case $(uname -m) in x86_64) arch=x64 ;; aarch64|arm64) arch=arm64 ;; *) echo "Unsupported CPU" >&2; exit 1 ;; esac
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
curl --fail --silent --show-error --proto '=https' --tlsv1.2 \
  https://nodejs.org/dist/latest-v24.x/SHASUMS256.txt -o "$work/SHASUMS256.txt"
archive=$(awk -v arch="$arch" '$2 ~ ("^node-v24\\.[0-9]+\\.[0-9]+-linux-" arch "\\.tar\\.xz$") {print $2}' "$work/SHASUMS256.txt")
[[ $archive =~ ^node-v24\.[0-9]+\.[0-9]+-linux-(x64|arm64)\.tar\.xz$ ]] || { echo "Invalid Node release manifest" >&2; exit 1; }
version=${archive#node-}; version=${version%-linux-*}
curl --fail --silent --show-error --proto '=https' --tlsv1.2 \
  "https://nodejs.org/dist/$version/$archive" -o "$work/$archive"
(cd "$work"; grep "  $archive\$" SHASUMS256.txt | sha256sum --check --strict -)
tar -xJf "$work/$archive" -C "$work" --no-same-owner
install -d -m 0755 /opt/openintel-node
if [[ ! -d /opt/openintel-node/"$version" ]]; then
  mv "$work/${archive%.tar.xz}" "/opt/openintel-node/$version"
  chown -R root:root "/opt/openintel-node/$version"
fi
ln -sfn "/opt/openintel-node/$version" /opt/openintel-node/current
printf 'Installed verified Node %s in /opt/openintel-node/current\n' "$version"
