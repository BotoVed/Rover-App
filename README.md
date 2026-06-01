# Rover-App

<p align="center">
  <img src="brand/icon.png" alt="Rover" width="180" />
</p>

**Android-приложение для системы [Rover](https://github.com/BotoVed/Rover)** — дистанционное управление умным домом через LoRa, когда интернет недоступен.

📱 Это один из трёх компонентов экосистемы Rover. Полная картина и документация — в [главном репозитории](https://github.com/BotoVed/Rover).

---

## Что это

Мобильное приложение, которое:

- Подключается по Bluetooth к Meshtastic-устройству на телефоне.
- Получает конфиг (устройства, зоны, пользователи) от шлюза Rover.
- Показывает устройства Home Assistant с группировкой по комнатам.
- Отправляет команды и принимает обновления состояний через LoRa.

Работает без интернета, без сотовой связи, без серверов посредников. Связь — радио, дальность — километры.

## Установка

### Готовый APK
Скачать из [последнего релиза](https://github.com/BotoVed/Rover-App/releases). Установить на Android (разрешить установку из неизвестных источников).

### Сборка из исходников
```bash
export ANDROID_HOME=~/android-sdk
/tmp/gradle-8.6/bin/gradle assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Что нужно

- Android 8.0+ (API 26)
- Bluetooth
- Meshtastic-устройство, спаренное с телефоном (например, SenseCAP T1000-E)
- Работающая сеть Rover с поднятым шлюзом (см. главный репозиторий)

## Связанные репозитории

- **[Rover](https://github.com/BotoVed/Rover)** — плагин Home Assistant (бэк) + общая документация
- **[Rover-Card](https://github.com/BotoVed/Rover-Card)** — карточка Lovelace для дашборда HA

## Лицензия

[GPL v3](https://github.com/BotoVed/Rover/blob/main/LICENSE)
