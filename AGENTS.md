# Agent Rules

## HAOS Backend — READ ONLY
- SSH to `root@192.168.1.114:222` password `775Ho`
- NEVER modify any files under `custom_components/rover/` (rns_transport.py, codec.py, const.py, etc.)
- Only read logs and files via `cat`/`logread`

## Android Build
- `./gradlew assembleDebug` then `adb install -r .../app-debug.apk`
- ADB logs: `adb logcat -d -s Rover`

## Dashboard — Device Display Rules
- Devices with `areaId = null` → zone "Устройства вне групп"
- Devices with `areaId != null` that don't match any saved `AreaEntity` → also in "Устройства вне групп"
- Never drop devices from display just because their areaId doesn't match a saved area

## Транспорт (Python RNS)
- Транспортный слой — reference Python RNS через Chaquopy, НЕ Kotlin-порт.
  Старый reticulum-kt/RnsManager/ChannelController пока живут параллельно,
  удаляются на этапе чистки.
- Кодек wire-формата — в Python (rover_rns.py). Ручной msgpack НЕ нужен:
  LXMF сам сериализует message.fields (dict с integer-ключами). Маппинг
  integer-ключей ОБЯЗАН совпадать с бэком (dispatcher _GENERAL_MAP/_TP_MAPS,
  rns_transport _OUT_KEY_MAP).
- Адресация: dst в QR = IDENTITY hash сервера. send() сам резолвит через
  recall -> Destination(lxmf/delivery) -> destination hash -> path.
  Прикладной код оперирует только identity hash.
- recall требует identity в кеше (из announce). После холодного старта
  первый send может ждать announce — учитывать в реконнекте.
- Path-wait использует time.monotonic(). Сервер сам отвечает announce на
  path-request (штатный RNS Transport), отдельный серверный код не нужен.
- Известный долг: integer-ключ может иметь разный смысл по типу сообщения
  (key 1 = section в CONFIG, reason в FORBIDDEN; долг CMD-ключей). Разбор
  ключей — ТОЛЬКО после ветвления по tp. Единую таблицу вводим отдельным этапом.
