#!/usr/bin/env python3
"""
Pioneer WYT Mini-Split DPID Probe / Test Harness

Interactive tool to test device controls and verify DPID mappings.
Sends commands and prompts for user confirmation of physical response.
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path

try:
    import tinytuya
    from dotenv import load_dotenv
    import yaml
except ImportError as e:
    print(f"Error: Missing dependency - {e}")
    print("Run: pip install tinytuya python-dotenv pyyaml")
    sys.exit(1)


# Load environment
load_dotenv()

# DPID definitions for Pioneer WYT
DPIDS = {
    'power': {'dp': 1, 'type': 'bool'},
    'target_temp': {'dp': 2, 'type': 'int', 'scale': 10},
    'current_temp': {'dp': 3, 'type': 'int', 'readonly': True},
    'mode': {'dp': 4, 'type': 'enum', 'values': ['cold', 'hot', 'wet', 'wind', 'auto']},
    'fan': {'dp': 5, 'type': 'enum', 'values': ['auto', 'quiet', 'low', 'medium-low', 'medium', 'medium-high', 'high', 'strong']},
    'humidity': {'dp': 18, 'type': 'int', 'readonly': True},
    'fault_code': {'dp': 20, 'type': 'int', 'readonly': True},
    'sleep_mode': {'dp': 105, 'type': 'enum', 'values': ['off', 'standard', 'elderly', 'child']},
    'vert_swing': {'dp': 113, 'type': 'enum', 'values': ['off', 'full', 'upper', 'lower']},
    'horiz_swing': {'dp': 114, 'type': 'enum', 'values': ['off', 'full', 'left', 'center', 'right']},
    'eco_mode': {'dp': 119, 'type': 'string'},
    'display_beep': {'dp': 123, 'type': 'hex'},
    'vert_position': {'dp': 126, 'type': 'int', 'min': 1, 'max': 5},
    'horiz_position': {'dp': 127, 'type': 'int', 'min': 1, 'max': 5},
    'filter_dirty': {'dp': 131, 'type': 'bool', 'readonly': True},
    'swing_action': {'dp': 133, 'type': 'int', 'min': 0, 'max': 3},
    'stats': {'dp': 134, 'type': 'json', 'readonly': True},
}


class DeviceProbe:
    """Interactive device probe for testing DPID mappings."""

    def __init__(self, device_id: str, local_key: str, ip: str, version: str = '3.3'):
        self.device = tinytuya.Device(device_id, ip, local_key)
        self.device.set_version(float(version))
        self.results = {}

    def get_status(self) -> dict:
        """Get current device status."""
        try:
            status = self.device.status()
            if status and 'dps' in status:
                return status['dps']
            return {}
        except Exception as e:
            print(f"Error getting status: {e}")
            return {}

    def set_value(self, dp: int, value) -> bool:
        """Set a DP value on the device."""
        try:
            result = self.device.set_value(dp, value)
            return result is not None
        except Exception as e:
            print(f"Error setting DP {dp}: {e}")
            return False

    def test_power(self) -> bool:
        """Test power on/off."""
        print("\n" + "=" * 50)
        print("TESTING: Power On/Off (DP 1)")
        print("=" * 50)

        # Get current state
        status = self.get_status()
        current = status.get('1', None)
        print(f"Current power state: {current}")

        # Toggle power
        new_state = not current if current is not None else True
        print(f"Setting power to: {new_state}")

        if self.set_value(1, new_state):
            time.sleep(2)
            response = input("Did the unit turn ON/OFF? (y/n): ").lower().strip()
            return response == 'y'
        return False

    def test_mode(self) -> dict:
        """Test operating modes."""
        print("\n" + "=" * 50)
        print("TESTING: Operating Mode (DP 4)")
        print("=" * 50)

        modes = ['cold', 'hot', 'auto', 'wind', 'wet']
        results = {}

        for mode in modes:
            print(f"\nSetting mode to: {mode}")
            if self.set_value(4, mode):
                time.sleep(3)
                response = input(f"Did mode change to {mode}? (y/n/s=skip): ").lower().strip()
                if response == 's':
                    continue
                results[mode] = response == 'y'

        return results

    def test_temperature(self) -> bool:
        """Test temperature setting."""
        print("\n" + "=" * 50)
        print("TESTING: Temperature (DP 2)")
        print("=" * 50)
        print("Note: Temperature is sent as F * 10 (e.g., 72F = 720)")

        # Get current
        status = self.get_status()
        current = status.get('2', None)
        print(f"Current target temp value: {current}")
        if current:
            print(f"That's {current / 10}°F")

        # Set to 74F (740)
        test_temp = 740
        print(f"\nSetting temperature to 74°F (value: {test_temp})")

        if self.set_value(2, test_temp):
            time.sleep(2)
            response = input("Did the display show 74°F? (y/n): ").lower().strip()
            return response == 'y'
        return False

    def test_fan(self) -> dict:
        """Test fan speeds."""
        print("\n" + "=" * 50)
        print("TESTING: Fan Speed (DP 5)")
        print("=" * 50)

        fans = ['auto', 'low', 'medium', 'high']
        results = {}

        for fan in fans:
            print(f"\nSetting fan to: {fan}")
            if self.set_value(5, fan):
                time.sleep(2)
                response = input(f"Did fan change to {fan}? (y/n/s=skip): ").lower().strip()
                if response == 's':
                    continue
                results[fan] = response == 'y'

        return results

    def test_swing(self) -> dict:
        """Test swing/louver positions."""
        print("\n" + "=" * 50)
        print("TESTING: Swing/Louver (DP 113, 114)")
        print("=" * 50)

        results = {}

        # Vertical swing
        print("\nTesting vertical swing (DP 113)...")
        for pos in ['off', 'full']:
            print(f"Setting vertical swing to: {pos}")
            if self.set_value(113, pos):
                time.sleep(2)
                response = input(f"Did vertical louvers {'stop' if pos == 'off' else 'start swinging'}? (y/n/s=skip): ").lower().strip()
                if response != 's':
                    results[f'vert_{pos}'] = response == 'y'

        # Horizontal swing
        print("\nTesting horizontal swing (DP 114)...")
        for pos in ['off', 'full']:
            print(f"Setting horizontal swing to: {pos}")
            if self.set_value(114, pos):
                time.sleep(2)
                response = input(f"Did horizontal louvers {'stop' if pos == 'off' else 'start swinging'}? (y/n/s=skip): ").lower().strip()
                if response != 's':
                    results[f'horiz_{pos}'] = response == 'y'

        return results

    def dump_all_dps(self):
        """Dump all current DP values."""
        print("\n" + "=" * 50)
        print("CURRENT DEVICE STATE (All DPs)")
        print("=" * 50)

        status = self.get_status()
        if not status:
            print("Could not get device status")
            return

        for dp, value in sorted(status.items(), key=lambda x: int(x[0])):
            # Try to find the name
            name = "unknown"
            for n, info in DPIDS.items():
                if str(info['dp']) == str(dp):
                    name = n
                    break
            print(f"  DP {dp:3} ({name:15}): {value}")

    def run_all_tests(self):
        """Run all interactive tests."""
        print("\n" + "#" * 60)
        print("# Pioneer WYT Mini-Split DPID Test Harness")
        print("#" * 60)
        print("\nThis will test each control and ask you to confirm")
        print("whether the physical device responded correctly.")
        print("Press Ctrl+C at any time to abort.\n")

        input("Press Enter to begin testing...")

        # Dump current state first
        self.dump_all_dps()

        results = {
            'power': self.test_power(),
            'temperature': self.test_temperature(),
            'mode': self.test_mode(),
            'fan': self.test_fan(),
            'swing': self.test_swing(),
        }

        # Summary
        print("\n" + "=" * 60)
        print("TEST RESULTS SUMMARY")
        print("=" * 60)
        print(json.dumps(results, indent=2))

        # Save results
        output_path = Path("test_results.json")
        with open(output_path, 'w') as f:
            json.dump(results, f, indent=2)
        print(f"\nResults saved to: {output_path}")

        return results


def main():
    parser = argparse.ArgumentParser(
        description="Interactive DPID probe for Pioneer WYT mini-split"
    )
    parser.add_argument(
        "--device-id",
        default=os.getenv('TUYA_DEVICE_ID'),
        help="Tuya device ID (or set TUYA_DEVICE_ID env var)"
    )
    parser.add_argument(
        "--local-key",
        default=os.getenv('TUYA_LOCAL_KEY'),
        help="Tuya local key (or set TUYA_LOCAL_KEY env var)"
    )
    parser.add_argument(
        "--ip",
        default=os.getenv('TUYA_DEVICE_IP'),
        help="Device IP address (or set TUYA_DEVICE_IP env var)"
    )
    parser.add_argument(
        "--version",
        default=os.getenv('TUYA_PROTOCOL_VERSION', '3.3'),
        help="Tuya protocol version (default: 3.3)"
    )
    parser.add_argument(
        "--status-only",
        action="store_true",
        help="Only dump current status, don't run tests"
    )
    parser.add_argument(
        "--set",
        nargs=2,
        metavar=('DP', 'VALUE'),
        help="Set a specific DP value (e.g., --set 1 true)"
    )

    args = parser.parse_args()

    # Validate required args
    if not all([args.device_id, args.local_key, args.ip]):
        print("Error: Missing required parameters.")
        print("Either provide --device-id, --local-key, and --ip")
        print("Or set environment variables: TUYA_DEVICE_ID, TUYA_LOCAL_KEY, TUYA_DEVICE_IP")
        print("\nTip: Copy .env.example to .env and fill in your values")
        sys.exit(1)

    probe = DeviceProbe(args.device_id, args.local_key, args.ip, args.version)

    if args.status_only:
        probe.dump_all_dps()
    elif args.set:
        dp, value = args.set
        # Parse value
        if value.lower() == 'true':
            value = True
        elif value.lower() == 'false':
            value = False
        elif value.isdigit():
            value = int(value)

        print(f"Setting DP {dp} to {value}...")
        if probe.set_value(int(dp), value):
            print("Command sent successfully")
            time.sleep(1)
            probe.dump_all_dps()
        else:
            print("Command failed")
    else:
        try:
            probe.run_all_tests()
        except KeyboardInterrupt:
            print("\n\nTest aborted by user")
            probe.dump_all_dps()


if __name__ == "__main__":
    main()
