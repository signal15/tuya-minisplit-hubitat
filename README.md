# Pioneer Mini-Split Local Control for Hubitat

Native local control of Pioneer WYT (Diamante) mini-split heat pumps via Hubitat using the Tuya protocol. **No bridge or external services required.**

## Features

- Direct communication from Hubitat to your mini-split over LAN
- Full thermostat control: power, mode, temperature, fan speed
- Auto-reconnect on connection loss
- Supports Tuya protocol versions 3.1, 3.3, and 3.4

## Supported Devices

### Confirmed Working
- **Pioneer WYT (Diamante)** series mini-splits with TST-DIAWIFITPD WiFi module

### May Also Work (Same Tuya Protocol)
These brands use similar Tuya-based WiFi modules and may work with this driver (DPID mappings might vary):

- **Cooper & Hunter** - Sophia, MIA, Olivia, Dakota, Hyper Heat series (with WiFi adapter)
- **Confortotal** - CICON series
- **Royal Sovereign** - RSAI series
- **Kaisai** - Pro Heat+ series
- **Rotenso** - Roni series
- **Tesla Smart** - TAF/AUX series
- **Be Cool** - BC series

If your mini-split uses the **Tuya Smart** or **Smart Life** app and connects via WiFi (not IR blaster), it likely uses this protocol.

### NOT Supported
- **Pioneer WYS** models (use Midea protocol, not Tuya)
- IR blaster-controlled units
- Cloud-only devices without local API

### Technical Details
- Uses Tuya Tywe1s (ESP8266) chip
- Protocol: Tuya 3.3/3.4 over TCP port 6668
- Requires 2.4 GHz WiFi (5 GHz not supported)

## Installation

### Option 1: Hubitat Package Manager (HPM) - Recommended

1. Open **Apps** > **Hubitat Package Manager**
2. Select **Install** > **Search by Keywords**
3. Search for "Pioneer Mini-Split"
4. Click **Install**

Or install from URL:
1. Open **Apps** > **Hubitat Package Manager**
2. Select **Install** > **From a URL**
3. Enter: `https://raw.githubusercontent.com/signal15/tuya-minisplit-hubitat/main/hubitat/packageManifest.json`

### Option 2: Manual Installation

1. In Hubitat, go to **Drivers Code** > **New Driver**
2. Click **Import**
3. Enter: `https://raw.githubusercontent.com/signal15/tuya-minisplit-hubitat/main/hubitat/pioneer-minisplit-local.groovy`
4. Click **Import** then **Save**

## Device Setup

### Prerequisites

You need three pieces of information from your mini-split:
- **Device IP** - The local IP address of your mini-split
- **Device ID** - The Tuya device identifier
- **Local Key** - A 16-character encryption key

### Finding Your Device ID and Local Key

1. **Create a Tuya IoT Account** (free):
   - Go to [iot.tuya.com](https://iot.tuya.com) and create an account
   - Create a Cloud Project (select your region, e.g., "US West")
   - Choose "Smart Home" industry, "Smart Home PaaS" development method

2. **Link Your App Account**:
   - In your Tuya IoT project, go to **Devices** > **Link Tuya App Account**
   - Open the **Tuya Smart** app (not Pioneer Airlink) on your phone
   - Scan the QR code to link your account
   - Your device should appear in the device list

3. **Get the Local Key**:
   - In the Tuya IoT console, go to **Cloud** > **API Explorer**
   - Select **Device Management** > **Get Device Information**
   - Enter your Device ID and execute
   - Copy the `local_key` from the response

4. **Find the Device IP**:
   - Check your router's DHCP client list, or
   - Use a network scanner to find devices on port 6668

### Creating the Hubitat Device

1. Go to **Devices** > **Add Device** > **Virtual**
2. Enter a name (e.g., "Living Room Mini-Split")
3. Select **Type**: "Pioneer Mini-Split Local"
4. Click **Save Device**

### Configure Device Settings

In the device preferences:

| Setting | Value |
|---------|-------|
| Device IP | Your mini-split's IP address (e.g., `192.168.1.100`) |
| Device ID | The Tuya device ID (20 characters) |
| Local Key | The 16-character encryption key |
| Protocol Version | Usually `3.3` (try `3.4` if 3.3 doesn't work) |
| Poll Interval | `60` seconds recommended |
| Auto Reconnect | Enabled |

Click **Save Preferences**.

### Testing

1. Click **Refresh** to connect and get current status
2. Try **On** and **Off** commands
3. Check the logs if commands don't work

## Important Notes

### Single Connection Limit

Tuya devices only allow **one local connection at a time**. If you have the Tuya Smart app open on your phone, Hubitat won't be able to connect. Close the app completely before testing.

### Local Key Special Characters

If your local key contains special characters (backticks, brackets, etc.), enter them exactly as shown. The driver handles HTML entity decoding automatically.

### DHCP Reservation

Set a DHCP reservation for your mini-split to prevent the IP from changing.

## Available Commands

| Command | Description |
|---------|-------------|
| `on` / `off` | Power control |
| `setThermostatMode(mode)` | Set mode: `cool`, `heat`, `auto`, `dry`, `fan_only`, `off` |
| `setCoolingSetpoint(temp)` | Set cooling temperature (61-86°F) |
| `setHeatingSetpoint(temp)` | Set heating temperature (61-86°F) |
| `setThermostatFanMode(mode)` | Set fan: `auto`, `low`, `medium`, `high` |
| `refresh` | Poll current status |
| `debugKey` | Log key info for troubleshooting |

## DPID Reference

| DP | Function | Type | Values |
|----|----------|------|--------|
| 1 | Power | Boolean | on/off |
| 2 | Target Temp | Integer | °F × 10 (e.g., 720 = 72°F) |
| 3 | Current Temp | Integer | Celsius (read-only) |
| 4 | Mode | Enum | cold, hot, wet, wind, auto |
| 5 | Fan | Enum | auto, quiet, low, medium-low, medium, medium-high, high, strong |
| 18 | Humidity | Integer | Percent (read-only) |
| 131 | Filter Dirty | Boolean | (read-only) |

## Troubleshooting

### "No response after 5 retries"
- Close the Tuya Smart app on your phone
- Verify the device IP is correct
- Check that port 6668 is accessible

### "BadPaddingException" / Decryption errors
- The local key is incorrect or entered wrong
- Click **debugKey** to verify the key length is exactly 16
- Re-enter the key carefully, especially if it contains special characters

### Device not responding
- Verify the device is on and connected to WiFi
- Check the protocol version (try 3.3 or 3.4)
- Set a DHCP reservation to prevent IP changes

### Temperature displays wrong
- Target temperature is Fahrenheit (converted automatically)
- Current temperature is read from the device in Celsius and converted

## License

Apache 2.0 License - see LICENSE file

## Credits

- [ivarho's Tuya Generic Device](https://github.com/ivarho/hubiern) - Base Tuya protocol implementation
- [TinyTuya](https://github.com/jasonacox/tinytuya) - Protocol reference
- [tuya-local](https://github.com/make-all/tuya-local) - DPID mappings for Pioneer WYT
