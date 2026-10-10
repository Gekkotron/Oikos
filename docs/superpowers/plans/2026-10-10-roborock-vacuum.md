# Roborock Vacuum Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Roborock S5 appears in Oikos as a vacuum with live state, battery, Start/Pause/Stop/Dock/Locate and suction power, announced by Roborock2Mqtt through HA MQTT discovery.

**Architecture:** The bridge keeps shelling out to `miiocli`; a new pure `ha_discovery.py` builds the discovery config, the HA state JSON and the command translation, and `main.py` wires it to MQTT behind `HA_DISCOVERY_ENABLED`. Oikos gets a new `Kind.VACUUM` end to end: model types, discovery parsing, a `VacuumMapper` for the HA vacuum state schema, and a card + control sheet.

**Tech Stack:** Python 3.12, paho-mqtt 2.1, python-miio 0.5.12 (`miiocli`), unittest. Kotlin, Jetpack Compose, kotlinx.serialization, kotlin.test, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-10-roborock-vacuum-design.md`

## Global Constraints

- Bridge repo: `~/Documents/SourcesPerso/RoborockMqtt` (remote `Gekkotron/Roborock2Mqtt`), branch `main`. Oikos repo: `~/Documents/SourcesPerso/Oikos`, branch `master`.
- Every commit uses `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit ...` and ends with the lines `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01F6NhNhvBxcNwJmCw7ySjfH`. Never write the user's real name anywhere.
- `HA_DISCOVERY_ENABLED` defaults to **false**; `HA_DISCOVERY_PREFIX` defaults to `homeassistant`; `HA_STATUS_INTERVAL` defaults to `30` seconds.
- HA state topic `<prefix>/ha/state` (retained), command topic `<prefix>/ha/command`, fan speed topic `<prefix>/ha/fan_speed/set`, availability `<prefix>/alive` with `1`/`0`.
- HA vacuum states are exactly `cleaning, docked, paused, idle, returning, error`.
- Oikos vacuum actions are exactly `start, pause, stop, return_to_base, locate`.
- Strings in both `feature/home/src/main/res/values/strings.xml` and `values-fr/strings.xml`.
- No widget or voice features for the vacuum; those modules only get the minimum to compile.
- Android regex gotcha: escape literal `]` and `}` in any `Regex` (ICU throws at class init).
- Do not stage `.frigate-pass` (Oikos) or any `.env` file.

## Review Focus

1. **`roborock/ha/command` hitting the raw miiocli path:** the old handler matched `endswith("/command")`. Expected: HA payloads are translated (`return_to_base`→`home`), never sent verbatim. Pinned by `RouteTest` in Task 1.
2. **Bridge restart after a crash:** retained LWT `0` on `roborock/alive` with a non-retained `1` heartbeat leaves the vacuum unavailable in Oikos for up to 240 s. Expected: available as soon as the bridge is up. Fixed in Task 2 (retained `1`), checked in Task 2 Step 6.
3. **Vacuum unreachable / miiocli timeout** (`"Error: miiocli timeout"` or empty output): expected no crash, no discovery until the unit answers, last state kept. Pinned by `parse_json` tests in Task 1 and the guard in Task 2.
4. **`fan_power` matching no preset** (custom value): the bridge sends `"fan_speed": null`; Oikos must clear the selected chip, not keep a stale one. Pinned by `VacuumMapperTest.explicit null fan speed clears it` in Task 4.
5. **Odd state payloads** (unknown state string, battery `99.6`, partial JSON, non-JSON): expected previous state kept, battery rounded to 100, non-JSON reported as an error. Pinned by `VacuumMapperTest` in Task 4.

---

### Task 1: Bridge pure HA module

**Files:**
- Create: `~/Documents/SourcesPerso/RoborockMqtt/ha_discovery.py`
- Test: `~/Documents/SourcesPerso/RoborockMqtt/tests/test_ha_discovery.py`

**Interfaces:**
- Produces (used by Task 2):
  - `discovery_enabled(value: str | None) -> bool` (None/empty → False)
  - `parse_json(text: str) -> dict | None`
  - `vacuum_state(status: dict, presets: dict) -> dict`
  - `command_for(action: str) -> str | None`
  - `fan_speed_command(name: str, presets: dict) -> str | None`
  - `discovery_config(prefix: str, discovery_prefix: str, info: dict, presets: dict, fallback_id: str) -> tuple[str, dict]`
  - `redact_info(text: str) -> str`
  - `route(topic: str, prefix: str) -> str | None` returning `"command"`, `"ha_command"`, `"ha_fan_speed"` or None

- [ ] **Step 1: Write the failing tests**

```python
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import ha_discovery as ha

PRESETS = {"Silent": 101, "Standard": 102, "Medium": 103, "Turbo": 104, "Gentle": 105, "Auto": 106}
STATUS = {"state": 8, "battery": 100, "fan_power": 104, "error_code": 0, "in_cleaning": 0}
INFO = {"model": "roborock.vacuum.s5", "fw_ver": "3.5.8_002034", "mac": "40:31:3C:A8:B9:64", "token": "secret"}


class DiscoveryEnabledTest(unittest.TestCase):
    def test_default_off(self):
        self.assertFalse(ha.discovery_enabled(None))
        self.assertFalse(ha.discovery_enabled(""))
        self.assertFalse(ha.discovery_enabled("false"))

    def test_on(self):
        for value in ("true", "1", "yes", "on", " TRUE "):
            self.assertTrue(ha.discovery_enabled(value))


class ParseJsonTest(unittest.TestCase):
    def test_object(self):
        self.assertEqual(ha.parse_json('{"state": 8}'), {"state": 8})

    def test_not_an_object(self):
        self.assertIsNone(ha.parse_json("Error: miiocli timeout"))
        self.assertIsNone(ha.parse_json(""))
        self.assertIsNone(ha.parse_json("[1, 2]"))


class VacuumStateTest(unittest.TestCase):
    def test_docked(self):
        self.assertEqual(ha.vacuum_state(STATUS, PRESETS),
                         {"state": "docked", "battery_level": 100, "fan_speed": "Turbo", "error_code": 0})

    def test_state_mapping(self):
        expected = {
            5: "cleaning", 11: "cleaning", 16: "cleaning", 17: "cleaning", 18: "cleaning",
            6: "returning", 15: "returning", 8: "docked", 100: "docked", 10: "paused",
            9: "error", 12: "error", 1: "idle", 2: "idle", 3: "idle", 13: "idle", 14: "idle", 999: "idle",
        }
        for code, state in expected.items():
            self.assertEqual(ha.vacuum_state({**STATUS, "state": code}, PRESETS)["state"], state, code)

    def test_unknown_fan_power(self):
        self.assertIsNone(ha.vacuum_state({**STATUS, "fan_power": 60}, PRESETS)["fan_speed"])

    def test_missing_fields(self):
        self.assertEqual(ha.vacuum_state({}, PRESETS),
                         {"state": "idle", "battery_level": None, "fan_speed": None, "error_code": 0})


class CommandTest(unittest.TestCase):
    def test_actions(self):
        self.assertEqual(ha.command_for("start"), "start")
        self.assertEqual(ha.command_for("pause"), "pause")
        self.assertEqual(ha.command_for("stop"), "stop")
        self.assertEqual(ha.command_for("return_to_base"), "home")
        self.assertEqual(ha.command_for("locate"), "find")
        self.assertEqual(ha.command_for(" start\n"), "start")

    def test_unknown_action(self):
        self.assertIsNone(ha.command_for("clean_spot"))
        self.assertIsNone(ha.command_for("rm -rf"))

    def test_fan_speed(self):
        self.assertEqual(ha.fan_speed_command("Turbo", PRESETS), "set_fan_speed 104")
        self.assertIsNone(ha.fan_speed_command("Max", PRESETS))


class DiscoveryConfigTest(unittest.TestCase):
    def test_payload(self):
        topic, payload = ha.discovery_config("roborock", "homeassistant", INFO, PRESETS, "192.168.1.200")
        self.assertEqual(topic, "homeassistant/vacuum/roborock_40313ca8b964/roborock_40313ca8b964/config")
        self.assertEqual(payload["unique_id"], "roborock_40313ca8b964")
        self.assertIsNone(payload["name"])
        self.assertEqual(payload["state_topic"], "roborock/ha/state")
        self.assertEqual(payload["command_topic"], "roborock/ha/command")
        self.assertEqual(payload["set_fan_speed_topic"], "roborock/ha/fan_speed/set")
        self.assertEqual(payload["fan_speed_list"], ["Silent", "Standard", "Medium", "Turbo", "Gentle", "Auto"])
        self.assertEqual(payload["supported_features"],
                         ["start", "pause", "stop", "return_home", "locate", "fan_speed", "battery", "status"])
        self.assertEqual(payload["availability_topic"], "roborock/alive")
        self.assertEqual((payload["payload_available"], payload["payload_not_available"]), ("1", "0"))
        self.assertEqual(payload["device"], {
            "identifiers": ["roborock_40313ca8b964"], "name": "Roborock", "manufacturer": "Roborock",
            "model": "roborock.vacuum.s5", "sw_version": "3.5.8_002034",
        })
        self.assertNotIn("secret", json.dumps(payload))

    def test_fallback_id_without_mac(self):
        topic, payload = ha.discovery_config("roborock", "homeassistant", {}, PRESETS, "192.168.1.200")
        self.assertEqual(payload["unique_id"], "roborock_192_168_1_200")
        self.assertNotIn("model", payload["device"])


class RedactInfoTest(unittest.TestCase):
    def test_drops_token(self):
        redacted = ha.redact_info(json.dumps(INFO))
        self.assertNotIn("secret", redacted)
        self.assertEqual(json.loads(redacted)["model"], "roborock.vacuum.s5")

    def test_passthrough(self):
        self.assertEqual(ha.redact_info("Error: timeout"), "Error: timeout")
        self.assertEqual(ha.redact_info('{"a": 1}'), '{"a": 1}')


class RouteTest(unittest.TestCase):
    def test_routes(self):
        self.assertEqual(ha.route("roborock/command", "roborock"), "command")
        self.assertEqual(ha.route("roborock/ha/command", "roborock"), "ha_command")
        self.assertEqual(ha.route("roborock/ha/fan_speed/set", "roborock"), "ha_fan_speed")

    def test_ignored(self):
        for topic in ("roborock/ha/state", "roborock/status", "roborock/result", "roborock/alive", "other/command"):
            self.assertIsNone(ha.route(topic, "roborock"), topic)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ~/Documents/SourcesPerso/RoborockMqtt && python3 -m unittest discover -s tests -v`
Expected: FAIL / ERROR with `ModuleNotFoundError: No module named 'ha_discovery'`

- [ ] **Step 3: Implement `ha_discovery.py`**

```python
"""
Home Assistant MQTT discovery for the Roborock bridge.

Pure functions only (no MQTT, no subprocess) so they can be unit tested:
- discovery_config(): the retained config announcing the vacuum
- vacuum_state(): miiocli status -> JSON state published on <prefix>/ha/state
- command_for() / fan_speed_command(): an HA command -> the miiocli command to run
"""
import json
import re

# miio vacuum state codes -> HA vacuum state; anything unlisted is idle
_STATES = {
    **dict.fromkeys((5, 11, 16, 17, 18), "cleaning"),
    **dict.fromkeys((6, 15), "returning"),
    **dict.fromkeys((8, 100), "docked"),
    10: "paused",
    **dict.fromkeys((9, 12), "error"),
}

# HA command payload -> miiocli subcommand
_ACTIONS = {"start": "start", "pause": "pause", "stop": "stop", "return_to_base": "home", "locate": "find"}

SUPPORTED_FEATURES = ["start", "pause", "stop", "return_home", "locate", "fan_speed", "battery", "status"]


def discovery_enabled(value):
    """ HA_DISCOVERY_ENABLED: off unless true/1/yes/on """
    return (value or "").strip().lower() in ("true", "1", "yes", "on")


def parse_json(text):
    """ miiocli JSON output as a dict, None for errors and anything else """
    try:
        data = json.loads(text)
    except (TypeError, ValueError):
        return None
    return data if isinstance(data, dict) else None


def vacuum_state(status, presets):
    fan_power = status.get("fan_power")
    fan_speed = next((name for name, code in presets.items() if code == fan_power), None)
    return {
        "state": _STATES.get(status.get("state"), "idle"),
        "battery_level": status.get("battery"),
        "fan_speed": fan_speed,
        "error_code": status.get("error_code", 0),
    }


def command_for(action):
    return _ACTIONS.get(action.strip())


def fan_speed_command(name, presets):
    code = presets.get(name.strip())
    return f"set_fan_speed {code}" if code is not None else None


def _unique_id(info, fallback_id):
    raw = (info.get("mac") or "").replace(":", "") or fallback_id
    return "roborock_" + re.sub(r"[^0-9A-Za-z]+", "_", raw).strip("_").lower()


def discovery_config(prefix, discovery_prefix, info, presets, fallback_id):
    """ (topic, payload) announcing the vacuum with the HA state schema """
    uid = _unique_id(info, fallback_id)
    device = {"identifiers": [uid], "name": "Roborock", "manufacturer": "Roborock"}
    if info.get("model"):
        device["model"] = info["model"]
    if info.get("fw_ver"):
        device["sw_version"] = info["fw_ver"]
    payload = {
        "name": None,
        "unique_id": uid,
        "object_id": uid,
        "device": device,
        "state_topic": f"{prefix}/ha/state",
        "command_topic": f"{prefix}/ha/command",
        "set_fan_speed_topic": f"{prefix}/ha/fan_speed/set",
        "fan_speed_list": list(presets),
        "supported_features": list(SUPPORTED_FEATURES),
        "availability_topic": f"{prefix}/alive",
        "payload_available": "1",
        "payload_not_available": "0",
    }
    return f"{discovery_prefix}/vacuum/{uid}/{uid}/config", payload


def redact_info(text):
    """ an `info` result without the miio token; anything else unchanged """
    data = parse_json(text)
    if data is None or "token" not in data:
        return text
    data.pop("token")
    return json.dumps(data, indent=2)


def route(topic, prefix):
    return {
        f"{prefix}/command": "command",
        f"{prefix}/ha/command": "ha_command",
        f"{prefix}/ha/fan_speed/set": "ha_fan_speed",
    }.get(topic)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ~/Documents/SourcesPerso/RoborockMqtt && python3 -m unittest discover -s tests -v`
Expected: all tests PASS

- [ ] **Step 5: Commit**

```bash
cd ~/Documents/SourcesPerso/RoborockMqtt
git add ha_discovery.py tests/test_ha_discovery.py
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(ha): pure HA vacuum discovery module" -m $'State mapping, command translation, discovery payload, token redaction and topic routing.\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\nClaude-Session: https://claude.ai/code/session_01F6NhNhvBxcNwJmCw7ySjfH'
```

---

### Task 2: Bridge MQTT wiring, Docker, README

**Files:**
- Modify: `~/Documents/SourcesPerso/RoborockMqtt/main.py`
- Modify: `~/Documents/SourcesPerso/RoborockMqtt/Dockerfile` (`COPY main.py .` → also copy `ha_discovery.py`)
- Modify: `~/Documents/SourcesPerso/RoborockMqtt/README.md` (env table + "Home Assistant discovery" section + topics)
- Modify or create: `~/Documents/SourcesPerso/RoborockMqtt/.env.example` (add the three HA variables, commented defaults; create it if missing since the README refers to it)

**Interfaces:**
- Consumes: everything Task 1 produces.

- [ ] **Step 1: Add config and HA state to `main.py`**

After `ALIVE_INTERVAL = ...` add (and `import json`, `import ha_discovery` at the top):

```python
HA_DISCOVERY_ENABLED = ha_discovery.discovery_enabled(os.getenv("HA_DISCOVERY_ENABLED"))
HA_DISCOVERY_PREFIX = os.getenv("HA_DISCOVERY_PREFIX", "homeassistant").rstrip("/")
HA_STATUS_INTERVAL = int(os.getenv("HA_STATUS_INTERVAL", "30"))

# Fan speed presets once announced; None until the vacuum answered info + fan_speed_presets
ha_presets: dict | None = None
ha_started = threading.Event()
```

- [ ] **Step 2: Add the HA functions to `main.py`** (below `alive`)

```python
def ha_announce(client: mqtt.Client) -> None:
    global ha_presets
    presets = ha_discovery.parse_json(miiocli("fan_speed_presets"))
    info = ha_discovery.parse_json(miiocli("info"))
    if not presets or info is None:
        log.warning("HA discovery postponed: vacuum did not answer info/fan_speed_presets")
        return
    config_topic, payload = ha_discovery.discovery_config(TOPIC_PREFIX, HA_DISCOVERY_PREFIX, info, presets, VACUUM_IP)
    client.publish(config_topic, json.dumps(payload), qos=1, retain=True)
    ha_presets = presets
    log.info("HA discovery published on %s", config_topic)


def ha_refresh(client: mqtt.Client) -> None:
    if ha_presets is None:
        return
    status = ha_discovery.parse_json(miiocli("status"))
    if status is None:
        return
    state = ha_discovery.vacuum_state(status, ha_presets)
    client.publish(topic("ha/state"), json.dumps(state), qos=1, retain=True)


def ha_loop(client: mqtt.Client) -> None:
    if ha_presets is None:
        ha_announce(client)
    ha_refresh(client)
    schedule(HA_STATUS_INTERVAL, lambda: ha_loop(client))


def ha_after_command(client: mqtt.Client) -> None:
    ha_refresh(client)
    schedule(5, lambda: ha_refresh(client))


def ha_command(client: mqtt.Client, payload: str) -> None:
    command = ha_discovery.command_for(payload)
    if command is None:
        log.warning("Ignoring unknown HA vacuum command: %r", payload)
        return
    publish(client, topic("result"), miiocli(command))
    ha_after_command(client)


def ha_fan_speed(client: mqtt.Client, payload: str) -> None:
    command = ha_discovery.fan_speed_command(payload, ha_presets or {})
    if command is None:
        log.warning("Ignoring unknown fan speed: %r", payload)
        return
    publish(client, topic("result"), miiocli(command))
    ha_after_command(client)
```

- [ ] **Step 3: Retain the heartbeat, start the HA loop once, route topics exactly**

Replace `alive`, `on_connect` and `on_message` with:

```python
def alive(client: mqtt.Client) -> None:
    # Retained so it replaces a retained LWT "0" left by an earlier crash
    client.publish(topic("alive"), "1", retain=True)
    schedule(ALIVE_INTERVAL, lambda: alive(client))


def on_connect(client, userdata, flags, reason_code, properties=None):
    log.info("Connected to MQTT broker (code=%s)", reason_code)
    client.subscribe(topic("#"))
    status(client)
    consumable_status(client)
    alive(client)
    if HA_DISCOVERY_ENABLED and not ha_started.is_set():
        ha_started.set()
        ha_loop(client)


def on_message(client, userdata, msg):
    payload = msg.payload.decode("utf-8", errors="replace")
    log.debug("MQTT %s: %s", msg.topic, payload)

    kind = ha_discovery.route(msg.topic, TOPIC_PREFIX)
    if kind == "ha_command":
        if HA_DISCOVERY_ENABLED:
            ha_command(client, payload)
        return
    if kind == "ha_fan_speed":
        if HA_DISCOVERY_ENABLED:
            ha_fan_speed(client, payload)
        return
    if kind != "command":
        return

    result = miiocli(payload)
    if payload == "status":
        publish(client, topic("status"), result)
    elif payload == "consumable_status":
        publish(client, topic("consumable_status"), result)
    else:
        if payload.strip() == "info":
            result = ha_discovery.redact_info(result)
        publish(client, topic("result"), result)
```

Note: on_connect runs again after a reconnect; `ha_started` keeps a single HA loop, and the retained discovery config survives on the broker.

- [ ] **Step 4: Dockerfile and docs**

In `Dockerfile` replace `COPY main.py .` with `COPY main.py ha_discovery.py ./`.

In `README.md`, add to the configuration table:

```markdown
| `HA_DISCOVERY_ENABLED` | no | `false` | Publish Home Assistant MQTT discovery for the vacuum (`true` to enable) |
| `HA_DISCOVERY_PREFIX` | no | `homeassistant` | Discovery prefix |
| `HA_STATUS_INTERVAL` | no | `30` | HA state refresh interval (s) |
```

and a section after "MQTT topics":

```markdown
## Home Assistant discovery

With `HA_DISCOVERY_ENABLED=true` the bridge announces one `vacuum` entity (HA state schema) on
`homeassistant/vacuum/roborock_<mac>/roborock_<mac>/config`, built from the unit's `info` and `fan_speed_presets`.

| Topic | Direction | Payload |
| --- | --- | --- |
| `roborock/ha/state` | out, retained | `{"state": "docked", "battery_level": 100, "fan_speed": "Turbo", "error_code": 0}` every `HA_STATUS_INTERVAL` s and right after each command |
| `roborock/ha/command` | in | `start`, `pause`, `stop`, `return_to_base`, `locate` |
| `roborock/ha/fan_speed/set` | in | a preset name from `fan_speed_list`, e.g. `Turbo` |

Availability is `roborock/alive` (`1` / `0`, retained). The `info` result on `roborock/result` no longer contains the miio token.
```

Add the same three variables to `.env.example` as commented defaults (`# HA_DISCOVERY_ENABLED=false` etc.); create the file with all README variables (empty values for required ones) if it does not exist.

- [ ] **Step 5: Static check + tests**

Run: `cd ~/Documents/SourcesPerso/RoborockMqtt && python3 -m py_compile main.py ha_discovery.py && python3 -m unittest discover -s tests -v`
Expected: no output from py_compile, all tests PASS.

- [ ] **Step 6: Local end-to-end smoke (no hardware)**

Start a local broker and a fake `miiocli` on PATH, run the bridge for ~10 s, and check the topics:

```bash
cd ~/Documents/SourcesPerso/RoborockMqtt
SCRATCH=$(mktemp -d)
cat > "$SCRATCH/miiocli" <<'EOF'
#!/bin/sh
for last; do :; done
case "$last" in
  status) echo '{"state": 8, "battery": 100, "fan_power": 104, "error_code": 0}' ;;
  fan_speed_presets) echo '{"Silent": 101, "Standard": 102, "Turbo": 104}' ;;
  info) echo '{"model": "roborock.vacuum.s5", "fw_ver": "3.5.8", "mac": "40:31:3C:A8:B9:64", "token": "secret"}' ;;
  *) echo '["ok"]' ;;
esac
EOF
chmod +x "$SCRATCH/miiocli"
mosquitto -p 18830 -d
(PATH="$SCRATCH:$PATH" VACUUM_IP=127.0.0.1 VACUUM_TOKEN=x BROKER_IP=127.0.0.1 BROKER_PORT=18830 HA_DISCOVERY_ENABLED=true timeout 12 python3 main.py &)
sleep 4
mosquitto_pub -p 18830 -t roborock/command -m info
mosquitto_pub -p 18830 -t roborock/ha/command -m return_to_base
timeout 6 mosquitto_sub -p 18830 -t 'roborock/#' -t 'homeassistant/#' -v
pkill -f "mosquitto -p 18830"
```

Expected: a retained `homeassistant/vacuum/roborock_40313ca8b964/roborock_40313ca8b964/config`, a retained `roborock/ha/state {"state": "docked", ...}`, a retained `roborock/alive 1`, a `roborock/result` for `info` without `"token"`, and the bridge log showing `miiocli ... home` (not `return_to_base`).

- [ ] **Step 7: Commit and push**

```bash
cd ~/Documents/SourcesPerso/RoborockMqtt
git add main.py Dockerfile README.md .env.example
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(ha): opt-in Home Assistant vacuum discovery" -m $'HA_DISCOVERY_ENABLED announces a vacuum entity, publishes its state every HA_STATUS_INTERVAL s and translates HA commands. The alive heartbeat is retained and the info result no longer leaks the miio token.\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\nClaude-Session: https://claude.ai/code/session_01F6NhNhvBxcNwJmCw7ySjfH'
git push origin main
```

---

### Task 3: Oikos model + discovery for `vacuum`

**Files:**
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/Kind.kt`
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/StateValue.kt`
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/Command.kt`
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/Entity.kt` (Capabilities)
- Modify: `core/source/src/main/resources/com/gekkotron/oikos/source/abbreviations.json` (payload table)
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParser.kt`
- Create: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/VacuumMapper.kt` (capability helpers only in this task; decode/encode in Task 4)
- Modify: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/KindMapper.kt`
- Modify: every exhaustive `when` that no longer compiles (expected: `feature/home/.../presentation/HomeUiBuilder.kt` `tapFor`, `feature/home/.../ui/KindIcon.kt`, `feature/home/.../presentation/CardFormatter.kt`, `feature/widget/.../TileMapper.kt` ×2, `feature/widget/.../TileActions.kt`). Task 5 replaces the placeholders in `feature/home`; widget handling here is final.
- Test: `core/source/src/test/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParserTest.kt`

**Interfaces:**
- Produces:
  - `Kind.VACUUM`
  - `StateValue.Vacuum(val state: String?, val battery: Int?, val fanSpeed: String?)`
  - `Command.VacuumAction(val action: String)`, `Command.SetFanSpeed(val speed: String)`
  - `Capabilities.vacuumActions: List<String> = emptyList()`, `Capabilities.fanSpeeds: List<String> = emptyList()`
  - `VacuumMapper.STATES: Set<String>`, `VacuumMapper.actions(b: MqttBindings): List<String>`, `VacuumMapper.fanSpeeds(b: MqttBindings): List<String>`

- [ ] **Step 1: Write the failing parser test** (append inside `capabilities per kind` in `DiscoveryParserTest.kt`, and import `com.gekkotron.oikos.model.Kind` if not already)

```kotlin
        val vacuum = single(
            "homeassistant/vacuum/v/config",
            """{"stat_t":"s","cmd_t":"c","set_fan_spd_t":"f","fanspd_lst":["Silent","Turbo"],
               "sup_feat":["start","pause","return_home","locate","fan_speed","battery","status"]}""",
        ).entity
        assertEquals(Kind.VACUUM, vacuum.kind)
        assertEquals(listOf("start", "pause", "return_to_base", "locate"), vacuum.capabilities.vacuumActions)
        assertEquals(listOf("Silent", "Turbo"), vacuum.capabilities.fanSpeeds)
        // HA defaults: start, stop, return_home, status, battery, clean_spot; no fan_speed feature → no speeds
        val vacuumDefault = single("homeassistant/vacuum/w/config", """{"stat_t":"s","cmd_t":"c","set_fan_spd_t":"f","fanspd_lst":["Silent"]}""").entity.capabilities
        assertEquals(listOf("start", "stop", "return_to_base"), vacuumDefault.vacuumActions)
        assertEquals(emptyList(), vacuumDefault.fanSpeeds)
        // fan_speed supported but no set_fan_speed_topic → no speeds
        val noFanTopic = single("homeassistant/vacuum/x/config", """{"stat_t":"s","cmd_t":"c","fanspd_lst":["Silent"],"sup_feat":["start","fan_speed"]}""").entity.capabilities
        assertEquals(emptyList(), noFanTopic.fanSpeeds)
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:source:test --tests '*DiscoveryParserTest*'`
Expected: compilation FAIL (`Unresolved reference: VACUUM` / `vacuumActions`).

- [ ] **Step 3: Model types**

`Kind.kt`:

```kotlin
enum class Kind { LIGHT, SWITCH, COVER, CLIMATE, SENSOR, BINARY_SENSOR, ALARM_PANEL, NUMBER, SELECT, BUTTON, VACUUM }
```

`StateValue.kt`, after `Alarm`:

```kotlin
    /** [state] is one of HA's vacuum states (cleaning, docked, paused, idle, returning, error); [battery] is 0..100. */
    data class Vacuum(val state: String?, val battery: Int?, val fanSpeed: String?) : StateValue
```

`Command.kt`, after `Disarm`:

```kotlin
    /** [action] is one of start, pause, stop, return_to_base, locate. */
    data class VacuumAction(val action: String) : Command
    data class SetFanSpeed(val speed: String) : Command
```

`Entity.kt` `Capabilities`, after `fanModes`:

```kotlin
    /** The vacuum's supported actions (start, pause, stop, return_to_base, locate), in that order. */
    val vacuumActions: List<String> = emptyList(),
    /** The vacuum's `fan_speed_list`; empty when it cannot set a fan speed. */
    val fanSpeeds: List<String> = emptyList(),
```

- [ ] **Step 4: Abbreviations**

Add to the `"payload"` object of `abbreviations.json` (keep the file's existing formatting/order style; skip keys already present):

```json
"fanspd_lst": "fan_speed_list",
"set_fan_spd_t": "set_fan_speed_topic",
"pl_strt": "payload_start",
"pl_paus": "payload_pause",
"pl_ret": "payload_return_to_base",
"pl_loc": "payload_locate",
"pl_cln_sp": "payload_clean_spot",
```

- [ ] **Step 5: `VacuumMapper` capability helpers + registration**

Create `VacuumMapper.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings

/** HA vacuum, state schema: JSON state on `state_topic`, plain payloads on `command_topic`. */
object VacuumMapper : KindMapper {
    val STATES = setOf("cleaning", "docked", "paused", "idle", "returning", "error")

    /** HA's default `supported_features` when the config has none. */
    val DEFAULT_FEATURES = listOf("start", "stop", "return_home", "status", "battery", "clean_spot")

    /** Feature name → Oikos action, in the order the sheet shows them. */
    private val ACTION_FEATURES = linkedMapOf(
        "start" to "start", "pause" to "pause", "stop" to "stop", "return_home" to "return_to_base", "locate" to "locate",
    )

    private fun features(b: MqttBindings): List<String> = b.strList("supported_features") ?: DEFAULT_FEATURES

    fun actions(b: MqttBindings): List<String> = features(b).let { f -> ACTION_FEATURES.filterKeys { it in f }.values.toList() }

    fun fanSpeeds(b: MqttBindings): List<String> =
        if ("fan_speed" in features(b) && b.str("set_fan_speed_topic") != null) b.strList("fan_speed_list").orEmpty() else emptyList()

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded = Decoded.Ignored
}
```

`KindMapper.kt` `forKind`: add `Kind.VACUUM -> VacuumMapper`.

- [ ] **Step 6: DiscoveryParser**

In `KINDS` add `"vacuum" to Kind.VACUUM,`. Add the import `com.gekkotron.oikos.state.kinds.VacuumMapper`. In `capabilities(...)` before `else ->`:

```kotlin
        Kind.VACUUM -> Capabilities(vacuumActions = VacuumMapper.actions(b), fanSpeeds = VacuumMapper.fanSpeeds(b))
```

- [ ] **Step 7: Make every exhaustive `when` compile**

Run `./gradlew assembleDebug` and fix each error:
- `HomeUiBuilder.tapFor`: add `Kind.VACUUM` to the `TapAction.OPEN_SHEET` branch, and add `Kind.VACUUM` to `SHEET_KINDS`.
- `KindIcon.kt`: temporary `Kind.VACUUM -> IconSpec.Static(OikosGlyph.PLAY, on = false)` (Task 5 swaps the glyph).
- `CardFormatter.format`: temporary `is StateValue.Vacuum -> CardValue.Text(value.state ?: "")` (Task 5 replaces it).
- `TileMapper` value: `is StateValue.Vacuum -> value.state?.let { TileValue.Text(it) } ?: TileValue.None`; icon: add `Kind.VACUUM` to the `TileIcon.SENSOR` branch.
- `TileActions.forTap`: add `Kind.VACUUM -> TileAction.OpenHome`.
- Any other site the compiler reports (voice, security): treat `VACUUM` like a kind the feature does not support (exclude it / no-op), never a crash.

- [ ] **Step 8: Run tests**

Run: `./gradlew :core:source:test :core:state:test :feature:home:testDebugUnitTest :feature:widget:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add core/model core/source core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds feature/home/src/main feature/widget/src/main core/voice
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(vacuum): discover HA vacuum entities" -m $'Kind.VACUUM, vacuum state/commands/capabilities, discovery abbreviations; UI placeholders until the card lands.\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\nClaude-Session: https://claude.ai/code/session_01F6NhNhvBxcNwJmCw7ySjfH'
```

(`git add core/voice` is a no-op if voice needed no change.)

---

### Task 4: `VacuumMapper` decode / encode

**Files:**
- Modify: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/VacuumMapper.kt`
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/VacuumMapperTest.kt`

**Interfaces:**
- Consumes: Task 3 types and `VacuumMapper` helpers; `outgoing(b, topic, payload)` and `publish(b, topicKey, templateKey, value)` from `Common.kt`.
- Produces: `VacuumMapper.decode/encode/predict/feedbackTopics` per spec.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.model.StateValue.Vacuum
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class VacuumMapperTest {
    // What Roborock2Mqtt publishes for the S5.
    private val roborock = bindings(
        "vacuum",
        """{"state_topic":"roborock/ha/state","command_topic":"roborock/ha/command",
           "set_fan_speed_topic":"roborock/ha/fan_speed/set",
           "fan_speed_list":["Silent","Standard","Medium","Turbo","Gentle","Auto"],
           "supported_features":["start","pause","stop","return_home","locate","fan_speed","battery","status"]}""",
    )

    private fun publishes(topic: String, payload: String) = Encoded.Publish(listOf(Outgoing(topic, payload, false, 0)))

    @Test
    fun `full state`() {
        assertEquals(
            Decoded.State(Vacuum("docked", 100, "Turbo")),
            VacuumMapper.decode(roborock, "roborock/ha/state", """{"state":"docked","battery_level":100,"fan_speed":"Turbo","error_code":0}""", Unknown),
        )
    }

    @Test
    fun `partial state keeps previous values`() {
        assertEquals(
            Decoded.State(Vacuum("cleaning", 80, "Turbo")),
            VacuumMapper.decode(roborock, "roborock/ha/state", """{"state":"cleaning"}""", Vacuum("docked", 80, "Turbo")),
        )
    }

    @Test
    fun `explicit null fan speed clears it`() {
        assertEquals(
            Decoded.State(Vacuum("docked", 100, null)),
            VacuumMapper.decode(roborock, "roborock/ha/state", """{"state":"docked","battery_level":100,"fan_speed":null}""", Vacuum("docked", 100, "Turbo")),
        )
    }

    @Test
    fun `odd values`() {
        assertEquals(
            Decoded.State(Vacuum("docked", 100, null)),
            VacuumMapper.decode(roborock, "roborock/ha/state", """{"state":"unknown","battery_level":99.6}""", Vacuum("docked", 50, null)),
        )
        assertIs<Decoded.Error>(VacuumMapper.decode(roborock, "roborock/ha/state", "docked", Unknown))
        assertEquals(Decoded.Ignored, VacuumMapper.decode(roborock, "other", "{}", Unknown))
    }

    @Test
    fun `actions publish plain payloads`() {
        assertEquals(publishes("roborock/ha/command", "start"), VacuumMapper.encode(roborock, Command.VacuumAction("start"), Unknown))
        assertEquals(publishes("roborock/ha/command", "return_to_base"), VacuumMapper.encode(roborock, Command.VacuumAction("return_to_base"), Unknown))
        assertEquals(publishes("roborock/ha/command", "locate"), VacuumMapper.encode(roborock, Command.VacuumAction("locate"), Unknown))
        assertEquals(publishes("roborock/ha/command", "return_to_base"), VacuumMapper.encode(roborock, Command.TurnOff, Unknown))
        assertIs<Encoded.NotSupported>(VacuumMapper.encode(roborock, Command.VacuumAction("clean_spot"), Unknown))
    }

    @Test
    fun `payload overrides and unsupported actions`() {
        val custom = bindings(
            "vacuum",
            """{"state_topic":"s","command_topic":"c","payload_return_to_base":"dock","supported_features":["start","return_home"]}""",
        )
        assertEquals(publishes("c", "dock"), VacuumMapper.encode(custom, Command.VacuumAction("return_to_base"), Unknown))
        assertIs<Encoded.NotSupported>(VacuumMapper.encode(custom, Command.VacuumAction("pause"), Unknown))
        val noCommandTopic = bindings("vacuum", """{"state_topic":"s"}""")
        assertIs<Encoded.NotSupported>(VacuumMapper.encode(noCommandTopic, Command.VacuumAction("start"), Unknown))
    }

    @Test
    fun `fan speed`() {
        assertEquals(publishes("roborock/ha/fan_speed/set", "Silent"), VacuumMapper.encode(roborock, Command.SetFanSpeed("Silent"), Unknown))
        assertIs<Encoded.NotSupported>(VacuumMapper.encode(roborock, Command.SetFanSpeed("Max"), Unknown))
    }

    @Test
    fun `prediction and topics`() {
        val docked = Vacuum("docked", 100, "Turbo")
        assertEquals(Vacuum("cleaning", 100, "Turbo"), VacuumMapper.predict(Command.VacuumAction("start"), docked))
        assertEquals(Vacuum("paused", 100, "Turbo"), VacuumMapper.predict(Command.VacuumAction("pause"), docked))
        assertEquals(Vacuum("idle", 100, "Turbo"), VacuumMapper.predict(Command.VacuumAction("stop"), docked))
        assertEquals(Vacuum("returning", 100, "Turbo"), VacuumMapper.predict(Command.VacuumAction("return_to_base"), docked))
        assertNull(VacuumMapper.predict(Command.VacuumAction("locate"), docked))
        assertEquals(Vacuum("docked", 100, "Silent"), VacuumMapper.predict(Command.SetFanSpeed("Silent"), docked))
        assertEquals(setOf("roborock/ha/state"), VacuumMapper.stateTopics(roborock))
        assertEquals(setOf("roborock/ha/state"), VacuumMapper.feedbackTopics(roborock, Command.VacuumAction("start")))
        assertEquals(emptySet(), VacuumMapper.feedbackTopics(roborock, Command.VacuumAction("locate")))
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :core:state:test --tests '*VacuumMapperTest*'`
Expected: FAIL (decode returns Ignored, encode NotSupported "read-only entity").

- [ ] **Step 3: Implement** — in `VacuumMapper.kt` replace the `decode` stub and add the rest:

```kotlin
    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded {
        if (topic != b.str("state_topic")) return Decoded.Ignored
        val json = try {
            Json.parseToJsonElement(payload) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return Decoded.Error("vacuum state is not a JSON object")
        val prev = previous as? StateValue.Vacuum ?: StateValue.Vacuum(null, null, null)
        val state = (json["state"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in STATES } ?: prev.state
        val battery = (json["battery_level"] as? JsonPrimitive)?.doubleOrNull?.roundToInt()?.coerceIn(0, 100) ?: prev.battery
        // An absent key keeps the previous speed; an explicit null means "no known preset".
        val fanSpeed = if ("fan_speed" in json) (json["fan_speed"] as? JsonPrimitive)?.contentOrNull else prev.fanSpeed
        return Decoded.State(StateValue.Vacuum(state, battery, fanSpeed))
    }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded = when (command) {
        is Command.VacuumAction -> action(b, command.action)
        is Command.SetFanSpeed ->
            if (command.speed !in fanSpeeds(b)) Encoded.NotSupported("fan speed '${command.speed}' not supported")
            else publish(b, "set_fan_speed_topic", null, command.speed)
        Command.TurnOff -> action(b, "return_to_base")
        else -> Encoded.NotSupported("vacuum cannot $command")
    }

    private fun action(b: MqttBindings, action: String): Encoded {
        if (action !in actions(b)) return Encoded.NotSupported("vacuum action '$action' not supported")
        val topic = b.str("command_topic") ?: return Encoded.NotSupported("no command_topic configured")
        return Encoded.Publish(listOf(outgoing(b, topic, b.str("payload_$action", action))))
    }

    override fun feedbackTopics(b: MqttBindings, command: Command): Set<String> =
        if (command == Command.VacuumAction("locate")) emptySet() else stateTopics(b)

    override fun predict(command: Command, previous: StateValue): StateValue? {
        val prev = previous as? StateValue.Vacuum ?: StateValue.Vacuum(null, null, null)
        return when (command) {
            is Command.VacuumAction -> PREDICTED[command.action]?.let { prev.copy(state = it) }
            is Command.SetFanSpeed -> prev.copy(fanSpeed = command.speed)
            Command.TurnOff -> prev.copy(state = "returning")
            else -> null
        }
    }

    private val PREDICTED = mapOf("start" to "cleaning", "pause" to "paused", "stop" to "idle", "return_to_base" to "returning")
```

Imports to add: `com.gekkotron.oikos.model.Command`, `kotlinx.serialization.SerializationException`, `kotlinx.serialization.json.Json`, `kotlinx.serialization.json.JsonObject`, `kotlinx.serialization.json.JsonPrimitive`, `kotlinx.serialization.json.contentOrNull`, `kotlinx.serialization.json.doubleOrNull`, `kotlin.math.roundToInt`. Move `PREDICTED` next to the other constants at the top of the object (a `private val` declared after its first use in an `object` is still initialised before any method call, but keep constants together for readability).

- [ ] **Step 4: Run tests**

Run: `./gradlew :core:state:test`
Expected: PASS (all core:state tests).

- [ ] **Step 5: Commit**

```bash
git add core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/VacuumMapper.kt core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/VacuumMapperTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(vacuum): HA vacuum state schema mapper" -m $'Decodes the JSON state, publishes actions and fan speed, predicts the next state.\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\nClaude-Session: https://claude.ai/code/session_01F6NhNhvBxcNwJmCw7ySjfH'
```

---

### Task 5: Vacuum card, icon, control sheet, demo

**Files:**
- Modify: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/StaticIcon.kt` (new glyph)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindIcon.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt` (`CardValue.Vacuum`)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/CardFormatter.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CardText.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/ControlSheet.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`, `feature/home/src/main/res/values-fr/strings.xml`
- Modify: `core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoHouse.kt`
- Test: the existing CardFormatter test file in `feature/home/src/test/...` (find it with `grep -rl "CardFormatter" feature/home/src/test`; create `CardFormatterVacuumTest.kt` next to it if none exists)

**Interfaces:**
- Consumes: Task 3 types; `Capabilities.vacuumActions`, `Capabilities.fanSpeeds`, `StateValue.Vacuum`, `Command.VacuumAction`, `Command.SetFanSpeed`.
- Produces: `OikosGlyph.VACUUM`, `CardValue.Vacuum(val state: String?, val battery: Int?)`.

- [ ] **Step 1: Write the failing formatter test**

```kotlin
    @Test
    fun `vacuum shows state and battery`() {
        val entity = testEntity("v", Kind.VACUUM)   // use the file's existing entity helper; build an Entity with kind VACUUM if there is none
        assertEquals(CardValue.Vacuum("docked", 100), CardFormatter.format(entity, StateValue.Vacuum("docked", 100, "Turbo"), Locale.ENGLISH))
        assertEquals(CardValue.Vacuum(null, null), CardFormatter.format(entity, StateValue.Vacuum(null, null, null), Locale.ENGLISH))
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*CardFormatter*'`
Expected: compilation FAIL (`Unresolved reference: Vacuum` in CardValue).

- [ ] **Step 3: Card value + formatter**

`HomeModels.kt`, in `CardValue` after `Alarm`:

```kotlin
    data class Vacuum(val state: String?, val battery: Int?) : CardValue
```

`CardFormatter.format`: replace the Task 3 placeholder with

```kotlin
        is StateValue.Vacuum -> CardValue.Vacuum(value.state, value.battery)
```

- [ ] **Step 4: Strings** — add to `values/strings.xml`:

```xml
    <string name="vacuum_state_cleaning">Cleaning</string>
    <string name="vacuum_state_docked">Docked</string>
    <string name="vacuum_state_paused">Paused</string>
    <string name="vacuum_state_idle">Idle</string>
    <string name="vacuum_state_returning">Returning to dock</string>
    <string name="vacuum_state_error">Error</string>
    <string name="vacuum_start">Start</string>
    <string name="vacuum_pause">Pause</string>
    <string name="vacuum_stop">Stop</string>
    <string name="vacuum_dock">Dock</string>
    <string name="vacuum_locate">Locate</string>
    <string name="sheet_suction">Suction</string>
```

and to `values-fr/strings.xml`:

```xml
    <string name="vacuum_state_cleaning">Nettoyage</string>
    <string name="vacuum_state_docked">Sur la base</string>
    <string name="vacuum_state_paused">En pause</string>
    <string name="vacuum_state_idle">À l\'arrêt</string>
    <string name="vacuum_state_returning">Retour à la base</string>
    <string name="vacuum_state_error">Erreur</string>
    <string name="vacuum_start">Démarrer</string>
    <string name="vacuum_pause">Pause</string>
    <string name="vacuum_stop">Arrêter</string>
    <string name="vacuum_dock">Retour base</string>
    <string name="vacuum_locate">Localiser</string>
    <string name="sheet_suction">Aspiration</string>
```

- [ ] **Step 5: Card text** — in `CardText.kt`, add a branch to `cardValueText` and two helpers:

```kotlin
    is CardValue.Vacuum ->
        listOfNotNull(value.state?.let { stringResource(vacuumStateLabel(it)) }, value.battery?.let { stringResource(R.string.value_percent, it) })
            .joinToString(" · ")
            .ifEmpty { stringResource(R.string.value_none) }
```

```kotlin
@StringRes
internal fun vacuumStateLabel(state: String): Int = when (state) {
    "cleaning" -> R.string.vacuum_state_cleaning
    "docked" -> R.string.vacuum_state_docked
    "paused" -> R.string.vacuum_state_paused
    "returning" -> R.string.vacuum_state_returning
    "error" -> R.string.vacuum_state_error
    else -> R.string.vacuum_state_idle
}

@StringRes
internal fun vacuumActionLabel(action: String): Int = when (action) {
    "pause" -> R.string.vacuum_pause
    "stop" -> R.string.vacuum_stop
    "return_to_base" -> R.string.vacuum_dock
    "locate" -> R.string.vacuum_locate
    else -> R.string.vacuum_start
}
```

(import `androidx.annotation.StringRes`.)

- [ ] **Step 6: Glyph + icon**

In `OikosGlyph` (StaticIcon.kt), after `DRAG_HANDLE` or next to the other device glyphs, add (robot seen from above: round body, bumper arc, centre button):

```kotlin
    VACUUM(listOf(area("M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0z"), line("M7.2 8.2a6.5 6.5 0 0 1 9.6 0M13.5 13a1.5 1.5 0 1 1-3 0 1.5 1.5 0 0 1 3 0z"))),
```

In `KindIcon.kt` replace the Task 3 placeholder with `Kind.VACUUM -> IconSpec.Static(OikosGlyph.VACUUM, on = (card.state as? StateValue.Vacuum)?.state == "cleaning")` (import `StateValue` if needed). Check the glyph in `OikosIconsPreview.kt` if it enumerates glyphs (add `VACUUM` there if it lists them by hand).

- [ ] **Step 7: Control sheet** — in `ControlSheet.kt`, add `Kind.VACUUM -> VacuumControls(card, send)` to the `when (card.kind)` and:

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VacuumControls(card: CardUi, onSend: (Command) -> Unit) {
    val vacuum = card.state as? StateValue.Vacuum
    val actions = card.capabilities.vacuumActions
    // Start turns into Pause while cleaning, when the vacuum can pause
    val primary = if (vacuum?.state == "cleaning" && "pause" in actions) "pause" else "start"
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (primary in actions) {
            Button(onClick = { onSend(Command.VacuumAction(primary)) }, enabled = card.available) { Text(stringResource(vacuumActionLabel(primary))) }
        }
        for (action in listOf("stop", "return_to_base", "locate")) {
            if (action in actions) {
                FilledTonalButton(onClick = { onSend(Command.VacuumAction(action)) }, enabled = card.available) {
                    Text(stringResource(vacuumActionLabel(action)))
                }
            }
        }
    }
    if (card.capabilities.fanSpeeds.isNotEmpty()) {
        Text(stringResource(R.string.sheet_suction))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (speed in card.capabilities.fanSpeeds) {
                FilterChip(selected = vacuum?.fanSpeed == speed, onClick = { onSend(Command.SetFanSpeed(speed)) }, label = { Text(speed) })
            }
        }
    }
}
```

The sheet already shows `cardValueText(card.value)` under the name, which is the state + battery line.

- [ ] **Step 8: Demo vacuum** — in `DemoHouse.kt`, call `vacuum("demo_living_vacuum", "Robot vacuum", "Living room", "demo/living/vacuum")` next to the `climate(...)` call, and add:

```kotlin
    private fun vacuum(id: String, device: String, area: String, topic: String) {
        val speeds = listOf("Silent", "Standard", "Medium", "Turbo", "Gentle", "Auto")
        val states = mapOf("start" to "cleaning", "pause" to "paused", "stop" to "idle", "return_to_base" to "returning")
        config("vacuum", id, device, null, area) {
            put("state_topic", topic)
            put("command_topic", "$topic/command")
            put("set_fan_speed_topic", "$topic/fan_speed/set")
            putJsonArray("fan_speed_list") { speeds.forEach { add(it) } }
            putJsonArray("supported_features") {
                listOf("start", "pause", "stop", "return_home", "locate", "fan_speed", "battery", "status").forEach { add(it) }
            }
        }
        doc(topic, obj("state" to "docked", "battery_level" to 100, "fan_speed" to "Standard"))
        echo("$topic/command", topic) { p -> states[p]?.let { obj("state" to it) } }
        echo("$topic/fan_speed/set", topic) { p -> if (p in speeds) obj("fan_speed" to p) else null }
    }
```

If a demo test asserts an entity count or a list of ids, update it for the new vacuum.

- [ ] **Step 9: Full verification**

Run: `./gradlew :core:state:test :core:source:test :core:demo:test :feature:home:testDebugUnitTest :feature:widget:testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add core/designsystem feature/home core/demo
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(vacuum): vacuum card and control sheet" -m $'State + battery on the card, Start/Pause/Stop/Dock/Locate and suction chips in the sheet, a vacuum glyph and a demo vacuum.\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\nClaude-Session: https://claude.ai/code/session_01F6NhNhvBxcNwJmCw7ySjfH'
```

---

### Task 6: Ship

- [ ] **Step 1:** Push Oikos: `git push origin master` (triggers the Firebase distribution build).
- [ ] **Step 2:** Tell the user the Geekom steps: in the Roborock2Mqtt `.env` set `HA_DISCOVERY_ENABLED=true`, then `git pull && docker compose up -d --build`.
- [ ] **Step 3:** After their redeploy, check over MQTT with the `oikos-setup.json` `local` credentials (never print them): a retained `homeassistant/vacuum/roborock_40313ca8b964/.../config`, a retained `roborock/ha/state` with `"state": "docked"`, a retained `roborock/alive 1`.
- [ ] **Step 4:** Device checklist for the user: vacuum card shows "Docked · 100%", Start → "Cleaning" and Pause appears, Dock → "Returning to dock", Locate beeps, a suction chip change is reflected after the next poll (≤ 30 s).
