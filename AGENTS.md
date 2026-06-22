# Agent Rules

## HAOS Backend — READ ONLY
- SSH to `root@192.168.1.114:222` password `775Ho`
- NEVER modify any files under `custom_components/rover/` (rns_transport.py, codec.py, const.py, etc.)
- Only read logs and files via `cat`/`logread`

## Android Build
- `./gradlew assembleDebug` then `adb install -r .../app-debug.apk`
- ADB logs: `adb logcat -d -s Rover`

## Debug Logging
Единый файл-лог для Python и Kotlin: `filesDir/rover_rns/debug.log`
(`/data/data/dev.botoved.rover/files/rover_rns/debug.log` на устройстве).

**Читать:**
```bash
adb shell run-as dev.botoved.rover cat /data/data/dev.botoved.rover/files/rover_rns/debug.log
```
или забрать на машину:
```bash
adb shell run-as dev.botoved.rover cat /data/data/dev.botoved.rover/files/rover_rns/debug.log > /tmp/rover_debug.log
```

**Как работает:**
- `AppLogger` (Kotlin) — пишет в файл И в logcat (`Log.*`). Инициализируется в `RoverApp.onCreate()`.
  Формат: `[HH:mm:ss.SSS] [LEVEL/Kt/TAG] message`
- `_init_file_log()` (Python, rover_rns.py) — устанавливает `sys.stdout` как TeeFile:
  все `print()` и внутренние логи RNS идут одновременно в logcat (`python.stdout`) и в файл.
  Вызывается в начале `start()`.
- Файл открывается в режиме append; каждая сессия начинается с маркера `=== session ===`.

**Правило:** новые точки логирования в RoverService/PyRnsBridge → `AppLogger.*`,
новые `print()` в rover_rns.py автоматически попадают в файл.

## Dashboard — Device Display Rules
- Devices with `areaId = null` → zone "Устройства вне групп"
- Devices with `areaId != null` that don't match any saved `AreaEntity` → also in "Устройства вне групп"
- Never drop devices from display just because their areaId doesn't match a saved area

## Chaquopy Java↔Python Bridge Rules
- **Никаких коллекций через мост.** Python `dict` → Java `Map` вызывает
  `Cannot convert dict object to java.util.Map`. Java `List`/`Map` переданные
  через `PyObject.fromJava()` — не итерируемы в Python.
- **Универсальное правило:** все данные через мост — только примитивные строки:
  - Map/dict → JSON-строка: `json.dumps(fields)` / `JSONObject.toString()`
  - List → CSV-строка: `sections.joinToString(",")` / `",".split(",")`
- `send_cmd`, `send_req`, `send_ping` — принимают JSON/CSV строки, парсят внутри Python.
- `onMessage(sourceHex, fieldsJson)` — принимает JSON-строку, парсится через
  `fromJson()` в Kotlin рекурсивно (JSONObject→Map, JSONArray→List).
- **Блокирующие Python-вызовы** (`sendReq`, `sendPing`, `sendCmd`) ВСЕГДА запускать
  в `serviceScope.launch(Dispatchers.IO)`, иначе блокируют основной корутин-цикл.
- `await_path` перед REGISTER/REQ/PING — намеренное решение, НЕ удалять.
- Код: `No !!`, `No Thread.sleep`, лог-тег = "Rover".

## Онбординг (Python RNS)
- После отправки REGISTER сервер может не прислать CONFIG сразу — он присылает
  PONG и ждёт REQ.
- В PONG-хендлере (tp=6) используй `pyServerDst ?: pendingRegisterDst` как dst,
  чтобы REQ работал и во время онбординга (когда `pyServerDst` ещё null).
- Камера запрашивается в `MainActivity` вместе с BLE/location — `OnboardingScreen`
  только проверяет статус через `LifecycleEventObserver.ON_RESUME`.

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
