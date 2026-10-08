# Oikos

[![CI](https://github.com/Gekkotron/Oikos/actions/workflows/ci.yml/badge.svg)](https://github.com/Gekkotron/Oikos/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/Gekkotron/Oikos/graph/badge.svg)](https://codecov.io/gh/Gekkotron/Oikos)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

A beautiful, self-discovering home-automation app for Android (Kotlin + Jetpack Compose).

Oikos talks **MQTT** directly. It connects to your local broker at home and goes through an end-to-end encrypted [Athena-HttpMqttGateway](https://github.com/Gekkotron/Athena-HttpMqttGateway) when you're away. It builds its dashboard from **Home Assistant MQTT Discovery** messages, so devices appear on their own, with no hard-coded device list.

> **Status:** foundation and demo Home screen. The core and the Home screen run against a built-in demo house; real broker transports come next. See the [design spec](docs/superpowers/specs/2026-09-24-oikos-design.md) and [mockups](docs/design/mockups/).

## Modules

| Module | Role |
|---|---|
| `:core:model` | Domain types: `Entity`, `StateValue`, `Command`, `UiOverride`, `Area` |
| `:core:crypto` | AES-256-GCM envelope for Athena-HttpMqttGateway |
| `:core:transport` | `MqttTransport` contract and topic matching (+ a test fake) |
| `:core:state` | Jinja-subset templates, one mapper per kind, `StateStore`, optimistic `CommandTracker` |
| `:core:source` | HA MQTT Discovery parser and `MqttDiscoverySource` |
| `:core:demo` | In-process demo broker with a fake house (discovery, state, echoed commands, live power) |
| `:core:designsystem` | Night Glass / Warm Minimal tokens, bundled Inter + Fraunces, cards, pill, chips |
| `:feature:home` | Home screen: rooms, cards, control sheet |
| `:app` | Hilt graph, Navigation 3, demo-mode wiring |

## Try the demo

With a phone connected over USB (developer mode on):

```bash
./gradlew :app:installDebug
adb shell am start -n com.gekkotron.oikos.debug/com.gekkotron.oikos.MainActivity
```

The debug build talks to a built-in demo house, so no broker is needed.

Build and test: `./gradlew check koverXmlReport`.

## Highlights (v1)

- Live state over MQTT subscriptions, with no polling.
- Automatic switching between direct Wi-Fi and the encrypted gateway.
- Device discovery via `homeassistant/…/config`, plus `oikos/ui/…` overrides (rename, rooms, order, card style).
- Home, Energy (live power flow) and Security screens.
- Night Glass (dark) and Warm Minimal (light) themes.
- Push notifications, with the device token registered over MQTT.
- History: sensor and energy charts (24 h, 7 d, 30 d, kWh per day) read from the OikosHistory service. It is found automatically at `http://<broker host>:8090` (OikosHistory runs next to Mosquitto); Settings → History service (or `history.url` in the setup QR, `tools/setup-qr.py --history-url`) only overrides that. Away from home, add `<broker host>:8090` to the gateway key's rules once.

## License

Copyright 2026 Gekkotron. Licensed under the [Apache License 2.0](LICENSE).
