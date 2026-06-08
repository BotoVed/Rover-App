# Agent Rules

## HAOS Backend — READ ONLY
- SSH to `root@192.168.1.114:222` password `775Ho`
- NEVER modify any files under `custom_components/rover/` (rns_transport.py, codec.py, const.py, etc.)
- Only read logs and files via `cat`/`logread`

## Android Build
- `./gradlew assembleDebug` then `adb install -r .../app-debug.apk`
- ADB logs: `adb logcat -d -s Rover`
