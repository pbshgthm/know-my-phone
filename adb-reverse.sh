#!/usr/bin/env bash
# ADB reverse port forwarding for backend (tcp:8765).
# Usage: ./adb-reverse.sh start | stop

PORT=8765

# Get all connected devices (excludes List header and offline/unauthorized)
device_list() {
  adb devices | grep -v "List" | grep "device$" | awk '{print $1}'
}

case "${1:-}" in
  start)
    devices="$(device_list)"
    if [ -z "$devices" ]; then
      echo "No devices connected"
      exit 1
    fi
    echo "Enabling for:"
    echo "$devices"
    echo ""
    while IFS= read -r dev; do
      [[ -z "$dev" ]] && continue
      adb -s "$dev" reverse --remove "tcp:${PORT}" 2>/dev/null
      if adb -s "$dev" reverse "tcp:${PORT}" "tcp:${PORT}" 2>/dev/null; then
        echo "✓ $dev -> tcp:${PORT}"
      else
        echo "✗ $dev - Failed"
      fi
    done <<< "$devices"
    ;;
  stop)
    devices="$(device_list)"
    if [ -z "$devices" ]; then
      echo "No devices connected"
      exit 1
    fi
    echo "Disabling for:"
    echo "$devices"
    echo ""
    while IFS= read -r dev; do
      [[ -z "$dev" ]] && continue
      if adb -s "$dev" reverse --remove "tcp:${PORT}" 2>/dev/null; then
        echo "✓ Cleared: $dev"
      else
        echo "✗ $dev - Failed"
      fi
    done <<< "$devices"
    ;;
  *)
    echo "Usage: $0 start | stop"
    echo ""
    echo "  start  - Forward device tcp:${PORT} to host tcp:${PORT} (for backend)"
    echo "  stop   - Remove the reverse forwarding"
    echo ""
    echo "With a device/emulator connected, use 'start' so the app can reach the backend at ws://localhost:${PORT}."
    exit 1
    ;;
esac
