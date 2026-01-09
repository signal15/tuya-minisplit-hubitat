#!/usr/bin/env python3
"""
Pioneer WYT Mini-Split FastAPI Bridge

Local HTTP API wrapping TinyTuya for device control.
Provides endpoints for Hubitat driver integration.
"""

import os
import sys
import time
import threading
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Optional

try:
    import tinytuya
    import yaml
    from dotenv import load_dotenv
    from fastapi import FastAPI, HTTPException, Depends, Header
    from fastapi.middleware.cors import CORSMiddleware
    from pydantic import BaseModel
    import uvicorn
except ImportError as e:
    print(f"Error: Missing dependency - {e}")
    print("Run: pip install -r requirements.txt")
    sys.exit(1)

# Load environment
load_dotenv()

# Configuration
DEVICE_ID = os.getenv('TUYA_DEVICE_ID')
LOCAL_KEY = os.getenv('TUYA_LOCAL_KEY')
DEVICE_IP = os.getenv('TUYA_DEVICE_IP')
PROTOCOL_VERSION = os.getenv('TUYA_PROTOCOL_VERSION', '3.3')
BRIDGE_HOST = os.getenv('BRIDGE_HOST', '0.0.0.0')
BRIDGE_PORT = int(os.getenv('BRIDGE_PORT', '8000'))
BRIDGE_TOKEN = os.getenv('BRIDGE_TOKEN', 'changeme')
TEMP_UNIT = os.getenv('TEMP_UNIT', 'F')

# Load DPID configuration
DPIDS_PATH = Path(__file__).parent / 'dpids.yaml'
with open(DPIDS_PATH) as f:
    DPID_CONFIG = yaml.safe_load(f)

DATAPOINTS = DPID_CONFIG.get('datapoints', {})
MODE_MAP = DPID_CONFIG.get('mode_map', {})
FAN_MAP = DPID_CONFIG.get('fan_map', {})


# Device connection (singleton with reconnect)
class DeviceConnection:
    """Thread-safe device connection manager."""

    def __init__(self):
        self.device: Optional[tinytuya.Device] = None
        self.lock = threading.Lock()
        self.last_status: dict = {}
        self.last_status_time: float = 0
        self.status_cache_ttl: float = 2.0  # seconds

    def connect(self) -> bool:
        """Establish connection to device."""
        if not all([DEVICE_ID, LOCAL_KEY, DEVICE_IP]):
            return False

        with self.lock:
            try:
                self.device = tinytuya.Device(DEVICE_ID, DEVICE_IP, LOCAL_KEY)
                self.device.set_version(float(PROTOCOL_VERSION))
                return True
            except Exception as e:
                print(f"Connection error: {e}")
                self.device = None
                return False

    def get_status(self, force_refresh: bool = False) -> dict:
        """Get device status with caching."""
        now = time.time()

        if not force_refresh and (now - self.last_status_time) < self.status_cache_ttl:
            return self.last_status

        with self.lock:
            if not self.device:
                if not self.connect():
                    return {}

            try:
                status = self.device.status()
                if status and 'dps' in status:
                    self.last_status = status['dps']
                    self.last_status_time = now
                    return self.last_status
                return {}
            except Exception as e:
                print(f"Status error: {e}")
                self.device = None
                return {}

    def set_value(self, dp: int, value: Any) -> bool:
        """Set a DP value."""
        with self.lock:
            if not self.device:
                if not self.connect():
                    return False

            try:
                result = self.device.set_value(dp, value)
                # Invalidate cache
                self.last_status_time = 0
                return result is not None
            except Exception as e:
                print(f"Set value error: {e}")
                self.device = None
                return False


# Global device connection
device_conn = DeviceConnection()


# Pydantic models
class CommandRequest(BaseModel):
    command: str
    value: Any


class StatusResponse(BaseModel):
    online: bool
    power: Optional[bool] = None
    mode: Optional[str] = None
    target_temp: Optional[float] = None
    current_temp: Optional[float] = None
    fan: Optional[str] = None
    humidity: Optional[int] = None
    vert_swing: Optional[str] = None
    horiz_swing: Optional[str] = None
    filter_dirty: Optional[bool] = None
    raw_dps: dict = {}


class DiscoverResponse(BaseModel):
    devices: list


# Auth dependency
async def verify_token(authorization: str = Header(None)):
    """Verify bearer token."""
    if not authorization:
        raise HTTPException(status_code=401, detail="Missing authorization header")

    parts = authorization.split()
    if len(parts) != 2 or parts[0].lower() != 'bearer':
        raise HTTPException(status_code=401, detail="Invalid authorization format")

    if parts[1] != BRIDGE_TOKEN:
        raise HTTPException(status_code=403, detail="Invalid token")

    return True


# Lifespan handler
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup and shutdown events."""
    # Startup
    print(f"Connecting to device {DEVICE_ID} at {DEVICE_IP}...")
    if device_conn.connect():
        print("Device connected successfully")
    else:
        print("Warning: Could not connect to device")

    yield

    # Shutdown
    print("Shutting down...")


# FastAPI app
app = FastAPI(
    title="Pioneer WYT Mini-Split Bridge",
    description="Local HTTP API for controlling Pioneer WYT mini-split via Tuya protocol",
    version="1.0.0",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


def celsius_to_fahrenheit(c: float) -> float:
    """Convert Celsius to Fahrenheit."""
    return (c * 9 / 5) + 32


def fahrenheit_to_celsius(f: float) -> float:
    """Convert Fahrenheit to Celsius."""
    return (f - 32) * 5 / 9


def parse_status(raw_dps: dict) -> StatusResponse:
    """Parse raw DPS into structured status."""
    if not raw_dps:
        return StatusResponse(online=False, raw_dps={})

    # Extract values by DP number
    power = raw_dps.get('1')
    target_temp_raw = raw_dps.get('2')
    current_temp_raw = raw_dps.get('3')
    mode = raw_dps.get('4')
    fan = raw_dps.get('5')
    humidity = raw_dps.get('18')
    vert_swing = raw_dps.get('113')
    horiz_swing = raw_dps.get('114')
    filter_dirty = raw_dps.get('131')

    # Convert temperatures
    target_temp = None
    if target_temp_raw is not None:
        # Raw value is F * 10
        target_temp = target_temp_raw / 10
        if TEMP_UNIT == 'C':
            target_temp = fahrenheit_to_celsius(target_temp)

    current_temp = None
    if current_temp_raw is not None:
        # Raw value is Celsius
        current_temp = current_temp_raw
        if TEMP_UNIT == 'F':
            current_temp = celsius_to_fahrenheit(current_temp)

    # Map mode to Hubitat convention
    hubitat_mode = MODE_MAP.get('tuya_to_hubitat', {}).get(mode, mode)

    # Map fan to Hubitat convention
    hubitat_fan = FAN_MAP.get('tuya_to_hubitat', {}).get(fan, fan)

    return StatusResponse(
        online=True,
        power=power,
        mode=hubitat_mode,
        target_temp=round(target_temp, 1) if target_temp else None,
        current_temp=round(current_temp, 1) if current_temp else None,
        fan=hubitat_fan,
        humidity=humidity,
        vert_swing=vert_swing,
        horiz_swing=horiz_swing,
        filter_dirty=filter_dirty,
        raw_dps=raw_dps
    )


@app.get("/health")
async def health_check():
    """Service health check."""
    return {
        "status": "ok",
        "device_id": DEVICE_ID,
        "device_ip": DEVICE_IP,
        "temp_unit": TEMP_UNIT
    }


@app.get("/status", response_model=StatusResponse)
async def get_status(
    refresh: bool = False,
    _: bool = Depends(verify_token)
):
    """Get current device status."""
    raw_dps = device_conn.get_status(force_refresh=refresh)
    return parse_status(raw_dps)


@app.post("/command")
async def send_command(
    request: CommandRequest,
    _: bool = Depends(verify_token)
):
    """Send a command to the device."""
    command = request.command.lower()
    value = request.value

    # Look up the DP for this command
    dp_info = DATAPOINTS.get(command)
    if not dp_info:
        raise HTTPException(status_code=400, detail=f"Unknown command: {command}")

    dp = dp_info['dp']
    dp_type = dp_info.get('type', 'string')

    # Validate and transform value
    if dp_type == 'bool':
        if isinstance(value, str):
            value = value.lower() in ('true', '1', 'on', 'yes')
        else:
            value = bool(value)

    elif dp_type == 'int':
        if command == 'target_temp':
            # Handle temperature - input is in user's preferred unit
            temp_f = float(value)
            if TEMP_UNIT == 'C':
                # Convert C to F for the device
                temp_f = celsius_to_fahrenheit(float(value))
            # Device expects F * 10
            value = int(temp_f * 10)

            # Clamp to valid range
            min_val = dp_info.get('min', 61) * 10
            max_val = dp_info.get('max', 86) * 10
            value = max(min_val, min(max_val, value))
        else:
            value = int(value)

    elif dp_type == 'enum':
        # Map Hubitat mode to Tuya mode if needed
        if command == 'mode':
            value = MODE_MAP.get('hubitat_to_tuya', {}).get(value, value)
        elif command == 'fan':
            value = FAN_MAP.get('hubitat_to_tuya', {}).get(value, value)

        # Validate enum value
        valid_values = dp_info.get('values', [])
        if valid_values and value not in valid_values:
            raise HTTPException(
                status_code=400,
                detail=f"Invalid value '{value}' for {command}. Valid: {valid_values}"
            )

    # Send command
    if device_conn.set_value(dp, value):
        # Get updated status
        time.sleep(0.5)
        raw_dps = device_conn.get_status(force_refresh=True)
        return {
            "success": True,
            "command": command,
            "value": value,
            "dp": dp,
            "status": parse_status(raw_dps).model_dump()
        }
    else:
        raise HTTPException(status_code=500, detail="Failed to send command")


@app.post("/discover", response_model=DiscoverResponse)
async def discover_devices(_: bool = Depends(verify_token)):
    """Scan network for Tuya devices."""
    devices = tinytuya.deviceScan(verbose=False, maxretry=2, timeout=8)
    device_list = list(devices.values()) if devices else []
    return DiscoverResponse(devices=device_list)


@app.post("/reconnect")
async def reconnect(_: bool = Depends(verify_token)):
    """Force reconnection to device."""
    if device_conn.connect():
        return {"success": True, "message": "Reconnected successfully"}
    else:
        raise HTTPException(status_code=500, detail="Failed to reconnect")


def main():
    """Run the bridge service."""
    if not all([DEVICE_ID, LOCAL_KEY, DEVICE_IP]):
        print("Error: Missing device configuration.")
        print("Set these environment variables or update .env:")
        print("  TUYA_DEVICE_ID")
        print("  TUYA_LOCAL_KEY")
        print("  TUYA_DEVICE_IP")
        sys.exit(1)

    print(f"Starting Pioneer WYT Bridge on {BRIDGE_HOST}:{BRIDGE_PORT}")
    print(f"Device: {DEVICE_ID} @ {DEVICE_IP}")
    print(f"Temperature unit: {TEMP_UNIT}")

    uvicorn.run(
        app,
        host=BRIDGE_HOST,
        port=BRIDGE_PORT,
        log_level="info"
    )


if __name__ == "__main__":
    main()
