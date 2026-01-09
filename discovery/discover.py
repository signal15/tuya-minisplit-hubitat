#!/usr/bin/env python3
"""
Pioneer WYT Mini-Split Device Discovery

Scans the local network for Tuya devices using TinyTuya.
Outputs device ID, IP, protocol version, and product key.
"""

import argparse
import json
import sys
from pathlib import Path

try:
    import tinytuya
except ImportError:
    print("Error: tinytuya not installed. Run: pip install tinytuya")
    sys.exit(1)


def discover_devices(retries: int = 5) -> list:
    """
    Scan for Tuya devices on the local network.

    Args:
        retries: Number of scan iterations

    Returns:
        List of discovered device dictionaries
    """
    print(f"Scanning for Tuya devices (retries: {retries})...")
    print("Listening on UDP ports 6666 and 6667...\n")

    devices = tinytuya.deviceScan(verbose=True, maxretry=retries, poll=False, forcescan=False)

    if not devices:
        return []

    # Convert from dict keyed by IP to list of devices
    result = []
    for ip, device_data in devices.items():
        device = {
            'ip': ip,
            'id': device_data.get('gwId', device_data.get('id')),
            'version': device_data.get('version', '3.3'),
            'productKey': device_data.get('productKey', ''),
            'name': device_data.get('name', ''),
        }
        result.append(device)

    return result


def format_device(device: dict) -> str:
    """Format a device dict for display."""
    lines = [
        f"  Device ID:  {device.get('id', 'Unknown')}",
        f"  IP Address: {device.get('ip', 'Unknown')}",
        f"  Version:    {device.get('version', 'Unknown')}",
        f"  Product ID: {device.get('productKey', 'Unknown')}",
    ]
    if device.get('name'):
        lines.insert(0, f"  Name:       {device['name']}")
    return "\n".join(lines)


def save_device_config(device: dict, output_path: Path):
    """Save device configuration to a JSON file."""
    config = {
        "deviceId": device.get('id'),
        "ip": device.get('ip'),
        "version": device.get('version', '3.3'),
        "productKey": device.get('productKey'),
        "localKey": "YOUR_LOCAL_KEY_HERE"
    }

    with open(output_path, 'w') as f:
        json.dump(config, f, indent=2)

    print(f"\nDevice config saved to: {output_path}")
    print("Update the 'localKey' field after running: python -m tinytuya wizard")


def main():
    parser = argparse.ArgumentParser(
        description="Discover Pioneer WYT (Tuya) devices on the local network"
    )
    parser.add_argument(
        "-r", "--retries",
        type=int,
        default=5,
        help="Number of scan iterations (default: 5)"
    )
    parser.add_argument(
        "-o", "--output",
        type=Path,
        help="Save first device config to JSON file"
    )
    parser.add_argument(
        "-j", "--json",
        action="store_true",
        help="Output raw JSON"
    )

    args = parser.parse_args()

    devices = discover_devices(retries=args.retries)

    if not devices:
        print("No Tuya devices found on the network.")
        print("\nTroubleshooting:")
        print("  - Ensure your device is powered on and connected to WiFi")
        print("  - Check that you're on the same network/VLAN as the device")
        print("  - UDP ports 6666/6667 must not be blocked")
        print("  - Try increasing retries: python discover.py -r 10")
        print("\nFallback: scan for TCP port 6668:")
        print("  nmap -p 6668 --open 10.129.1.0/24")
        sys.exit(1)

    if args.json:
        print(json.dumps(devices, indent=2))
    else:
        print(f"Found {len(devices)} device(s):\n")
        for i, device in enumerate(devices, 1):
            print(f"[{i}] {'-' * 40}")
            print(format_device(device))
            print()

    if args.output and devices:
        save_device_config(devices[0], args.output)

    # Print next steps
    if not args.json:
        print("=" * 50)
        print("NEXT STEPS:")
        print("=" * 50)
        print("1. Note the Device ID and IP address above")
        print("2. Get your localKey by running:")
        print("   python -m tinytuya wizard")
        print("3. Update your .env file with:")
        print(f"   TUYA_DEVICE_ID={devices[0].get('id', 'YOUR_DEVICE_ID')}")
        print(f"   TUYA_DEVICE_IP={devices[0].get('ip', 'YOUR_DEVICE_IP')}")
        print(f"   TUYA_PROTOCOL_VERSION={devices[0].get('version', '3.3')}")
        print("   TUYA_LOCAL_KEY=YOUR_LOCAL_KEY")


if __name__ == "__main__":
    main()
