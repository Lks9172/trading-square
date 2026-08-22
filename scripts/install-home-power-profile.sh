#!/usr/bin/env bash
set -Eeuo pipefail

[[ "${EUID}" -eq 0 ]] || {
  echo 'install-home-power-profile.sh must run as root' >&2
  exit 1
}

preference_file=/sys/devices/system/cpu/cpufreq/policy0/energy_performance_preference
available_file=/sys/devices/system/cpu/cpufreq/policy0/energy_performance_available_preferences

[[ -w "$preference_file" && -r "$available_file" ]] || {
  echo 'Intel P-state energy preference is unavailable on this host' >&2
  exit 1
}
grep -qw balance_power "$available_file" || {
  echo 'balance_power is not supported by this host' >&2
  exit 1
}

cat >/etc/systemd/system/macrosquare-home-power-profile.service <<'UNIT'
[Unit]
Description=MacroSquare home-server balanced power profile
After=multi-user.target

[Service]
Type=oneshot
ExecStart=/bin/sh -ec 'for file in /sys/devices/system/cpu/cpufreq/policy*/energy_performance_preference; do printf balance_power > "$file"; done'
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now macrosquare-home-power-profile.service

for file in /sys/devices/system/cpu/cpufreq/policy*/energy_performance_preference; do
  [[ "$(cat "$file")" == balance_power ]]
done

echo 'home power profile: balance_power (persistent)'
