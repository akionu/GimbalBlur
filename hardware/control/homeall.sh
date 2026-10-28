#!/bin/bash

# Settings
CMD="./build/home"
DEVICES=("/dev/ttyGBL0" "/dev/ttyGBL1" "/dev/ttyGBL2")

# Check that every device exists
for dev in "${DEVICES[@]}"; do
  if [[ ! -e "$dev" ]]; then
    echo "Error: Device $dev not found"
    exit 1
  fi
done

echo "========================================"
echo "Homing all 3 devices..."
echo "========================================"

# Home all 3 devices in parallel
for dev in "${DEVICES[@]}"; do
  echo "Homing $(basename "$dev")..."
  $CMD "$dev" &
done
wait

echo "Homing complete."
echo "Done."
