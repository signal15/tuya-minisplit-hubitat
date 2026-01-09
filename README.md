# Tuya Mini-Split Hubitat Integration

Local control of Pioneer WYT (Diamante) mini-split heat pumps via Hubitat, using the Tuya protocol.

## Overview

This project provides:
- **LAN Discovery**: Find your Pioneer WYT device on the network
- **DPID Test Harness**: Interactive tool to verify device controls
- **FastAPI Bridge**: Local HTTP service wrapping TinyTuya for device control
- **Hubitat Driver**: Thermostat driver that talks to the bridge

```
Pioneer WYT <--Tuya TCP--> FastAPI Bridge <--HTTP--> Hubitat Driver
```

## Supported Devices

- Pioneer WYT (Diamante) series mini-splits with TST-DIAWIFITPD WiFi module
- Uses Tuya Tywe1s (ESP8266) chip
- Protocol: Tuya 3.3/3.4 over TCP/6668

**Note**: This does NOT support WYS models (which use Midea protocol).

## Quick Start

### 1. Clone and Setup

```bash
git clone https://github.com/signal15/tuya-minisplit-hubitat.git
cd tuya-minisplit-hubitat
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
```

### 2. Discover Your Device

```bash
python discovery/discover.py
```

This will scan your network for Tuya devices and display their Device ID, IP, and protocol version.

### 3. Get Your Local Key (One-Time Setup)

You need a `localKey` to communicate with the device. This requires a free Tuya IoT developer account:

1. Go to [iot.tuya.com](https://iot.tuya.com) and create an account
2. Create a new Cloud Project:
   - Select your region (US West for North America)
   - Choose "Smart Home" industry
   - Development method: "Smart Home PaaS"
3. Go to "Devices" > "Link Tuya App Account"
4. Link your Pioneer Airlink (or Smart Life) app account using the QR code
5. Find your device in the device list and note the Device ID
6. Use TinyTuya wizard to retrieve the localKey:
   ```bash
   python -m tinytuya wizard
   ```
7. Update your `.env` file with the Device ID, Local Key, and IP

### 4. Test Device Control

```bash
python discovery/dp_probe.py
```

This interactive tool lets you test each control (power, mode, temp, fan, swing) and verify the device responds.

### 5. Start the Bridge Service

```bash
python service/app.py
```

The bridge will run on port 8000 (configurable in `.env`).

### 6. Install Hubitat Driver

1. In Hubitat, go to **Drivers Code** > **New Driver**
2. Paste the contents of `hubitat/pioneer-minisplit-driver.groovy`
3. Click **Save**
4. Go to **Devices** > **Add Device** > **Virtual**
5. Select "Pioneer Mini-Split" as the driver
6. Configure the bridge URL and token in device preferences
7. Click **Save Preferences**

## DPID Reference

| DP | Function | Type | Values |
|----|----------|------|--------|
| 1 | Power | Boolean | on/off |
| 2 | Target Temp | Integer | F x 10 (e.g., 720 = 72F) |
| 3 | Current Temp | Integer | Celsius (read-only) |
| 4 | Mode | Enum | cold, hot, wet, wind, auto |
| 5 | Fan | Enum | auto, quiet, low, medium-low, medium, medium-high, high, strong |
| 18 | Humidity | Integer | Percent (read-only) |
| 113 | Vert Swing | String | off, full, upper, lower |
| 114 | Horiz Swing | String | off, full, left, center, right |
| 123 | Display/Beep | Hex | Bitfield (see docs) |
| 131 | Filter Dirty | Boolean | (read-only) |

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Service health check |
| GET | `/status` | Current device state |
| POST | `/command` | Send command to device |
| POST | `/discover` | Run network discovery |

### Example Commands

```bash
# Get status
curl http://localhost:8000/status -H "Authorization: Bearer YOUR_TOKEN"

# Turn on
curl -X POST http://localhost:8000/command \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"command": "power", "value": true}'

# Set temperature to 72F
curl -X POST http://localhost:8000/command \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"command": "target_temp", "value": 72}'

# Set mode to cool
curl -X POST http://localhost:8000/command \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"command": "mode", "value": "cold"}'
```

## Hubitat Package Manager (HPM)

For easier updates, you can install via HPM:

1. Install HPM if you haven't: [HPM Installation](https://hubitatpackagemanager.hubitatcommunity.com/installing.html)
2. Go to **Apps** > **Hubitat Package Manager** > **Install** > **From a URL**
3. Enter: `https://raw.githubusercontent.com/signal15/tuya-minisplit-hubitat/main/hubitat/packageManifest.json`

## Troubleshooting

### Device not found during discovery
- Ensure the device is on the same network/VLAN
- Check that UDP ports 6666/6667 aren't blocked
- Try the nmap fallback: `nmap -p 6668 --open 10.129.1.0/24`

### Commands not working
- Verify the localKey is correct (re-run `tinytuya wizard`)
- Check the protocol version matches (3.3 or 3.4)
- Ensure the device IP hasn't changed (set a DHCP reservation)

### Temperature displays wrong
- Target temp is sent as F x 10 (72F = 720)
- Current temp is returned in Celsius (convert in display)

## License

MIT License - see LICENSE file

## Credits

- [TinyTuya](https://github.com/jasonacox/tinytuya) - Python library for local Tuya device control
- [tuya-local](https://github.com/make-all/tuya-local) - Home Assistant integration (DPID reference)
- [LocalTuya](https://github.com/rospogrigio/localtuya) - Alternative HA integration
