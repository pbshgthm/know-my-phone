#!/bin/bash

PORT=8765

echo ""
echo "ADB Reverse Port Manager (port $PORT)"
echo "====================================="
echo ""

# Get all devices
devices=$(adb devices | grep -v "List" | grep "device$" | awk '{print $1}')

if [ -z "$devices" ]; then
    echo "❌ No devices connected"
    exit 1
fi

# Main menu
echo "1) Start forwarding"
echo "2) Stop all forwarding"
echo ""
read -p "Choose [1/2]: " action

if [ "$action" = "2" ]; then
    echo ""
    echo "Stopping all port forwarding..."
    for device in $devices; do
        adb -s "$device" reverse --remove-all 2>/dev/null
        echo "✓ Cleared: $device"
    done
    echo ""
    echo "Done!"
    exit 0
fi

if [ "$action" != "1" ]; then
    echo "Invalid choice"
    exit 1
fi

# Show devices menu
echo ""
echo "Select device:"
echo ""

device_array=()
i=1
while IFS= read -r line; do
    device_array+=("$line")
    # Check if already forwarded
    status=$(adb -s "$line" reverse --list 2>/dev/null | grep "tcp:$PORT")
    if [ -n "$status" ]; then
        echo "  $i) $line [✓ active]"
    else
        echo "  $i) $line"
    fi
    ((i++))
done <<< "$devices"

echo "  a) All devices"
echo ""
read -p "Choose: " selection

# Parse selection
selected=()
if [ "$selection" = "a" ] || [ "$selection" = "A" ]; then
    selected=("${device_array[@]}")
elif [[ "$selection" =~ ^[0-9]+$ ]] && [ "$selection" -ge 1 ] && [ "$selection" -le "${#device_array[@]}" ]; then
    idx=$((selection - 1))
    selected+=("${device_array[$idx]}")
else
    echo "Invalid selection"
    exit 1
fi

# Apply forwarding
echo ""
echo "Setting up forwarding..."
echo ""

for device in "${selected[@]}"; do
    adb -s "$device" reverse --remove tcp:$PORT 2>/dev/null
    result=$(adb -s "$device" reverse tcp:$PORT tcp:$PORT 2>&1)

    if [ $? -eq 0 ]; then
        echo "✓ $device -> tcp:$PORT"
    else
        echo "✗ $device - Failed: $result"
    fi
done

echo ""
echo "Done!"
