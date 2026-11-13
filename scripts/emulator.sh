#!/usr/bin/env bash
# Launch the template's foldable emulator, headless, inside a hard memory/CPU cap.
# Usage: scripts/emulator.sh [--window]   (AVD from $STAMP_AVD, default stamp_fold)
set -euo pipefail
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
export ANDROID_SDK_HOME="${ANDROID_SDK_HOME:-$HOME/.config/.android}"
AVD="${STAMP_AVD:-stamp_fold}"

if [ ! -d "$ANDROID_AVD_HOME/$AVD.avd" ]; then
  echo "emulator.sh: no AVD named $AVD. Create it with: scripts/create-avd.sh" >&2
  exit 1
fi

# Room to start in. The emulator's own cap below is the hard limit; on a small box that cap is
# lower than this floor, on a big one higher, and builds run in their own capped slice.
MIN_FREE_MB=7000
avail=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
if (( avail < MIN_FREE_MB )); then
  echo "emulator.sh: only ${avail} MB available (< ${MIN_FREE_MB} MB). Not starting; close something first." >&2
  exit 1
fi
if pgrep -f "qemu-system-x86_64 -avd ${AVD}( |$)" >/dev/null; then
  echo "emulator.sh: AVD ${AVD} is already running." >&2
  exit 1
fi

# Headless by default so no window lands on a workspace someone is using.
extra=(-no-window)
gpu="swiftshader_indirect"
if [[ "${1:-}" == "--window" ]]; then
  extra=()
  gpu="host"
  shift
fi
gpu="${FOLDABLE_EMU_GPU:-$gpu}"

# Guest RAM is 1.5 GB. Software GPU on the 2076x2152 inner display needs 4.3 GB at boot and
# keeps climbing over a long session of folds, screenshots and installs. The cap is a share of
# this machine's RAM, at most 8 GB, so on a 16 GB laptop it can never take half the box.
# Swap is forbidden, so the guest dies before the desktop does; restart it between long
# test rounds to hand the grown memory back.
total=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
share=$(( total * 40 / 100 ))
mem="${FOLDABLE_EMU_MEM:-$(( share < 8000 ? share : 8000 ))}"
exec systemd-run --user --scope -q \
  -p "MemoryMax=${mem}M" -p MemorySwapMax=0 -p CPUQuota=600% \
  "$SDK/emulator/emulator" -avd "$AVD" -memory 1536 -no-audio -no-boot-anim -no-snapshot \
    -gpu "$gpu" "${extra[@]}" "$@"
