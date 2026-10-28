#!/bin/bash

# Settings
CMD_ALIGN="./build/ddm4006_ctrl_trig_align"
DEVICES=("/dev/ttyGBL0" "/dev/ttyGBL1" "/dev/ttyGBL2")
REF_FILE="./data/ref_wxa.csv"  # trigger pulse (same for all devices)
SEARCH_DIR="cl"

# Check arguments
if [[ -z "$1" ]]; then
  echo "Usage: $0 <group_number (0-9)>"
  echo "  group: 0: cl0-9, 1: cl10-19, ..., 9: cl90-99"
  exit 1
fi

GROUP=$1

if [[ ! "$GROUP" =~ ^[0-9]$ ]]; then
  echo "Error: group_number must be 0-9"
  exit 1
fi

# Check that every device exists
for dev in "${DEVICES[@]}"; do
  if [[ ! -e "$dev" ]]; then
    echo "Error: Device $dev not found"
    exit 1
  fi
done

# Trajectory index range
START=$((GROUP * 10))
END=$((START + 9))

echo "Processing cl${START} to cl${END} on all 3 devices in parallel"
echo "========================================"

# Replay the group on one device
run_device() {
  local DEV=$1
  local DEV_NAME=$(basename "$DEV")

  for ((i = START; i <= END; i++)); do
    TARGET_FILE=$(ls ${SEARCH_DIR}/cl${i}_*.csv 2>/dev/null | head -n 1)

    if [[ -n "$TARGET_FILE" ]]; then
      echo "[$DEV_NAME] Processing No.$i: $TARGET_FILE"
      # each run holds until the phone app is ready for the next trigger
      $CMD_ALIGN "$DEV" "$REF_FILE" "$TARGET_FILE"
    else
      echo "[$DEV_NAME] Warning: File matching cl${i}_*.csv not found"
    fi
  done

  echo "[$DEV_NAME] Finished all files"
}

# Run the 3 devices in parallel
for dev in "${DEVICES[@]}"; do
  run_device "$dev" &
done

# Wait for all of them
wait

echo "========================================"
echo "All devices finished. Sending stop command..."

run_device_stop() {
  echo $1 $REF_FILE
  $CMD_ALIGN "$1" "$REF_FILE" "$TARGET_FILE"
}

# Send the stop file to every device (twice)
TARGET_FILE="./data/stopr.csv"
for dev in "${DEVICES[@]}"; do
  run_device_stop $dev &
done
wait

for dev in "${DEVICES[@]}"; do
  run_device_stop $dev &
done
wait


echo "Done."
