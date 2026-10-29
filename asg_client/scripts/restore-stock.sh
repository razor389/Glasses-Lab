#!/usr/bin/env bash
# Reversible app switching; see docs/development-on-stock-firmware.md.
# Supply --serial USB_SERIAL. Install defaults to a timed trial and stock restore.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${PYTHON:-python3}" "$script_dir/dev_device.py" restore "$@"
