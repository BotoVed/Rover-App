# Rover-App

Android application for **[Rover](https://github.com/BotoVed/Rover)** — Home Assistant remote control over LoRa, when the internet is unavailable.

## What it does

- Connects to a Meshtastic device over Bluetooth LE.
- Receives configuration from the Rover gateway (zones, devices, mapping).
- Displays HA devices grouped by area.
- Sends commands and receives state updates over LoRa.

## Installation

### Pre-built APK
Download from the [latest release](https://github.com/BotoVed/Rover-App/releases).
Install on Android (you'll need to allow installation from unknown sources).

### Build from source
```bash
export ANDROID_HOME=~/android-sdk
/tmp/gradle-8.6/bin/gradle assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- Android 8.0+ (API 26).
- Bluetooth.
- A Meshtastic device paired with the phone (e.g., SenseCAP T1000-E, Heltec, RAK).
- A working Rover network with the gateway integration installed.

## First launch

1. Open the app.
2. Settings → enter connection parameters (channel name, PSK, gateway node ID). On MVP — manual entry. Later — QR import.
3. Scan for BLE devices, select the Meshtastic node.
4. Wait for handshake. After `Ready` — request config.
5. Devices grouped by area appear on the main screen.

## Documentation

Common documents live in the main Rover repository:
- **[SPEC.md](https://github.com/BotoVed/Rover/blob/main/SPEC.md)** — protocol specification
- **[DECISIONS.md](https://github.com/BotoVed/Rover/blob/main/DECISIONS.md)** — architectural decisions

App-specific docs in this repository:
- **[AGENT.md](./AGENT.md)** — instructions for AI agents working on the code

## Related

- **[Rover](https://github.com/BotoVed/Rover)** — Home Assistant integration (back)
- **[Rover-Card](https://github.com/BotoVed/Rover-Card)** — Lovelace card for HA dashboard

## License

[GPL v3](https://github.com/BotoVed/Rover/blob/main/LICENSE)
