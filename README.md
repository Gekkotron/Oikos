# Oikos

[![CI](https://github.com/Gekkotron/Oikos/actions/workflows/ci.yml/badge.svg)](https://github.com/Gekkotron/Oikos/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/Gekkotron/Oikos/graph/badge.svg)](https://codecov.io/gh/Gekkotron/Oikos)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

A beautiful, self-discovering home-automation app for Android (Kotlin + Jetpack Compose).

Oikos talks **MQTT** directly. It connects to your local broker at home and goes through an end-to-end encrypted [Athena-HttpMqttGateway](https://github.com/Gekkotron/Athena-HttpMqttGateway) when you're away. It builds its dashboard from **Home Assistant MQTT Discovery** messages, so devices appear on their own, with no hard-coded device list.

> **Status:** design phase. See the [design spec](docs/superpowers/specs/2026-09-24-oikos-design.md) and [mockups](docs/design/mockups/).

## Highlights (v1)

- Live state over MQTT subscriptions, with no polling.
- Automatic switching between direct Wi-Fi and the encrypted gateway.
- Device discovery via `homeassistant/…/config`, plus `oikos/ui/…` overrides (rename, rooms, order, card style).
- Home, Energy (live power flow) and Security screens.
- Night Glass (dark) and Warm Minimal (light) themes.
- Push notifications, with the device token registered over MQTT.

## License

Copyright 2026 Gekkotron. Licensed under the [Apache License 2.0](LICENSE).
