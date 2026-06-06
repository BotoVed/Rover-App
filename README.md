![Rover](brand/icon.png)

# Rover-App

**Remote Over Radio** — Android-приложение для управления умным домом через
mesh-сети, когда интернета нет или он заблокирован.

Телефон → Bluetooth → LoRa-радио → километры воздуха → шлюз → Home Assistant.
Никаких облаков, никаких серверов, никаких подписок.

---

## Экосистема Rover

| Репозиторий | Что это | Статус |
|---|---|---|
| [Rover](https://github.com/BotoVed/Rover) | Плагин Home Assistant (backend) | ✅ 0.4.2 стабильна |
| [Rover-Card](https://github.com/BotoVed/Rover-Card) | Карточка Lovelace | 🚧 В разработке |
| **Rover-App** (этот) | Android-приложение | 🚧 В разработке |

Протокол и архитектура описаны в
[SPEC.md](https://github.com/BotoVed/Rover/blob/main/SPEC.md).

---

## Как это работает

Rover построен на [Reticulum](https://reticulum.network/) — mesh-сетевом стеке
с end-to-end шифрованием, маршрутизацией до 128 хопов и доставкой с
подтверждением. Поверх работает [LXMF](https://github.com/markqvist/LXMF) —
лёгкий протокол сообщений, который берёт на себя шифрование, фрагментацию и
store-and-forward.

Типичный сценарий:

- **В дороге:** телефон через BLE → RNode (LoRa) → mesh → Home Assistant
- **Дома:** телефон по WiFi → Reticulum TCP интерфейс → Home Assistant

Одна и та же Identity работает через любой интерфейс автоматически.

---

## Возможности

- 🔦 **Свет** — вкл/выкл, яркость, цветовая температура, RGB
- 🌡️ **Климат** — режим, целевая температура, вентилятор
- 📡 **Датчики** — температура, влажность, протечка, движение
- 🔒 **Безопасность** — замки, сигнализация
- 🪟 **Жалюзи** — open/close/stop/позиция
- 🎵 **Медиа** — play/pause/volume
- ⚡ **Переключатели, сцены, кнопки, вентиляторы**

**11 типов устройств Home Assistant** из коробки.

---

## Железо

- **Телефон:** Android 8+ (minSdk 26)
- **Радио:** любой RNode на SX127x / SX126x (подключается по BLE)
- **Шлюз:** Home Assistant с установленной интеграцией Rover

---

## Сборка

```bash
git clone --recurse-submodules https://github.com/BotoVed/Rover-App.git
cd Rover-App
./gradlew assembleDebug
```

APK будет в `app/build/outputs/apk/debug/`.

---

## Безопасность

- **Нет паролей.** Авторизация через криптографические Identity Reticulum (Ed25519/X25519).
- **Ручное одобрение.** Телефон сканирует QR → запрос регистрации → owner одобряет в HA.
- **Лимит 5 активных пультов.** Защита сети от перегрузки.

---

## Лицензия

GPL v3 — код открыт, форки приветствуются.

Если Rover вам пригодился —
[GitHub Sponsors](https://github.com/sponsors/BotoVed) поможет проекту жить.

---

*Потому что 21 век обещал нам летающие машины, а доставил блокировки интернета.*
