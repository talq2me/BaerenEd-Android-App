#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./logcat_emulator.sh [--tag TAG]

Streams logcat for the currently connected Android emulator device (adb serial like emulator-5554).

By default it filters to only the Baeren apps by PID (com.talq2me.baerened, com.talq2me.baerenlock) to reduce system noise.

Options:
  --tag TAG, -s TAG   Filter logcat to a specific log tag (e.g. "ReactNative", "ActivityManager").
  --no-pid-filter     Disable PID filtering and stream broader logcat (still supports --tag).
  -h, --help           Show this help.
EOF
}

FILTER_TAG=""
NO_PID_FILTER=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      FILTER_TAG="${2:?Missing value for --tag}"
      shift 2
      ;;
    -s)
      FILTER_TAG="${2:?Missing value for -s}"
      shift 2
      ;;
    --no-pid-filter)
      NO_PID_FILTER="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

# Resolve adb location so PATH doesn't have to be set in the current terminal.
find_adb() {
  local adb_path=""

  # 1) Current PATH (fast path).
  adb_path="$(command -v adb 2>/dev/null || true)"
  if [[ -n "${adb_path}" && -x "${adb_path}" ]]; then
    echo "${adb_path}"
    return 0
  fi

  # 2) Windows SDK common locations.
  local candidates=()
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    candidates+=("${ANDROID_HOME}/platform-tools/adb.exe" "${ANDROID_HOME}/platform-tools/adb")
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    candidates+=("${ANDROID_SDK_ROOT}/platform-tools/adb.exe" "${ANDROID_SDK_ROOT}/platform-tools/adb")
  fi
  if [[ -n "${LOCALAPPDATA:-}" ]]; then
    candidates+=("${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe" "${LOCALAPPDATA}/Android/Sdk/platform-tools/adb")
  fi
  if [[ -n "${USERPROFILE:-}" ]]; then
    candidates+=("${USERPROFILE}/AppData/Local/Android/Sdk/platform-tools/adb.exe" "${USERPROFILE}/AppData/Local/Android/Sdk/platform-tools/adb")
  fi

  # 3) Filesystem candidate checks.
  for p in "${candidates[@]}"; do
    if [[ -f "${p}" && -x "${p}" ]]; then
      echo "${p}"
      return 0
    fi
  done

  return 1
}

ADB="$(find_adb || true)"
if [[ -z "${ADB}" ]]; then
  echo "Error: adb not found. Ensure Android SDK platform-tools is installed (adb.exe) and try again." >&2
  exit 127
fi

# Collect connected device serials.
devices_raw="$("${ADB}" devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}')"
if [[ -z "${devices_raw}" ]]; then
  echo "No connected adb devices found. Is the emulator running?" >&2
  exit 2
fi

# Prefer emulator-* devices.
mapfile -t emulators < <(printf '%s\n' "${devices_raw}" | awk '/^emulator-/{print}')
if [[ ${#emulators[@]} -eq 0 ]]; then
  echo "No emulator-* devices found. Connected devices:" >&2
  printf '%s\n' "${devices_raw}" >&2
  exit 3
fi

serial=""
if [[ ${#emulators[@]} -eq 1 ]]; then
  serial="${emulators[0]}"
else
  echo "Multiple emulators detected:"
  for i in "${!emulators[@]}"; do
    # Present as 1-based for humans.
    printf ' [%d] %s\n' "$((i+1))" "${emulators[$i]}"
  done

  if [[ -t 0 ]]; then
    read -r -p "Select emulator number [1]: " choice
    choice="${choice:-1}"
    if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= ${#emulators[@]} )); then
      serial="${emulators[$((choice-1))]}"
    else
      serial="${emulators[0]}"
      echo "Invalid choice; using ${serial}"
    fi
  else
    serial="${emulators[0]}"
    echo "No TTY; using first emulator: ${serial}"
  fi
fi

BAEREN_ED_PKG="com.talq2me.baerened"
BAEREN_LOCK_PKG="com.talq2me.baerenlock"

get_pid_for_package() {
  # Returns a single PID string for a given package, or empty if not found.
  local pkg="$1"
  local out=""

  # Prefer pidof.
  out="$("${ADB}" -s "${serial}" shell "pidof -s ${pkg}" 2>/dev/null | awk 'NR==1{gsub(/\r/,"");print; exit}' || true)"
  if [[ -n "${out}" ]]; then
    echo "${out}"
    return 0
  fi

  # Fallback: parse `ps` output.
  out="$("${ADB}" -s "${serial}" shell "ps -A" 2>/dev/null \
    | awk -v pkg="${pkg}" 'tolower($0) ~ tolower(pkg) {print $2; exit}' \
    | awk 'NR==1{gsub(/\r/,"");print; exit}' || true)"
  echo "${out}"
}

echo "Streaming logcat for ${serial} (Baeren apps only by PID)..."

logcat_cmd=( "${ADB}" -s "${serial}" logcat -v time "*:V" )

if [[ -z "${NO_PID_FILTER}" ]]; then
  pid_ed="$(get_pid_for_package "${BAEREN_ED_PKG}")"
  pid_lock="$(get_pid_for_package "${BAEREN_LOCK_PKG}")"

  pids=()
  [[ -n "${pid_ed}" ]] && pids+=("${pid_ed}")
  [[ -n "${pid_lock}" ]] && pids+=("${pid_lock}")

  if [[ ${#pids[@]} -gt 0 ]]; then
    # `adb logcat --pid` expects comma-separated list.
    pids_csv="$(IFS=','; echo "${pids[*]}")"
    echo "PID filter active: baerened=${pid_ed:-none}, baerenlock=${pid_lock:-none} (csv=${pids_csv})"
    logcat_cmd+=( --pid "${pids_csv}" )
  else
    echo "Warning: could not find PIDs for ${BAEREN_ED_PKG} / ${BAEREN_LOCK_PKG}; falling back to unfiltered logcat"
  fi
fi

if [[ -n "${FILTER_TAG}" ]]; then
  logcat_cmd+=( -s "${FILTER_TAG}" )
fi

exec "${logcat_cmd[@]}"

