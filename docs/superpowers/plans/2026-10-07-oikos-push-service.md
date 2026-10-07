# OikosPush Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build OikosPush, a Python 3.12 Docker service that turns MQTT producer payloads (v1 and legacy Mqtt2FCMPush format) into normalized FCM data messages for the Oikos Android app, and keeps a retained catch-up history on `oikos-push/history`.
**Architecture:** Flat Python modules mirroring Mqtt2FCMPush (`config`, `mqtt_handler`, `fcm_sender`, `notification_queue`, service, `main`) with the Firestore/admin code removed, plus three new modules: `normalize.py` (pure), `tokens.py` (installId -> token map fed by retained MQTT messages) and `history.py` (persistent catch-up buffer). The service class wires them together; MQTT (paho) and FCM (firebase_admin.messaging) are injected so every test runs with stubs and no network.
**Tech Stack:** Python 3.12, paho-mqtt (<2, same API as Mqtt2FCMPush), firebase-admin (FCM only), pytest, Docker Compose.
**Spec:** `docs/superpowers/specs/2026-10-07-oikos-push-alerts-design.md` (§2, §3)

Scope: spec §2, §3, the OikosPush tests of §9 and §10 step 1 only. The Android app is a separate plan.

## Global Constraints

- Topic prefix default `oikos-push` (env `TOPIC_PREFIX`); topics: `oikos-push/notification/<source>` (producer -> OikosPush, not retained), `oikos-push/tokens/<installId>` (Oikos -> OikosPush, retained, empty payload = unregister), `oikos-push/responses` (Oikos -> producer; the service never subscribes to it or publishes on it), `oikos-push/history` (OikosPush -> Oikos, retained), `oikos-push/heartbeat` (not retained).
- Channels: `alarm`, `security`, `camera`, `energy`, `climate`, `system`, `misc` (unknown or missing -> `misc`). Severities: `info | warning | critical` (default `info`).
- v1 payload: required `v` (must be integer 1), `id`, `title`; cancel = `{"v":1,"id":"...","cancel":true}`; same `id` replaces the earlier alert.
- Legacy mapping: `channelId` `alarme`/`nvr`/`pv`/`surveillance`/`Supervisor`/other -> `alarm`/`camera`/`energy`/`climate`/`system`/`misc`; `data.id` (number or string) -> string, missing -> `<source>-<sha1(title+body)[:8]>`; `actions` JSON string `[{"btn","action"}]` -> `[{label: btn, id: action}]`; empty or missing `title` -> `cancel: true`; `priority: "high"` -> `severity: "warning"`, otherwise `info`; other `data.*` keys dropped.
- FCM data map: all values strings; keys `v="1"`, `id`, `channel`, `severity`, `title`, `body`, `image_url`, `video_url`, `entity_id`, `actions` (JSON array string, `"[]"` when none), `silent` (`"true"/"false"`), `cancel`, `sent_at` (unix seconds), `source`; absent optional fields left out; map must stay under the 4 KB FCM limit (4096 bytes), otherwise `body` is truncated with `…`.
- Android priority is `high` when severity is `warning` or `critical`, or channel is `alarm`, `security` or `camera`; `normal` otherwise. TTL = payload `ttl` (default 43200 s); a cancel is sent with TTL 300 s.
- Failures: `UNREGISTERED` / `INVALID_ARGUMENT` -> publish an empty retained payload to that install's token topic; any other failure -> queue for those tokens, retried every `RETRY_INTERVAL=60` s up to `MAX_RETRIES=10` attempts or TTL expiry. No tokens -> history only, nothing queued.
- History: newest first; same `id` replaced in place and moved to front; cancel sets `cancel: true` on the stored alert or is stored alone; trimmed to `HISTORY_SIZE=100` entries and `HISTORY_DAYS=7` days by `sent_at`; persisted to `data/history.json`; whole list republished retained to `oikos-push/history` after every change, typed JSON values.
- Config env: `MQTT_BROKER`, `MQTT_PORT`, `MQTT_USERNAME`, `MQTT_PASSWORD`, `TOPIC_PREFIX=oikos-push`, `FIREBASE_CREDENTIALS`, `RETRY_INTERVAL=60`, `MAX_RETRIES=10`, `HISTORY_SIZE=100`, `HISTORY_DAYS=7`, `HEARTBEAT_INTERVAL=60`.
- Python 3.12; MIT licence; author `Gekkotron` (email `60887050+Gekkotron@users.noreply.github.com` when an email field is required). No real name anywhere in the repo.
- Never read, copy or commit `credential.json`; it is git-ignored along with `data/` and `venv/`.
- Commit identity (every commit): `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "<msg>" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"`.
- Working directory for every command: `/Users/julienhuguel/Documents/SourcesPerso/OikosPush` (written `$REPO` below). Run tests with `venv/bin/pytest`.

## Review Focus

Failure modes the spec implies but no obvious test exercises, most likely first. Each has a dedicated test in its owning task.

1. **Two installs share one FCM token** (reinstall, restored phone): the token is sent to once, and when FCM reports it invalid, BOTH install topics must be cleared, otherwise the stale one is re-added on the next connect. Expected: one send call with the token listed once; an empty retained payload published to each install's token topic; map empty. -> Task 6, test `test_invalid_token_shared_by_two_installs_clears_both_topics`.
2. **Partial multicast failure** (one token invalid, one transient, one fine): only the transient token is queued, never the one that already succeeded (no duplicate notification on retry), and a retry must skip tokens that were unregistered in the meantime. Expected: queue item holds only `["tokC"]`; retry sends to `["tokC"]` only; after unregistering it the retry sends nothing and the item is dropped. -> Task 6, tests `test_mixed_failures_queue_only_transient_tokens` and `test_retry_skips_tokens_no_longer_registered`.
3. **Multibyte body over the 4 KB limit**: the limit is on UTF-8 bytes of keys plus values, and truncation must not cut a character in half. Expected: total <= 4096 bytes, valid UTF-8, body ends with `…`. -> Task 2, test `test_truncation_multibyte_body_stays_valid_utf8_and_under_limit`.
4. **Garbage on the notification topic** (invalid UTF-8, non-JSON, JSON array/number/null, `v: 2`): dropped with a log line, no exception escapes the MQTT callback, no history entry, no send. Expected: history length 0, sender never called. -> Task 6, test `test_garbage_payloads_are_dropped_without_side_effects` (pure part: Task 2, test `test_non_object_payloads_are_rejected`).
5. **Cancel / re-alert ordering in history**: a cancel resolves the stored alert in place (position unchanged), a cancel for an unknown id is stored alone, and a later alert with the same `id` replaces the entry and clears the resolved flag. Expected: order unchanged after cancel; no `cancel` key after the re-alert; re-alert moved to front. -> Task 4, test `test_cancel_keeps_position_and_reposting_clears_resolved`.

---

## File Structure

All paths relative to `$REPO` = `/Users/julienhuguel/Documents/SourcesPerso/OikosPush`.

| File | Responsibility |
|---|---|
| `config.py` | Env-driven settings, `as_dict()` for the service |
| `normalize.py` | Pure: `Alert`, `Rejected`, `normalize(topic, payload, now=None)`, `to_fcm_data(alert)` (v1 + legacy, 4 KB truncation) |
| `tokens.py` | `TokenRegistry`: installId -> `Registration`, unique token list, reverse lookup |
| `history.py` | `History` catch-up buffer (caps, persistence) and `to_history_entry(alert)` (typed JSON) |
| `fcm_sender.py` | `android_priority`, `ttl_seconds`, `classify_failure`, `SendResult`, `FCMSender.send_data`, `init_firebase` |
| `notification_queue.py` | Persistent retry queue (port of Mqtt2FCMPush, injectable clock) |
| `mqtt_handler.py` | paho wrapper: connect/backoff, subscribe on connect, publish, heartbeat |
| `oikos_push_service.py` | `OikosPush` wiring: routing by topic, dispatch, cleanup, retry, history republish |
| `main.py` | Entry point |
| `Dockerfile`, `docker-compose.yml`, `entrypoint.sh`, `.dockerignore`, `.env.example` | Deployment |
| `README.md` | Contract documentation (§2) and deployment |
| `LICENSE`, `.gitignore`, `requirements.txt`, `requirements-dev.txt`, `pyproject.toml` | Repo plumbing |
| `tests/__init__.py`, `tests/test_*.py`, `tests/fake_fcm.py`, `tests/fake_mqtt.py` | pytest suite and stubs |

---

### Task 1: Repo scaffold and config

**Files:**
- Create: `$REPO/.gitignore`, `$REPO/LICENSE`, `$REPO/requirements.txt`, `$REPO/requirements-dev.txt`, `$REPO/pyproject.toml`, `$REPO/tests/__init__.py`, `$REPO/config.py`
- Test: `$REPO/tests/test_config.py`

**Interfaces:**
Consumes: nothing.
Produces:
- `config` module constants: `MQTT_BROKER: str`, `MQTT_PORT: int`, `MQTT_USERNAME: str | None`, `MQTT_PASSWORD: str | None`, `TOPIC_PREFIX: str` (no leading/trailing `/`), `FIREBASE_CREDENTIALS: str`, `RETRY_INTERVAL: int`, `MAX_RETRIES: int`, `HISTORY_SIZE: int`, `HISTORY_DAYS: int`, `HEARTBEAT_ENABLED: bool`, `HEARTBEAT_INTERVAL: int`, `AUTO_RECONNECT: bool`, `RECONNECT_DELAY_MIN: int`, `RECONNECT_DELAY_MAX: int`, `DATA_DIR: str`.
- `config.as_dict() -> dict` with keys `mqtt_broker, mqtt_port, mqtt_username, mqtt_password, topic_prefix, firebase_credentials, retry_interval, max_retries, history_size, history_days, heartbeat_enabled, heartbeat_interval, auto_reconnect, reconnect_delay_min, reconnect_delay_max, data_dir`.
- Every `os.getenv("NAME"...)` name in `config.py` is an env var documented later in the README (Task 7 checks this).

- [ ] **Step 1: Create the repo, venv, plumbing files and the failing test**

```bash
mkdir -p /Users/julienhuguel/Documents/SourcesPerso/OikosPush/tests
cd /Users/julienhuguel/Documents/SourcesPerso/OikosPush
git init -b main
# Python 3.12 is not installed on this Mac by default. Prefer: brew install python@3.12
# If you cannot install it, `python3 -m venv venv` also works (the code is 3.12-compatible; Docker pins 3.12).
python3.12 -m venv venv || python3 -m venv venv
```

Create `.gitignore`:

```
# Secrets and runtime data
credential.json
data/
.env

# Python
venv/
.venv/
__pycache__/
*.py[cod]
.pytest_cache/

# IDE / OS
.vscode/
.idea/
.DS_Store
```

Create `LICENSE`:

```
MIT License

Copyright (c) 2026 Gekkotron

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Create `requirements.txt`:

```
paho-mqtt>=1.6.1,<2
firebase-admin>=6.2.0
```

Create `requirements-dev.txt`:

```
-r requirements.txt
pytest>=8.0
```

Create `pyproject.toml`:

```toml
# Metadata and pytest config only: OikosPush is run as a service, not installed as a package.
[project]
name = "oikos-push"
version = "0.1.0"
description = "MQTT to FCM push bridge for the Oikos app"
requires-python = ">=3.12"
license = { text = "MIT" }
authors = [{ name = "Gekkotron", email = "60887050+Gekkotron@users.noreply.github.com" }]

[tool.pytest.ini_options]
pythonpath = ["."]
testpaths = ["tests"]
```

Create empty `tests/__init__.py`, then install and write `tests/test_config.py`:

```bash
venv/bin/pip install -r requirements-dev.txt
```

```python
import importlib

import pytest

import config

ENV_KEYS = [
    "MQTT_BROKER", "MQTT_PORT", "MQTT_USERNAME", "MQTT_PASSWORD", "TOPIC_PREFIX",
    "FIREBASE_CREDENTIALS", "RETRY_INTERVAL", "MAX_RETRIES", "HISTORY_SIZE",
    "HISTORY_DAYS", "HEARTBEAT_ENABLED", "HEARTBEAT_INTERVAL", "AUTO_RECONNECT",
    "RECONNECT_DELAY_MIN", "RECONNECT_DELAY_MAX", "DATA_DIR",
]


def load(monkeypatch, **env):
    for key in ENV_KEYS:
        monkeypatch.delenv(key, raising=False)
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    return importlib.reload(config)


def test_defaults_match_the_spec(monkeypatch):
    c = load(monkeypatch)
    assert c.TOPIC_PREFIX == "oikos-push"
    assert c.RETRY_INTERVAL == 60
    assert c.MAX_RETRIES == 10
    assert c.HISTORY_SIZE == 100
    assert c.HISTORY_DAYS == 7
    assert c.HEARTBEAT_INTERVAL == 60
    assert c.FIREBASE_CREDENTIALS == "credential.json"
    assert c.MQTT_PORT == 1883
    assert c.MQTT_USERNAME is None
    assert c.MQTT_PASSWORD is None
    assert c.DATA_DIR == "data"


def test_env_overrides_and_prefix_is_stripped(monkeypatch):
    c = load(
        monkeypatch,
        MQTT_BROKER="mosquitto", MQTT_PORT="8883", MQTT_USERNAME="u", MQTT_PASSWORD="p",
        TOPIC_PREFIX="/home-push/", HISTORY_SIZE="5", HISTORY_DAYS="2",
        RETRY_INTERVAL="30", MAX_RETRIES="3", HEARTBEAT_ENABLED="false",
    )
    assert c.MQTT_BROKER == "mosquitto"
    assert c.MQTT_PORT == 8883
    assert (c.MQTT_USERNAME, c.MQTT_PASSWORD) == ("u", "p")
    assert c.TOPIC_PREFIX == "home-push"
    assert (c.HISTORY_SIZE, c.HISTORY_DAYS) == (5, 2)
    assert (c.RETRY_INTERVAL, c.MAX_RETRIES) == (30, 3)
    assert c.HEARTBEAT_ENABLED is False


def test_as_dict_exposes_service_keys(monkeypatch):
    c = load(monkeypatch, MQTT_BROKER="b")
    d = c.as_dict()
    assert d["mqtt_broker"] == "b"
    assert d["topic_prefix"] == "oikos-push"
    assert d["history_size"] == 100
    assert d["history_days"] == 7
    assert d["retry_interval"] == 60
    assert d["max_retries"] == 10
    assert set(d) == {
        "mqtt_broker", "mqtt_port", "mqtt_username", "mqtt_password", "topic_prefix",
        "firebase_credentials", "retry_interval", "max_retries", "history_size",
        "history_days", "heartbeat_enabled", "heartbeat_interval", "auto_reconnect",
        "reconnect_delay_min", "reconnect_delay_max", "data_dir",
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd $REPO && venv/bin/pytest tests/test_config.py -v`
Expected: collection error `ModuleNotFoundError: No module named 'config'`.

- [ ] **Step 3: Write minimal implementation**

Create `config.py`:

```python
"""Settings for OikosPush, read from environment variables."""
import os


def _bool(name: str, default: str) -> bool:
    return os.getenv(name, default).strip().lower() in ("true", "1", "yes")


# MQTT
MQTT_BROKER = os.getenv("MQTT_BROKER", "localhost")
MQTT_PORT = int(os.getenv("MQTT_PORT", "1883"))
MQTT_USERNAME = os.getenv("MQTT_USERNAME") or None
MQTT_PASSWORD = os.getenv("MQTT_PASSWORD") or None
TOPIC_PREFIX = os.getenv("TOPIC_PREFIX", "oikos-push").strip("/")

# Firebase (service account of the Oikos project)
FIREBASE_CREDENTIALS = os.getenv("FIREBASE_CREDENTIALS", "credential.json")

# Retry queue
RETRY_INTERVAL = int(os.getenv("RETRY_INTERVAL", "60"))
MAX_RETRIES = int(os.getenv("MAX_RETRIES", "10"))

# History (catch-up buffer)
HISTORY_SIZE = int(os.getenv("HISTORY_SIZE", "100"))
HISTORY_DAYS = int(os.getenv("HISTORY_DAYS", "7"))

# Heartbeat
HEARTBEAT_ENABLED = _bool("HEARTBEAT_ENABLED", "True")
HEARTBEAT_INTERVAL = int(os.getenv("HEARTBEAT_INTERVAL", "60"))

# Reconnect
AUTO_RECONNECT = _bool("AUTO_RECONNECT", "True")
RECONNECT_DELAY_MIN = int(os.getenv("RECONNECT_DELAY_MIN", "1"))
RECONNECT_DELAY_MAX = int(os.getenv("RECONNECT_DELAY_MAX", "60"))

# Where history.json and notification_queue.json live
DATA_DIR = os.getenv("DATA_DIR", "data")


def as_dict() -> dict:
    """Settings as the dict the service expects."""
    return {
        "mqtt_broker": MQTT_BROKER,
        "mqtt_port": MQTT_PORT,
        "mqtt_username": MQTT_USERNAME,
        "mqtt_password": MQTT_PASSWORD,
        "topic_prefix": TOPIC_PREFIX,
        "firebase_credentials": FIREBASE_CREDENTIALS,
        "retry_interval": RETRY_INTERVAL,
        "max_retries": MAX_RETRIES,
        "history_size": HISTORY_SIZE,
        "history_days": HISTORY_DAYS,
        "heartbeat_enabled": HEARTBEAT_ENABLED,
        "heartbeat_interval": HEARTBEAT_INTERVAL,
        "auto_reconnect": AUTO_RECONNECT,
        "reconnect_delay_min": RECONNECT_DELAY_MIN,
        "reconnect_delay_max": RECONNECT_DELAY_MAX,
        "data_dir": DATA_DIR,
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd $REPO && venv/bin/pytest tests/test_config.py -v`
Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/OikosPush
git add .gitignore LICENSE requirements.txt requirements-dev.txt pyproject.toml tests/__init__.py config.py tests/test_config.py
git status --short   # credential.json, venv/ and data/ must NOT appear
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "chore: scaffold OikosPush repo and config" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `normalize.py` (pure normalization and FCM data map)

**Files:**
- Create: `$REPO/normalize.py`
- Test: `$REPO/tests/test_normalize.py`

**Interfaces:**
Consumes: nothing (stdlib only).
Produces:
- Constants: `CHANNELS: tuple[str, ...]`, `SEVERITIES: tuple[str, ...]`, `DEFAULT_TTL = 43200`, `CANCEL_TTL = 300`, `FCM_MAX_BYTES = 4096`, `LEGACY_CHANNELS: dict[str, str]`.
- `@dataclass(frozen=True) class Alert`: `id: str`, `channel: str = "misc"`, `severity: str = "info"`, `title: str = ""`, `body: str = ""`, `image_url: str | None = None`, `video_url: str | None = None`, `entity_id: str | None = None`, `actions: list[dict[str, str]]` (items `{"id","label"}`, default `[]`), `silent: bool = False`, `cancel: bool = False`, `ttl: int = 43200`, `sent_at: int = 0`, `source: str = ""`.
- `@dataclass(frozen=True) class Rejected`: `reason: str`.
- `normalize(topic: str, payload: object, now: float | None = None) -> Alert | Rejected` (`source` = last segment of `topic`; `sent_at = int(now or time.time())`; payload is the already-parsed JSON value).
- `to_fcm_data(alert: Alert) -> dict[str, str]`.

- [ ] **Step 1: Write the failing test**

Create `tests/test_normalize.py`:

```python
import hashlib
import json

import pytest

from normalize import (
    CANCEL_TTL, CHANNELS, DEFAULT_TTL, FCM_MAX_BYTES, Alert, Rejected, normalize, to_fcm_data,
)

NOW = 1_700_000_000
TOPIC = "oikos-push/notification/frigate"


def fcm_size(data):
    return sum(len(k.encode("utf-8")) + len(v.encode("utf-8")) for k, v in data.items())


# ---------- v1 ----------

def test_v1_every_field():
    payload = {
        "v": 1, "id": "frigate-front-1696", "channel": "camera", "severity": "critical",
        "title": "Front door", "body": "Person detected",
        "image_url": "http://192.168.1.20:5000/api/events/1696/snapshot.jpg",
        "video_url": "http://192.168.1.20:5000/api/events/1696/clip.mp4",
        "entity_id": "binary_sensor.front_door",
        "actions": [{"id": "disarm", "label": "Disarm"}],
        "silent": True, "ttl": 600,
    }
    assert normalize(TOPIC, payload, now=NOW) == Alert(
        id="frigate-front-1696", channel="camera", severity="critical",
        title="Front door", body="Person detected",
        image_url="http://192.168.1.20:5000/api/events/1696/snapshot.jpg",
        video_url="http://192.168.1.20:5000/api/events/1696/clip.mp4",
        entity_id="binary_sensor.front_door",
        actions=[{"id": "disarm", "label": "Disarm"}],
        silent=True, cancel=False, ttl=600, sent_at=NOW, source="frigate",
    )


def test_v1_defaults():
    a = normalize(TOPIC, {"v": 1, "id": "x", "title": "T"}, now=NOW)
    assert (a.channel, a.severity, a.body, a.actions, a.silent, a.ttl) == (
        "misc", "info", "", [], False, DEFAULT_TTL)
    assert (a.image_url, a.video_url, a.entity_id) == (None, None, None)


@pytest.mark.parametrize("channel", ["nope", None, 7, ["alarm"]])
def test_v1_unknown_channel_becomes_misc(channel):
    assert normalize(TOPIC, {"v": 1, "id": "x", "title": "T", "channel": channel}).channel == "misc"


@pytest.mark.parametrize("channel", CHANNELS)
def test_v1_all_seven_channels_accepted(channel):
    assert normalize(TOPIC, {"v": 1, "id": "x", "title": "T", "channel": channel}).channel == channel


@pytest.mark.parametrize("severity", ["urgent", None, 3])
def test_v1_unknown_severity_becomes_info(severity):
    assert normalize(TOPIC, {"v": 1, "id": "x", "title": "T", "severity": severity}).severity == "info"


@pytest.mark.parametrize("ttl", [0, -5, "600", None, True])
def test_v1_bad_ttl_uses_default(ttl):
    assert normalize(TOPIC, {"v": 1, "id": "x", "title": "T", "ttl": ttl}).ttl == DEFAULT_TTL


def test_v1_numeric_id_becomes_string():
    assert normalize(TOPIC, {"v": 1, "id": 1696, "title": "T"}).id == "1696"


def test_v1_invalid_actions_entries_are_dropped():
    payload = {"v": 1, "id": "x", "title": "T", "actions": [
        {"id": "a", "label": "A"}, {"id": "b"}, {"label": "C"}, "junk", {"id": "", "label": "D"}]}
    assert normalize(TOPIC, payload).actions == [{"id": "a", "label": "A"}]


def test_v1_cancel_needs_only_v_and_id():
    a = normalize(TOPIC, {"v": 1, "id": "frigate-front-1696", "cancel": True}, now=NOW)
    assert a == Alert(id="frigate-front-1696", cancel=True, sent_at=NOW, source="frigate")


def test_v1_cancel_drops_other_content():
    a = normalize(TOPIC, {"v": 1, "id": "x", "cancel": True, "title": "T", "body": "B",
                          "image_url": "http://x/y.jpg"})
    assert (a.title, a.body, a.image_url, a.cancel) == ("", "", None, True)


# ---------- rejections ----------

@pytest.mark.parametrize("payload", [
    {"v": 1, "title": "T"},                    # no id
    {"v": 1, "id": "", "title": "T"},          # empty id
    {"v": 1, "id": "x"},                       # no title, no cancel
    {"v": 1, "id": "x", "title": "   "},       # blank title, no cancel
    {"v": 1, "cancel": True},                  # cancel without id
    {"v": 2, "id": "x", "title": "T"},         # unsupported version
    {"v": "1", "id": "x", "title": "T"},
    {"v": True, "id": "x", "title": "T"},
    {"v": None, "id": "x", "title": "T"},
])
def test_rejected_v1_payloads(payload):
    assert isinstance(normalize(TOPIC, payload), Rejected)


@pytest.mark.parametrize("payload", [[1, 2], "text", 42, None, True])
def test_non_object_payloads_are_rejected(payload):
    result = normalize(TOPIC, payload)
    assert isinstance(result, Rejected)
    assert result.reason


# ---------- legacy ----------

@pytest.mark.parametrize("channel_id, expected", [
    ("alarme", "alarm"), ("nvr", "camera"), ("pv", "energy"), ("surveillance", "climate"),
    ("Supervisor", "system"), ("anything-else", "misc"), (None, "misc"), (12, "misc"),
])
def test_legacy_channel_mapping(channel_id, expected):
    payload = {"title": "T", "body": "B", "data": {"id": "1", "channelId": channel_id}}
    assert normalize(TOPIC, payload).channel == expected


def test_legacy_full_example():
    payload = {
        "title": "Alarme", "body": "Intrusion salon", "priority": "high", "ttl": 900,
        "data": {
            "id": 1696, "channelId": "alarme", "image_url": "http://cam/snap.jpg",
            "video_url": "http://cam/clip.mp4", "silent": "true",
            "actions": '[{"btn":"Désarmer","action":"disarm"},{"btn":"Ignorer","action":"ignore"}]',
            "something_else": "dropped", "color": "red",
        },
    }
    assert normalize("oikos-push/notification/jeedom", payload, now=NOW) == Alert(
        id="1696", channel="alarm", severity="warning", title="Alarme", body="Intrusion salon",
        image_url="http://cam/snap.jpg", video_url="http://cam/clip.mp4", entity_id=None,
        actions=[{"id": "disarm", "label": "Désarmer"}, {"id": "ignore", "label": "Ignorer"}],
        silent=True, cancel=False, ttl=900, sent_at=NOW, source="jeedom",
    )


@pytest.mark.parametrize("raw_id, expected", [("abc", "abc"), (42, "42"), (42.0, "42")])
def test_legacy_id_number_or_string(raw_id, expected):
    assert normalize(TOPIC, {"title": "T", "data": {"id": raw_id}}).id == expected


@pytest.mark.parametrize("data", [{}, {"id": ""}, {"id": None}, None, "junk"])
def test_legacy_missing_id_falls_back_to_source_and_sha1(data):
    payload = {"title": "Door open", "body": "Salon", "data": data}
    expected = "frigate-" + hashlib.sha1("Door openSalon".encode("utf-8")).hexdigest()[:8]
    assert normalize(TOPIC, payload).id == expected


def test_legacy_same_content_gives_same_fallback_id():
    p = {"title": "Door open", "body": "Salon"}
    assert normalize(TOPIC, p).id == normalize(TOPIC, dict(p)).id


@pytest.mark.parametrize("title", ["", None, "   "])
def test_legacy_empty_or_missing_title_is_cancel(title):
    payload = {"body": "x", "data": {"id": "77"}}
    if title is not None:
        payload["title"] = title
    a = normalize(TOPIC, payload)
    assert (a.id, a.cancel, a.title, a.body) == ("77", True, "", "")


@pytest.mark.parametrize("priority, severity", [("high", "warning"), ("normal", "info"), (None, "info")])
def test_legacy_priority_maps_to_severity(priority, severity):
    payload = {"title": "T"}
    if priority is not None:
        payload["priority"] = priority
    assert normalize(TOPIC, payload).severity == severity


def test_legacy_bad_actions_json_gives_no_actions():
    payload = {"title": "T", "data": {"id": "1", "actions": "not json"}}
    assert normalize(TOPIC, payload).actions == []


def test_legacy_actions_may_already_be_a_list():
    payload = {"title": "T", "data": {"id": "1", "actions": [{"btn": "Go", "action": "go"}, {"btn": "x"}]}}
    assert normalize(TOPIC, payload).actions == [{"id": "go", "label": "Go"}]


def test_legacy_defaults():
    a = normalize(TOPIC, {"title": "T"})
    assert (a.ttl, a.silent, a.entity_id, a.image_url) == (DEFAULT_TTL, False, None, None)


# ---------- to_fcm_data ----------

def test_to_fcm_data_full_map_is_all_strings():
    alert = Alert(
        id="frigate-front-1696", channel="camera", severity="critical", title="Front door",
        body="Person detected", image_url="http://i/s.jpg", video_url="http://i/c.mp4",
        entity_id="binary_sensor.front_door", actions=[{"id": "disarm", "label": "Disarm"}],
        silent=False, sent_at=NOW, source="frigate",
    )
    assert to_fcm_data(alert) == {
        "v": "1", "id": "frigate-front-1696", "channel": "camera", "severity": "critical",
        "title": "Front door", "body": "Person detected",
        "image_url": "http://i/s.jpg", "video_url": "http://i/c.mp4",
        "entity_id": "binary_sensor.front_door",
        "actions": '[{"id":"disarm","label":"Disarm"}]',
        "silent": "false", "sent_at": str(NOW), "source": "frigate",
    }


def test_to_fcm_data_leaves_out_absent_optionals_and_has_empty_actions():
    data = to_fcm_data(Alert(id="x", title="T", sent_at=NOW, source="s"))
    assert "image_url" not in data and "video_url" not in data and "entity_id" not in data
    assert "cancel" not in data
    assert data["actions"] == "[]"
    assert data["silent"] == "false"


def test_to_fcm_data_cancel_and_silent():
    data = to_fcm_data(Alert(id="x", cancel=True, silent=True, sent_at=NOW, source="s"))
    assert data["cancel"] == "true"
    assert data["silent"] == "true"
    assert data["title"] == "" and data["body"] == ""


def test_to_fcm_data_keeps_non_ascii_unescaped():
    data = to_fcm_data(Alert(id="x", title="T", actions=[{"id": "a", "label": "Désarmer"}], sent_at=NOW))
    assert "Désarmer" in data["actions"]


def test_small_body_is_not_truncated():
    data = to_fcm_data(Alert(id="x", title="T", body="hello", sent_at=NOW, source="s"))
    assert data["body"] == "hello"


def test_truncation_ascii_body_fits_under_limit():
    data = to_fcm_data(Alert(id="x", title="T", body="a" * 10_000, sent_at=NOW, source="s"))
    assert fcm_size(data) <= FCM_MAX_BYTES
    assert data["body"].endswith("…")
    assert data["body"].startswith("a" * 100)


def test_truncation_multibyte_body_stays_valid_utf8_and_under_limit():
    for body in ("é" * 5000, "🔥" * 3000, "é" + "a" * 5000 + "漢" * 100):
        data = to_fcm_data(Alert(id="x", title="Titre é", body=body, sent_at=NOW, source="s"))
        assert fcm_size(data) <= FCM_MAX_BYTES
        data["body"].encode("utf-8")  # raises if a character was cut in half
        assert data["body"].endswith("…")
        assert body.startswith(data["body"][:-1])


def test_ttl_constants():
    assert (DEFAULT_TTL, CANCEL_TTL, FCM_MAX_BYTES) == (43200, 300, 4096)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd $REPO && venv/bin/pytest tests/test_normalize.py -v`
Expected: collection error `ModuleNotFoundError: No module named 'normalize'`.

- [ ] **Step 3: Write minimal implementation**

Create `normalize.py`:

```python
"""Pure normalization of producer payloads (v1 and legacy Mqtt2FCMPush format).

No MQTT, no FCM, no I/O: `normalize` turns an already-parsed JSON value into an
`Alert` (or a `Rejected` reason) and `to_fcm_data` turns an `Alert` into the
string-only FCM data map.
"""
from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass, field
from typing import Any

CHANNELS = ("alarm", "security", "camera", "energy", "climate", "system", "misc")
SEVERITIES = ("info", "warning", "critical")
DEFAULT_TTL = 43200
CANCEL_TTL = 300
FCM_MAX_BYTES = 4096
ELLIPSIS = "…"

LEGACY_CHANNELS = {
    "alarme": "alarm",
    "nvr": "camera",
    "pv": "energy",
    "surveillance": "climate",
    "Supervisor": "system",
}


@dataclass(frozen=True)
class Alert:
    id: str
    channel: str = "misc"
    severity: str = "info"
    title: str = ""
    body: str = ""
    image_url: str | None = None
    video_url: str | None = None
    entity_id: str | None = None
    actions: list[dict[str, str]] = field(default_factory=list)
    silent: bool = False
    cancel: bool = False
    ttl: int = DEFAULT_TTL
    sent_at: int = 0
    source: str = ""


@dataclass(frozen=True)
class Rejected:
    reason: str


# ---------- small coercion helpers ----------

def _text(value: Any) -> str:
    """str or number -> str; anything else (None, bool, list, dict) -> ''."""
    if isinstance(value, str):
        return value
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return str(value)
    return ""


def _id_text(value: Any) -> str:
    """Id as a stripped string. 42 and 42.0 both give '42'."""
    if isinstance(value, float) and value.is_integer():
        value = int(value)
    return _text(value).strip()


def _opt(value: Any) -> str | None:
    text = _text(value).strip()
    return text or None


def _ttl(value: Any) -> int:
    if isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0:
        return int(value)
    return DEFAULT_TTL


def _truthy(value: Any) -> bool:
    return value is True or (isinstance(value, str) and value.strip().lower() == "true")


def _v1_actions(raw: Any) -> list[dict[str, str]]:
    out: list[dict[str, str]] = []
    if isinstance(raw, list):
        for item in raw:
            if isinstance(item, dict):
                action_id = _id_text(item.get("id"))
                label = _text(item.get("label")).strip()
                if action_id and label:
                    out.append({"id": action_id, "label": label})
    return out


def _legacy_actions(raw: Any) -> list[dict[str, str]]:
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except ValueError:
            return []
    out: list[dict[str, str]] = []
    if isinstance(raw, list):
        for item in raw:
            if isinstance(item, dict):
                action_id = _id_text(item.get("action"))
                label = _text(item.get("btn")).strip()
                if action_id and label:
                    out.append({"id": action_id, "label": label})
    return out


# ---------- normalize ----------

def normalize(topic: str, payload: Any, now: float | None = None) -> Alert | Rejected:
    """Normalize a parsed producer payload received on `topic`."""
    if not isinstance(payload, dict):
        return Rejected("payload is not a JSON object")
    source = topic.rstrip("/").rsplit("/", 1)[-1]
    sent_at = int(time.time() if now is None else now)
    if "v" in payload:
        return _normalize_v1(source, payload, sent_at)
    return _normalize_legacy(source, payload, sent_at)


def _normalize_v1(source: str, payload: dict, sent_at: int) -> Alert | Rejected:
    version = payload["v"]
    if type(version) is not int or version != 1:
        return Rejected(f"unsupported v: {version!r}")
    alert_id = _id_text(payload.get("id"))
    if not alert_id:
        return Rejected("v1 payload without id")
    channel = payload.get("channel")
    channel = channel if isinstance(channel, str) and channel in CHANNELS else "misc"
    if payload.get("cancel") is True:
        return Alert(id=alert_id, channel=channel, cancel=True, sent_at=sent_at, source=source)
    title = _text(payload.get("title")).strip()
    if not title:
        return Rejected("v1 payload without title")
    severity = payload.get("severity")
    severity = severity if isinstance(severity, str) and severity in SEVERITIES else "info"
    return Alert(
        id=alert_id,
        channel=channel,
        severity=severity,
        title=title,
        body=_text(payload.get("body")),
        image_url=_opt(payload.get("image_url")),
        video_url=_opt(payload.get("video_url")),
        entity_id=_opt(payload.get("entity_id")),
        actions=_v1_actions(payload.get("actions")),
        silent=payload.get("silent") is True,
        ttl=_ttl(payload.get("ttl")),
        sent_at=sent_at,
        source=source,
    )


def _normalize_legacy(source: str, payload: dict, sent_at: int) -> Alert:
    raw_title = _text(payload.get("title"))
    body = _text(payload.get("body"))
    data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
    alert_id = _id_text(data.get("id")) or (
        f"{source}-{hashlib.sha1((raw_title + body).encode('utf-8')).hexdigest()[:8]}"
    )
    channel_id = data.get("channelId")
    channel = LEGACY_CHANNELS.get(channel_id, "misc") if isinstance(channel_id, str) else "misc"
    severity = "warning" if payload.get("priority") == "high" else "info"
    if not raw_title.strip():
        return Alert(id=alert_id, channel=channel, severity=severity, cancel=True,
                     sent_at=sent_at, source=source)
    return Alert(
        id=alert_id,
        channel=channel,
        severity=severity,
        title=raw_title.strip(),
        body=body,
        image_url=_opt(data.get("image_url")),
        video_url=_opt(data.get("video_url")),
        actions=_legacy_actions(data.get("actions")),
        silent=_truthy(data.get("silent")),
        ttl=_ttl(payload.get("ttl")),
        sent_at=sent_at,
        source=source,
    )


# ---------- FCM data map ----------

def _size(data: dict[str, str]) -> int:
    return sum(len(k.encode("utf-8")) + len(v.encode("utf-8")) for k, v in data.items())


def to_fcm_data(alert: Alert) -> dict[str, str]:
    """Build the string-only FCM data map; truncate `body` if it exceeds 4 KB."""
    data = {
        "v": "1",
        "id": alert.id,
        "channel": alert.channel,
        "severity": alert.severity,
        "title": alert.title,
        "body": alert.body,
        "actions": json.dumps(alert.actions, ensure_ascii=False, separators=(",", ":")),
        "silent": "true" if alert.silent else "false",
        "sent_at": str(alert.sent_at),
        "source": alert.source,
    }
    for key in ("image_url", "video_url", "entity_id"):
        value = getattr(alert, key)
        if value is not None:
            data[key] = value
    if alert.cancel:
        data["cancel"] = "true"
    return _fit(data)


def _fit(data: dict[str, str]) -> dict[str, str]:
    """Keep key+value UTF-8 bytes under FCM_MAX_BYTES by truncating `body`."""
    if _size(data) <= FCM_MAX_BYTES:
        return data
    fixed = _size({**data, "body": ""})
    budget = FCM_MAX_BYTES - fixed - len(ELLIPSIS.encode("utf-8"))
    out = dict(data)
    if budget < 0:
        out["body"] = ""
        return out
    cut = data["body"].encode("utf-8")[:budget]
    out["body"] = cut.decode("utf-8", errors="ignore") + ELLIPSIS
    return out
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd $REPO && venv/bin/pytest tests/test_normalize.py -v`
Expected: all pass (about 60 parametrized cases, 0 failed).

- [ ] **Step 5: Commit**

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/OikosPush
git add normalize.py tests/test_normalize.py
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat: normalize v1 and legacy payloads, build FCM data map" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `tokens.py` (token registry)

**Files:**
- Create: `$REPO/tokens.py`
- Test: `$REPO/tests/test_tokens.py`

**Interfaces:**
Consumes: nothing.
Produces:
- `@dataclass(frozen=True) class Registration`: `token: str`, `device_name: str = ""`, `lang: str = ""`, `updated_at: int = 0`.
- `class TokenRegistry(logger: logging.Logger | None = None)`:
  - `handle(install_id: str, payload: bytes | str) -> str` returning one of `"registered" | "updated" | "removed" | "noop" | "ignored"`;
  - `remove(install_id: str) -> bool`;
  - `clear() -> None`;
  - `get(install_id: str) -> Registration | None`;
  - `tokens() -> list[str]` (unique, insertion order);
  - `installs_for_token(token: str) -> list[str]`;
  - `__len__() -> int` (number of installs).

- [ ] **Step 1: Write the failing test**

Create `tests/test_tokens.py`:

```python
import json

from tokens import Registration, TokenRegistry


def reg_payload(token="tok-a", **extra):
    return json.dumps({"token": token, **extra})


def test_register_new_install():
    r = TokenRegistry()
    status = r.handle("inst-a", reg_payload("tok-a", device_name="Pixel 8", lang="fr", updated_at=1700000000))
    assert status == "registered"
    assert r.get("inst-a") == Registration(token="tok-a", device_name="Pixel 8", lang="fr", updated_at=1700000000)
    assert r.tokens() == ["tok-a"]
    assert len(r) == 1


def test_update_replaces_token_for_same_install():
    r = TokenRegistry()
    r.handle("inst-a", reg_payload("old"))
    assert r.handle("inst-a", reg_payload("new")) == "updated"
    assert r.tokens() == ["new"]
    assert len(r) == 1


def test_only_token_is_required_and_unknown_fields_are_ignored():
    r = TokenRegistry()
    assert r.handle("inst-a", reg_payload("t", whatever=[1, 2], other={"x": 1})) == "registered"
    assert r.get("inst-a") == Registration(token="t")


def test_bytes_payload_is_accepted():
    r = TokenRegistry()
    assert r.handle("inst-a", reg_payload("t").encode("utf-8")) == "registered"


def test_empty_payload_unregisters():
    r = TokenRegistry()
    r.handle("inst-a", reg_payload("t"))
    assert r.handle("inst-a", b"") == "removed"
    assert r.get("inst-a") is None
    assert r.tokens() == []


def test_empty_payload_for_unknown_install_is_noop():
    assert TokenRegistry().handle("ghost", b"") == "noop"
    assert TokenRegistry().handle("ghost", "   ") == "noop"


def test_bad_inputs_are_ignored_and_keep_existing_entry():
    r = TokenRegistry()
    r.handle("inst-a", reg_payload("keep"))
    for bad in [b"not json", b"\xff\xfe", b"[1]", b"42", b"null", b'{"device_name":"x"}',
                b'{"token": ""}', b'{"token": "  "}', b'{"token": 5}', b'{"token": null}']:
        assert r.handle("inst-a", bad) == "ignored", bad
    assert r.tokens() == ["keep"]


def test_duplicate_tokens_are_listed_once():
    r = TokenRegistry()
    r.handle("inst-a", reg_payload("same"))
    r.handle("inst-b", reg_payload("same"))
    r.handle("inst-c", reg_payload("other"))
    assert r.tokens() == ["same", "other"]
    assert r.installs_for_token("same") == ["inst-a", "inst-b"]
    assert r.installs_for_token("nope") == []


def test_remove_and_clear():
    r = TokenRegistry()
    r.handle("inst-a", reg_payload("a"))
    r.handle("inst-b", reg_payload("b"))
    assert r.remove("inst-a") is True
    assert r.remove("inst-a") is False
    r.clear()
    assert len(r) == 0 and r.tokens() == []
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd $REPO && venv/bin/pytest tests/test_tokens.py -v`
Expected: collection error `ModuleNotFoundError: No module named 'tokens'`.

- [ ] **Step 3: Write minimal implementation**

Create `tokens.py`:

```python
"""installId -> FCM registration map, fed by the retained `tokens/<installId>` messages."""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass


@dataclass(frozen=True)
class Registration:
    token: str
    device_name: str = ""
    lang: str = ""
    updated_at: int = 0


class TokenRegistry:
    def __init__(self, logger: logging.Logger | None = None):
        self._by_install: dict[str, Registration] = {}
        self._log = logger or logging.getLogger(__name__)

    def handle(self, install_id: str, payload: bytes | str) -> str:
        """Apply one `tokens/<install_id>` message.

        Returns "registered", "updated", "removed", "noop" or "ignored".
        """
        text = payload.decode("utf-8", errors="replace") if isinstance(payload, bytes) else payload
        if not text.strip():
            return "removed" if self._by_install.pop(install_id, None) is not None else "noop"
        try:
            data = json.loads(text)
        except ValueError:
            self._log.warning("Token message for %s is not valid JSON; ignored", install_id)
            return "ignored"
        token = data.get("token") if isinstance(data, dict) else None
        if not isinstance(token, str) or not token.strip():
            self._log.warning("Token message for %s has no usable token; ignored", install_id)
            return "ignored"
        updated_at = data.get("updated_at")
        registration = Registration(
            token=token.strip(),
            device_name=str(data.get("device_name") or ""),
            lang=str(data.get("lang") or ""),
            updated_at=updated_at if isinstance(updated_at, int) and not isinstance(updated_at, bool) else 0,
        )
        status = "updated" if install_id in self._by_install else "registered"
        self._by_install[install_id] = registration
        return status

    def remove(self, install_id: str) -> bool:
        return self._by_install.pop(install_id, None) is not None

    def clear(self) -> None:
        self._by_install.clear()

    def get(self, install_id: str) -> Registration | None:
        return self._by_install.get(install_id)

    def tokens(self) -> list[str]:
        """Unique tokens in registration order (two installs sharing a token are sent to once)."""
        return list(dict.fromkeys(r.token for r in self._by_install.values()))

    def installs_for_token(self, token: str) -> list[str]:
        return [i for i, r in self._by_install.items() if r.token == token]

    def __len__(self) -> int:
        return len(self._by_install)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd $REPO && venv/bin/pytest tests/test_tokens.py -v`
Expected: 9 passed.

- [ ] **Step 5: Commit**

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/OikosPush
git add tokens.py tests/test_tokens.py
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat: token registry fed by retained token messages" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `history.py` (catch-up buffer)

**Files:**
- Create: `$REPO/history.py`
- Test: `$REPO/tests/test_history.py`

**Interfaces:**
Consumes: `normalize.Alert` (fields `id, channel, severity, title, body, image_url, video_url, entity_id, actions, silent, cancel, sent_at, source`).
Produces:
- `to_history_entry(alert: Alert) -> dict`: keys `v` (int 1), `id`, `channel`, `severity`, `title`, `body`, `actions` (list of `{id,label}`), `silent` (bool), `sent_at` (int), `source`; plus `image_url`/`video_url`/`entity_id` only when not None; plus `cancel: True` only for cancels.
- `class History(path: str | Path, size: int = 100, days: int = 7, clock: Callable[[], float] = time.time, logger: logging.Logger | None = None)`:
  - `apply(alert: Alert) -> None` (insert/replace/cancel, trim, persist);
  - `trim() -> None` (drops entries older than `days` by `sent_at`, then keeps `size` newest-first; does not persist);
  - `entries() -> list[dict]` (deep copy, newest first);
  - `to_json() -> str` (compact JSON array, non-ASCII unescaped);
  - `__len__() -> int`.
  - Loads `path` on construction (missing or corrupt file -> empty list, corrupt is logged).

- [ ] **Step 1: Write the failing test**

Create `tests/test_history.py`:

```python
import json

from history import History, to_history_entry
from normalize import Alert

NOW = 1_700_000_000
DAY = 86400


def mk(alert_id, sent_at=NOW, **kw):
    kw.setdefault("title", f"title-{alert_id}")
    return Alert(id=alert_id, channel="camera", body="b", sent_at=sent_at, source="src", **kw)


def make(tmp_path, **kw):
    return History(tmp_path / "data" / "history.json", clock=lambda: NOW, **kw)


def ids(h):
    return [e["id"] for e in h.entries()]


def test_new_alerts_are_prepended_newest_first(tmp_path):
    h = make(tmp_path)
    for i in "abc":
        h.apply(mk(i))
    assert ids(h) == ["c", "b", "a"]


def test_same_id_replaces_in_place_and_moves_to_front(tmp_path):
    h = make(tmp_path)
    for i in "abc":
        h.apply(mk(i))
    h.apply(mk("a", title="updated"))
    assert ids(h) == ["a", "c", "b"]
    assert len(h) == 3
    assert h.entries()[0]["title"] == "updated"


def test_cancel_marks_existing_alert_resolved(tmp_path):
    h = make(tmp_path)
    h.apply(mk("a"))
    h.apply(Alert(id="a", cancel=True, sent_at=NOW, source="src"))
    assert len(h) == 1
    entry = h.entries()[0]
    assert entry["cancel"] is True
    assert entry["title"] == "title-a"


def test_cancel_for_unknown_id_is_stored_alone(tmp_path):
    h = make(tmp_path)
    h.apply(mk("a"))
    h.apply(Alert(id="ghost", cancel=True, sent_at=NOW, source="src"))
    assert ids(h) == ["ghost", "a"]
    ghost = h.entries()[0]
    assert ghost["cancel"] is True and ghost["title"] == "" and ghost["body"] == ""


def test_cancel_keeps_position_and_reposting_clears_resolved(tmp_path):
    h = make(tmp_path)
    for i in "abc":
        h.apply(mk(i))
    assert ids(h) == ["c", "b", "a"]
    h.apply(Alert(id="a", cancel=True, sent_at=NOW, source="src"))
    assert ids(h) == ["c", "b", "a"]                       # resolved in place, not moved
    assert h.entries()[2]["cancel"] is True
    h.apply(mk("a", title="again"))                          # same id re-alerts
    assert ids(h) == ["a", "c", "b"]
    assert "cancel" not in h.entries()[0]
    assert h.entries()[0]["title"] == "again"


def test_size_cap_keeps_newest(tmp_path):
    h = make(tmp_path, size=3)
    for i in "abcde":
        h.apply(mk(i))
    assert ids(h) == ["e", "d", "c"]


def test_day_cap_drops_old_entries_by_sent_at(tmp_path):
    h = make(tmp_path, days=7)
    h.apply(mk("fresh", sent_at=NOW - DAY))
    h.apply(mk("edge", sent_at=NOW - 7 * DAY))               # exactly 7 days: kept
    h.apply(mk("old", sent_at=NOW - 7 * DAY - 1))            # older: dropped
    assert sorted(ids(h)) == ["edge", "fresh"]


def test_persistence_round_trip_and_creates_parent_dir(tmp_path):
    h = make(tmp_path)
    h.apply(mk("a", image_url="http://x/a.jpg", actions=[{"id": "go", "label": "Go"}]))
    h.apply(mk("b"))
    reloaded = make(tmp_path)
    assert reloaded.entries() == h.entries()
    assert (tmp_path / "data" / "history.json").exists()


def test_load_trims_stale_entries(tmp_path):
    path = tmp_path / "data" / "history.json"
    path.parent.mkdir()
    old = to_history_entry(mk("old", sent_at=NOW - 30 * DAY))
    fresh = to_history_entry(mk("fresh"))
    path.write_text(json.dumps([fresh, old]), encoding="utf-8")
    assert ids(make(tmp_path)) == ["fresh"]


def test_corrupt_or_wrong_shape_file_starts_empty(tmp_path):
    path = tmp_path / "data" / "history.json"
    path.parent.mkdir()
    path.write_text("{not json", encoding="utf-8")
    assert len(make(tmp_path)) == 0
    path.write_text('{"a": 1}', encoding="utf-8")
    assert len(make(tmp_path)) == 0
    path.write_text('[1, "x", {"id": 5}, {"id": "ok", "sent_at": %d}]' % NOW, encoding="utf-8")
    assert ids(make(tmp_path)) == ["ok"]


def test_entry_has_typed_json_values():
    entry = to_history_entry(Alert(
        id="a", channel="alarm", severity="critical", title="T", body="B",
        image_url="http://i", video_url="http://v", entity_id="e.x",
        actions=[{"id": "go", "label": "Go"}], silent=True, sent_at=NOW, source="s"))
    assert entry == {
        "v": 1, "id": "a", "channel": "alarm", "severity": "critical", "title": "T", "body": "B",
        "image_url": "http://i", "video_url": "http://v", "entity_id": "e.x",
        "actions": [{"id": "go", "label": "Go"}], "silent": True, "sent_at": NOW, "source": "s",
    }
    assert isinstance(entry["v"], int) and isinstance(entry["sent_at"], int)
    assert isinstance(entry["silent"], bool) and isinstance(entry["actions"], list)


def test_entry_omits_absent_optionals_and_flags_cancel():
    entry = to_history_entry(Alert(id="a", sent_at=NOW, source="s"))
    assert "image_url" not in entry and "video_url" not in entry and "entity_id" not in entry
    assert "cancel" not in entry
    assert entry["actions"] == [] and entry["silent"] is False
    assert to_history_entry(Alert(id="a", cancel=True, sent_at=NOW))["cancel"] is True


def test_to_json_is_a_compact_array_with_unescaped_unicode(tmp_path):
    h = make(tmp_path)
    h.apply(mk("a", title="Désarmé"))
    raw = h.to_json()
    assert "Désarmé" in raw and ", " not in raw
    assert json.loads(raw) == h.entries()


def test_entries_returns_a_copy(tmp_path):
    h = make(tmp_path)
    h.apply(mk("a"))
    h.entries()[0]["title"] = "mutated"
    assert h.entries()[0]["title"] == "title-a"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd $REPO && venv/bin/pytest tests/test_history.py -v`
Expected: collection error `ModuleNotFoundError: No module named 'history'`.

- [ ] **Step 3: Write minimal implementation**

Create `history.py`:

```python
"""Catch-up buffer: recent normalized alerts, newest first, persisted to disk."""
from __future__ import annotations

import copy
import json
import logging
import os
import time
from pathlib import Path
from typing import Callable

from normalize import Alert

DAY_SECONDS = 86400


def to_history_entry(alert: Alert) -> dict:
    """Same keys as the FCM data map, but typed JSON values."""
    entry: dict = {
        "v": 1,
        "id": alert.id,
        "channel": alert.channel,
        "severity": alert.severity,
        "title": alert.title,
        "body": alert.body,
        "actions": [dict(a) for a in alert.actions],
        "silent": alert.silent,
        "sent_at": int(alert.sent_at),
        "source": alert.source,
    }
    for key in ("image_url", "video_url", "entity_id"):
        value = getattr(alert, key)
        if value is not None:
            entry[key] = value
    if alert.cancel:
        entry["cancel"] = True
    return entry


class History:
    def __init__(
        self,
        path: str | Path,
        size: int = 100,
        days: int = 7,
        clock: Callable[[], float] = time.time,
        logger: logging.Logger | None = None,
    ):
        self.path = Path(path)
        self.size = size
        self.days = days
        self._clock = clock
        self._log = logger or logging.getLogger(__name__)
        self._entries: list[dict] = []
        self._load()

    # ----- persistence -----

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            self._log.error("Cannot read %s (%s); starting with an empty history", self.path, exc)
            return
        if not isinstance(data, list):
            self._log.error("%s is not a JSON array; starting with an empty history", self.path)
            return
        self._entries = [e for e in data if isinstance(e, dict) and isinstance(e.get("id"), str)]
        self.trim()

    def _save(self) -> None:
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".json.tmp")
            tmp.write_text(self.to_json(), encoding="utf-8")
            os.replace(tmp, self.path)
        except OSError as exc:
            self._log.error("Failed to save history to %s: %s", self.path, exc)

    # ----- mutation -----

    def apply(self, alert: Alert) -> None:
        index = next((i for i, e in enumerate(self._entries) if e["id"] == alert.id), None)
        if alert.cancel:
            if index is not None:
                self._entries[index]["cancel"] = True      # resolved in place
            else:
                self._entries.insert(0, to_history_entry(alert))
        else:
            if index is not None:
                del self._entries[index]
            self._entries.insert(0, to_history_entry(alert))
        self.trim()
        self._save()

    def trim(self) -> None:
        cutoff = self._clock() - self.days * DAY_SECONDS
        kept = [
            e for e in self._entries
            if isinstance(e.get("sent_at"), (int, float)) and e["sent_at"] >= cutoff
        ]
        self._entries = kept[: self.size]

    # ----- read -----

    def entries(self) -> list[dict]:
        return copy.deepcopy(self._entries)

    def to_json(self) -> str:
        return json.dumps(self._entries, ensure_ascii=False, separators=(",", ":"))

    def __len__(self) -> int:
        return len(self._entries)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd $REPO && venv/bin/pytest tests/test_history.py -v`
Expected: 14 passed.

- [ ] **Step 5: Commit**

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/OikosPush
git add history.py tests/test_history.py
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat: persistent catch-up history with caps and typed entries" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `fcm_sender.py` and `notification_queue.py`

**Files:**
- Create: `$REPO/fcm_sender.py`, `$REPO/notification_queue.py`, `$REPO/tests/fake_fcm.py`
- Test: `$REPO/tests/test_fcm_sender.py`, `$REPO/tests/test_notification_queue.py`

**Interfaces:**
Consumes: `normalize.Alert`, `normalize.CANCEL_TTL`.
Produces:
- `fcm_sender.HIGH_PRIORITY_CHANNELS: frozenset[str]` = `{"alarm","security","camera"}`.
- `fcm_sender.android_priority(alert: Alert) -> str` (`"high"` | `"normal"`).
- `fcm_sender.ttl_seconds(alert: Alert) -> int` (`CANCEL_TTL` for cancels, else `alert.ttl`).
- `fcm_sender.classify_failure(exc: BaseException) -> str` (`"invalid"` for `UnregisteredError` / `InvalidArgumentError` anywhere in the class MRO, else `"transient"`).
- `@dataclass fcm_sender.SendResult`: `success_count: int = 0`, `invalid_tokens: list[str]`, `transient_tokens: list[str]`.
- `class fcm_sender.FCMSender(messaging=None, logger=None)` with `send_data(data: dict[str, str], tokens: list[str], priority: str, ttl: int) -> SendResult`. `messaging` defaults to `firebase_admin.messaging`.
- `fcm_sender.init_firebase(credentials_path: str)`.
- `class notification_queue.NotificationQueue(queue_file, retry_interval=60, max_retries=10, logger=None, clock=time.time, poll_interval=10)` with `add_notification(notification: dict) -> None` (uses `notification["ttl"]`, default 43200), `get_notifications_to_retry() -> list[dict]` (each item `{"notification","queued_at","expires_at","retry_count","next_retry"}`), `mark_success(item)`, `mark_failure(item)`, `run_once(send_callback: Callable[[dict], bool]) -> None`, `start_retry_worker(send_callback)`, `stop_retry_worker()`, `get_queue_status() -> dict`, attribute `queue: list[dict]`.
- Test stub `tests.fake_fcm.FakeMessaging(outcomes=None, raises=None, truncate_to=None)` with attribute `sent` (recorded `MulticastMessage`s with `.data`, `.android`, `.tokens`); `outcomes` maps token -> exception or `None`.

- [ ] **Step 1: Write the failing tests**

Create `tests/fake_fcm.py`:

```python
"""Stand-in for firebase_admin.messaging."""


class FakeResponse:
    def __init__(self, exception=None):
        self.success = exception is None
        self.exception = exception


class FakeBatch:
    def __init__(self, responses):
        self.responses = responses
        self.success_count = sum(1 for r in responses if r.success)
        self.failure_count = len(responses) - self.success_count


class _Record:
    def __init__(self, **kwargs):
        self.__dict__.update(kwargs)


class FakeMessaging:
    MulticastMessage = _Record
    AndroidConfig = _Record

    def __init__(self, outcomes=None, raises=None, truncate_to=None):
        self.outcomes = outcomes or {}
        self.raises = raises
        self.truncate_to = truncate_to
        self.sent = []

    def send_each_for_multicast(self, message):
        self.sent.append(message)
        if self.raises is not None:
            raise self.raises
        responses = [FakeResponse(self.outcomes.get(token)) for token in message.tokens]
        if self.truncate_to is not None:
            responses = responses[: self.truncate_to]
        return FakeBatch(responses)
```

Create `tests/test_fcm_sender.py`:

```python
import datetime

import pytest
from firebase_admin import exceptions, messaging

from fcm_sender import (
    FCMSender, SendResult, android_priority, classify_failure, ttl_seconds,
)
from normalize import Alert
from tests.fake_fcm import FakeMessaging


def alert(**kw):
    return Alert(id="x", title="T", **kw)


# ---------- priority ----------

@pytest.mark.parametrize("channel, severity, expected", [
    ("misc", "warning", "high"),
    ("misc", "critical", "high"),
    ("energy", "info", "normal"),
    ("climate", "info", "normal"),
    ("system", "info", "normal"),
    ("misc", "info", "normal"),
    ("alarm", "info", "high"),
    ("security", "info", "high"),
    ("camera", "info", "high"),
    ("energy", "warning", "high"),
])
def test_android_priority(channel, severity, expected):
    assert android_priority(alert(channel=channel, severity=severity)) == expected


# ---------- ttl ----------

def test_ttl_is_payload_ttl_with_default():
    assert ttl_seconds(alert()) == 43200
    assert ttl_seconds(alert(ttl=600)) == 600


def test_cancel_ttl_is_300_whatever_the_payload_ttl():
    assert ttl_seconds(alert(cancel=True, ttl=43200)) == 300


# ---------- classification ----------

def test_classify_failure():
    assert classify_failure(messaging.UnregisteredError("gone")) == "invalid"
    assert classify_failure(exceptions.InvalidArgumentError("bad token")) == "invalid"
    assert classify_failure(exceptions.UnavailableError("fcm down")) == "transient"
    assert classify_failure(exceptions.InternalError("oops")) == "transient"
    assert classify_failure(RuntimeError("anything")) == "transient"
    assert classify_failure(None) == "transient"


# ---------- sending ----------

def test_send_data_builds_a_data_only_multicast_message():
    fake = FakeMessaging()
    result = FCMSender(messaging=fake).send_data({"id": "x", "title": "T"}, ["t1", "t2"], "high", 600)
    msg = fake.sent[0]
    assert msg.data == {"id": "x", "title": "T"}
    assert msg.tokens == ["t1", "t2"]
    assert msg.android.priority == "high"
    assert msg.android.ttl == datetime.timedelta(seconds=600)
    assert not hasattr(msg, "notification")
    assert result == SendResult(success_count=2)


def test_send_data_with_no_tokens_does_not_call_fcm():
    fake = FakeMessaging()
    assert FCMSender(messaging=fake).send_data({"id": "x"}, [], "high", 60) == SendResult()
    assert fake.sent == []


def test_failures_are_split_per_error_kind():
    fake = FakeMessaging(outcomes={
        "gone": messaging.UnregisteredError("gone"),
        "bad": exceptions.InvalidArgumentError("bad"),
        "later": exceptions.UnavailableError("down"),
    })
    result = FCMSender(messaging=fake).send_data(
        {"id": "x"}, ["ok", "gone", "bad", "later"], "normal", 60)
    assert result.success_count == 1
    assert result.invalid_tokens == ["gone", "bad"]
    assert result.transient_tokens == ["later"]


def test_whole_call_failure_makes_every_token_transient():
    fake = FakeMessaging(raises=ConnectionError("network down"))
    result = FCMSender(messaging=fake).send_data({"id": "x"}, ["a", "b"], "high", 60)
    assert result == SendResult(success_count=0, invalid_tokens=[], transient_tokens=["a", "b"])


def test_missing_responses_are_treated_as_transient():
    fake = FakeMessaging(truncate_to=1)
    result = FCMSender(messaging=fake).send_data({"id": "x"}, ["a", "b", "c"], "high", 60)
    assert result.success_count == 1
    assert result.transient_tokens == ["b", "c"]
```

Create `tests/test_notification_queue.py`:

```python
import json

from notification_queue import NotificationQueue


class Clock:
    def __init__(self, t=1000.0):
        self.t = t

    def __call__(self):
        return self.t


def note(ttl=43200, **kw):
    return {"id": "a", "tokens": ["t1"], "data": {"id": "a"}, "priority": "high", "ttl": ttl, **kw}


def make(tmp_path, clock, **kw):
    kw.setdefault("retry_interval", 60)
    kw.setdefault("max_retries", 3)
    return NotificationQueue(tmp_path / "queue.json", clock=clock, **kw)


def test_new_item_is_ready_immediately(tmp_path):
    q = make(tmp_path, Clock())
    q.add_notification(note())
    assert [i["notification"]["id"] for i in q.get_notifications_to_retry()] == ["a"]


def test_failure_delays_next_attempt_by_retry_interval(tmp_path):
    clock = Clock()
    q = make(tmp_path, clock)
    q.add_notification(note())
    item = q.get_notifications_to_retry()[0]
    q.mark_failure(item)
    assert item["retry_count"] == 1
    assert q.get_notifications_to_retry() == []
    clock.t += 59
    assert q.get_notifications_to_retry() == []
    clock.t += 2
    assert len(q.get_notifications_to_retry()) == 1


def test_item_is_discarded_after_max_retries(tmp_path):
    clock = Clock()
    q = make(tmp_path, clock, max_retries=2)
    q.add_notification(note())
    for _ in range(2):
        q.mark_failure(q.get_notifications_to_retry()[0])
        clock.t += 61
    assert q.get_notifications_to_retry() == []
    assert q.queue == []


def test_item_is_discarded_when_ttl_expires(tmp_path):
    clock = Clock()
    q = make(tmp_path, clock)
    q.add_notification(note(ttl=100))
    clock.t += 101
    assert q.get_notifications_to_retry() == []
    assert q.queue == []


def test_success_removes_item(tmp_path):
    q = make(tmp_path, Clock())
    q.add_notification(note())
    q.mark_success(q.get_notifications_to_retry()[0])
    assert q.queue == []


def test_queue_survives_restart(tmp_path):
    clock = Clock()
    q = make(tmp_path, clock)
    q.add_notification(note(tokens=["t9"]))
    q.mark_failure(q.get_notifications_to_retry()[0])
    reloaded = make(tmp_path, clock)
    assert reloaded.queue[0]["notification"]["tokens"] == ["t9"]
    assert reloaded.queue[0]["retry_count"] == 1


def test_mutating_the_notification_is_persisted_on_failure(tmp_path):
    clock = Clock()
    q = make(tmp_path, clock)
    q.add_notification(note(tokens=["a", "b"]))
    item = q.get_notifications_to_retry()[0]
    item["notification"]["tokens"] = ["b"]
    q.mark_failure(item)
    assert json.loads((tmp_path / "queue.json").read_text())[0]["notification"]["tokens"] == ["b"]


def test_corrupt_file_is_backed_up_and_queue_starts_empty(tmp_path):
    (tmp_path / "queue.json").write_text("{broken", encoding="utf-8")
    q = make(tmp_path, Clock())
    assert q.queue == []
    assert (tmp_path / "queue.json.backup").exists()


def test_run_once_marks_success_failure_and_exceptions(tmp_path):
    clock = Clock()
    q = make(tmp_path, clock)
    for name in ("ok", "no", "boom"):
        q.add_notification(note(id=name))

    def send(notification):
        if notification["id"] == "boom":
            raise RuntimeError("x")
        return notification["id"] == "ok"

    q.run_once(send)
    left = {i["notification"]["id"]: i["retry_count"] for i in q.queue}
    assert left == {"no": 1, "boom": 1}


def test_status_counts(tmp_path):
    clock = Clock()
    q = make(tmp_path, clock)
    q.add_notification(note(id="ready"))
    q.add_notification(note(id="waiting"))
    q.mark_failure(q.queue[1])
    assert q.get_queue_status() == {"total": 2, "ready_to_retry": 1, "expired": 0, "waiting": 1}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd $REPO && venv/bin/pytest tests/test_fcm_sender.py tests/test_notification_queue.py -v`
Expected: collection errors `ModuleNotFoundError: No module named 'fcm_sender'` and `... 'notification_queue'`.

- [ ] **Step 3: Write minimal implementation**

Create `fcm_sender.py`:

```python
"""FCM multicast sender: data-only messages, failure classification."""
from __future__ import annotations

import datetime
import importlib
import logging
from dataclasses import dataclass, field
from typing import Any

from normalize import CANCEL_TTL, Alert

HIGH_PRIORITY_CHANNELS = frozenset({"alarm", "security", "camera"})
INVALID_TOKEN_ERRORS = frozenset({"UnregisteredError", "InvalidArgumentError"})


def android_priority(alert: Alert) -> str:
    """`high` for warning/critical or the alarm/security/camera channels."""
    if alert.severity in ("warning", "critical") or alert.channel in HIGH_PRIORITY_CHANNELS:
        return "high"
    return "normal"


def ttl_seconds(alert: Alert) -> int:
    return CANCEL_TTL if alert.cancel else alert.ttl


def classify_failure(exc: BaseException | None) -> str:
    """`invalid` when FCM says the token is unregistered / invalid, else `transient`."""
    if exc is not None and any(c.__name__ in INVALID_TOKEN_ERRORS for c in type(exc).__mro__):
        return "invalid"
    return "transient"


@dataclass
class SendResult:
    success_count: int = 0
    invalid_tokens: list[str] = field(default_factory=list)
    transient_tokens: list[str] = field(default_factory=list)


class FCMSender:
    def __init__(self, messaging: Any = None, logger: logging.Logger | None = None):
        self._m = messaging or importlib.import_module("firebase_admin.messaging")
        self._log = logger or logging.getLogger(__name__)

    def send_data(self, data: dict[str, str], tokens: list[str], priority: str, ttl: int) -> SendResult:
        """Send one data-only multicast message; split the failed tokens by error kind."""
        if not tokens:
            return SendResult()
        try:
            message = self._m.MulticastMessage(
                data=data,
                android=self._m.AndroidConfig(ttl=datetime.timedelta(seconds=ttl), priority=priority),
                tokens=list(tokens),
            )
            response = self._m.send_each_for_multicast(message)
        except Exception as exc:  # network down, auth error, ...: retry later for every token
            self._log.error("FCM send failed for %d token(s): %s", len(tokens), exc)
            return SendResult(transient_tokens=list(tokens))

        result = SendResult()
        responses = response.responses
        for index, token in enumerate(tokens):
            if index >= len(responses):
                result.transient_tokens.append(token)
                continue
            outcome = responses[index]
            if outcome.success:
                result.success_count += 1
            elif classify_failure(outcome.exception) == "invalid":
                self._log.warning("Token %d is invalid: %s", index, outcome.exception)
                result.invalid_tokens.append(token)
            else:
                self._log.warning("Transient failure for token %d: %s", index, outcome.exception)
                result.transient_tokens.append(token)
        self._log.info("FCM: %d sent, %d invalid, %d to retry", result.success_count,
                       len(result.invalid_tokens), len(result.transient_tokens))
        return result


def init_firebase(credentials_path: str):
    """Initialise firebase_admin with the Oikos project's service account."""
    import firebase_admin
    from firebase_admin import credentials

    return firebase_admin.initialize_app(credentials.Certificate(credentials_path))
```

Create `notification_queue.py`:

```python
"""Persistent retry queue (port of Mqtt2FCMPush's, with an injectable clock)."""
from __future__ import annotations

import json
import logging
import os
import threading
import time
from pathlib import Path
from typing import Any, Callable

DEFAULT_TTL = 43200


class NotificationQueue:
    def __init__(
        self,
        queue_file: str | Path = "notification_queue.json",
        retry_interval: int = 60,
        max_retries: int = 10,
        logger: logging.Logger | None = None,
        clock: Callable[[], float] = time.time,
        poll_interval: float = 10,
    ):
        self.queue_file = Path(queue_file)
        self.retry_interval = retry_interval
        self.max_retries = max_retries
        self.logger = logger or logging.getLogger(__name__)
        self._clock = clock
        self._poll_interval = poll_interval
        self.queue: list[dict[str, Any]] = []
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._load()

    # ----- persistence -----

    def _load(self) -> None:
        if not self.queue_file.exists():
            return
        try:
            content = self.queue_file.read_text(encoding="utf-8").strip()
            self.queue = json.loads(content) if content else []
            self.logger.info("Loaded %d queued notification(s)", len(self.queue))
        except ValueError as exc:
            self.logger.error("Corrupt queue file (%s); backing it up", exc)
            self.queue = []
            try:
                self.queue_file.rename(self.queue_file.with_suffix(".json.backup"))
            except OSError as backup_error:
                self.logger.error("Could not back up the corrupt queue: %s", backup_error)
        except OSError as exc:
            self.logger.error("Failed to read the queue: %s", exc)
            self.queue = []

    def _save(self) -> None:
        try:
            self.queue_file.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.queue_file.with_suffix(".json.tmp")
            tmp.write_text(json.dumps(self.queue, indent=2), encoding="utf-8")
            os.replace(tmp, self.queue_file)
        except OSError as exc:
            self.logger.error("Failed to save the queue: %s", exc)

    # ----- queue operations -----

    def add_notification(self, notification: dict[str, Any]) -> None:
        now = self._clock()
        with self._lock:
            self.queue.append({
                "notification": notification,
                "queued_at": now,
                "expires_at": now + notification.get("ttl", DEFAULT_TTL),
                "retry_count": 0,
                "next_retry": now,
            })
            self._save()
            self.logger.info("Queued notification '%s' (total: %d)",
                             notification.get("id", "?"), len(self.queue))

    def get_notifications_to_retry(self) -> list[dict[str, Any]]:
        """Items ready now. Expired and max-retried items are removed here."""
        now = self._clock()
        with self._lock:
            ready, dropped = [], []
            for item in self.queue:
                if now > item["expires_at"]:
                    dropped.append(item)
                    self.logger.warning("Notification '%s' expired (TTL)", item["notification"].get("id", "?"))
                elif item["retry_count"] >= self.max_retries:
                    dropped.append(item)
                    self.logger.error("Max retries reached for '%s'", item["notification"].get("id", "?"))
                elif now >= item["next_retry"]:
                    ready.append(item)
            for item in dropped:
                self.queue.remove(item)
            if dropped:
                self._save()
            return ready

    def mark_success(self, item: dict[str, Any]) -> None:
        with self._lock:
            if item in self.queue:
                self.queue.remove(item)
                self._save()

    def mark_failure(self, item: dict[str, Any]) -> None:
        with self._lock:
            if item in self.queue:
                item["retry_count"] += 1
                item["next_retry"] = self._clock() + self.retry_interval
                self._save()
                self.logger.warning("Retry %d/%d failed for '%s'", item["retry_count"],
                                    self.max_retries, item["notification"].get("id", "?"))

    def run_once(self, send_callback: Callable[[dict[str, Any]], bool]) -> None:
        """One retry pass. `send_callback(notification)` returns True when done with it."""
        for item in self.get_notifications_to_retry():
            try:
                done = send_callback(item["notification"])
            except Exception as exc:
                self.logger.error("Retry callback raised: %s", exc)
                done = False
            if done:
                self.mark_success(item)
            else:
                self.mark_failure(item)

    # ----- worker -----

    def start_retry_worker(self, send_callback: Callable[[dict[str, Any]], bool]) -> None:
        if self._thread and self._thread.is_alive():
            self.logger.warning("Retry worker already running")
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._worker, args=(send_callback,), daemon=True)
        self._thread.start()
        self.logger.info("Retry worker started")

    def _worker(self, send_callback: Callable[[dict[str, Any]], bool]) -> None:
        while not self._stop.is_set():
            try:
                self.run_once(send_callback)
            except Exception as exc:
                self.logger.error("Error in retry worker: %s", exc)
            self._stop.wait(self._poll_interval)

    def stop_retry_worker(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=5)
        self.logger.info("Retry worker stopped")

    def get_queue_status(self) -> dict[str, int]:
        now = self._clock()
        with self._lock:
            total = len(self.queue)
            expired = sum(1 for i in self.queue if now > i["expires_at"])
            ready = sum(1 for i in self.queue if now <= i["expires_at"] and now >= i["next_retry"])
            return {"total": total, "ready_to_retry": ready, "expired": expired,
                    "waiting": total - ready - expired}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd $REPO && venv/bin/pytest tests/test_fcm_sender.py tests/test_notification_queue.py -v`
Expected: all pass (about 27 cases, 0 failed).

- [ ] **Step 5: Commit**

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/OikosPush
git add fcm_sender.py notification_queue.py tests/fake_fcm.py tests/test_fcm_sender.py tests/test_notification_queue.py
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat: FCM data-only sender with failure classes and persistent retry queue" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: MQTT handler and service wiring

**Files:**
- Create: `$REPO/mqtt_handler.py`, `$REPO/oikos_push_service.py`, `$REPO/main.py`, `$REPO/tests/fake_mqtt.py`
- Test: `$REPO/tests/test_mqtt_handler.py`, `$REPO/tests/test_service.py`

**Interfaces:**
Consumes: `config.as_dict()` keys (Task 1); `normalize.normalize/to_fcm_data/Alert/Rejected` (Task 2); `tokens.TokenRegistry` (Task 3); `history.History` (`apply`, `trim`, `to_json`, `__len__`) (Task 4); `fcm_sender.FCMSender/SendResult/android_priority/ttl_seconds/init_firebase` and `notification_queue.NotificationQueue` (`add_notification`, `run_once`, `start_retry_worker`, `stop_retry_worker`, `queue`) (Task 5).
Produces:
- `class mqtt_handler.MQTTHandler(config: dict, logger, message_callback: Callable[[str, bytes], None], on_connected: Callable[[], None] | None = None, client_factory=None, sleep=time.sleep)`:
  - config keys read: `mqtt_broker`, `mqtt_port`, `mqtt_username`, `mqtt_password`, `topics: list[str]`, `heartbeat_enabled`, `heartbeat_interval`, `heartbeat_topic`, `auto_reconnect`, `reconnect_delay_min`, `reconnect_delay_max`;
  - `client_factory(client_id="oikos-push", clean_session=False)` defaults to `paho.mqtt.client.Client`;
  - `publish(topic: str, payload: str | bytes, qos: int = 1, retain: bool = False)`, `heartbeat_once() -> bool`, `connect()`, `start_loop()`, `disconnect()`; attributes `client`, `is_connected`.
- `class oikos_push_service.OikosPush(cfg: dict, logger=None, *, sender=None, queue=None, history=None, registry=None, mqtt_client_factory=None, clock=time.time)`:
  - `on_message(topic: str, payload: bytes) -> None`, `on_connected() -> None`, `dispatch(alert: Alert) -> None`, `start()`, `stop()`;
  - attributes `registry`, `history`, `queue`, `sender`, `handler`, `prefix`;
  - private `_retry_send(notification: dict) -> bool` (queue callback), `_cleanup_invalid(tokens: list[str])`, `_publish_history()`.
  - `sender` must offer `send_data(data, tokens, priority, ttl) -> SendResult`.
- `oikos_push_service.setup_logger() -> logging.Logger`; `main.main()`.
- Queue item notification shape: `{"id": str, "data": dict[str,str], "tokens": list[str], "priority": str, "ttl": int}`.
- Test stubs: `tests.fake_mqtt.FakeMQTTClient(client_id="", clean_session=True, fail_connects=0)` with `subscriptions: list[(topic, qos)]`, `published: list[Published(topic, payload, qos, retain)]`, `credentials`, `reconnect_delay`, `connected_to`, callbacks `on_connect/on_message/on_disconnect`; `tests.fake_mqtt.deliver(client, topic, payload)`.

- [ ] **Step 1: Write the failing tests**

Create `tests/fake_mqtt.py`:

```python
"""Stand-in for paho.mqtt.client.Client."""
from dataclasses import dataclass


@dataclass
class Published:
    topic: str
    payload: object
    qos: int
    retain: bool


@dataclass
class FakeMessage:
    topic: str
    payload: bytes


class FakePublishInfo:
    rc = 0

    def wait_for_publish(self, timeout=None):
        return None


class FakeMQTTClient:
    def __init__(self, client_id="", clean_session=True, fail_connects=0):
        self.client_id = client_id
        self.clean_session = clean_session
        self.fail_connects = fail_connects
        self.subscriptions = []
        self.published = []
        self.credentials = None
        self.reconnect_delay = None
        self.connected_to = None
        self.connect_attempts = 0
        self.disconnected = False
        self.on_connect = None
        self.on_message = None
        self.on_disconnect = None

    def username_pw_set(self, username, password=None):
        self.credentials = (username, password)

    def reconnect_delay_set(self, min_delay=1, max_delay=120):
        self.reconnect_delay = (min_delay, max_delay)

    def subscribe(self, topic, qos=0):
        self.subscriptions.append((topic, qos))
        return (0, 1)

    def publish(self, topic, payload=None, qos=0, retain=False):
        self.published.append(Published(topic, payload, qos, retain))
        return FakePublishInfo()

    def connect(self, host, port=1883, keepalive=60):
        self.connect_attempts += 1
        if self.connect_attempts <= self.fail_connects:
            raise ConnectionRefusedError("broker down")
        self.connected_to = (host, port)

    def loop_forever(self, retry_first_connection=False):
        return None

    def disconnect(self):
        self.disconnected = True


def deliver(client, topic, payload):
    """Simulate the broker delivering a message to the service's client."""
    if isinstance(payload, str):
        payload = payload.encode("utf-8")
    client.on_message(client, None, FakeMessage(topic, payload))


def connect_ok(client):
    client.on_connect(client, None, {}, 0)
```

Create `tests/test_mqtt_handler.py`:

```python
import json
import logging

from mqtt_handler import MQTTHandler
from tests.fake_mqtt import FakeMQTTClient, connect_ok, deliver

LOG = logging.getLogger("test")


def cfg(**over):
    base = {
        "mqtt_broker": "broker", "mqtt_port": 1884, "mqtt_username": None, "mqtt_password": None,
        "topics": ["oikos-push/notification/+", "oikos-push/tokens/+"],
        "heartbeat_enabled": False, "heartbeat_interval": 60,
        "heartbeat_topic": "oikos-push/heartbeat", "auto_reconnect": True,
        "reconnect_delay_min": 1, "reconnect_delay_max": 8,
    }
    base.update(over)
    return base


def test_client_uses_persistent_session_and_credentials():
    h = MQTTHandler(cfg(mqtt_username="u", mqtt_password="p"), LOG, lambda t, p: None,
                    client_factory=FakeMQTTClient)
    assert h.client.client_id == "oikos-push"
    assert h.client.clean_session is False
    assert h.client.credentials == ("u", "p")
    assert h.client.reconnect_delay == (1, 8)


def test_on_connect_subscribes_to_every_topic_with_qos1_and_calls_hook():
    calls = []
    h = MQTTHandler(cfg(), LOG, lambda t, p: None, on_connected=lambda: calls.append("hook"),
                    client_factory=FakeMQTTClient)
    connect_ok(h.client)
    assert h.client.subscriptions == [("oikos-push/notification/+", 1), ("oikos-push/tokens/+", 1)]
    assert calls == ["hook"]
    assert h.is_connected is True


def test_failed_connect_does_not_subscribe():
    h = MQTTHandler(cfg(), LOG, lambda t, p: None, client_factory=FakeMQTTClient)
    h.client.on_connect(h.client, None, {}, 5)
    assert h.client.subscriptions == []
    assert h.is_connected is False


def test_messages_reach_the_callback_with_topic_and_raw_bytes():
    got = []
    h = MQTTHandler(cfg(), LOG, lambda t, p: got.append((t, p)), client_factory=FakeMQTTClient)
    deliver(h.client, "oikos-push/notification/x", b"\xff raw")
    assert got == [("oikos-push/notification/x", b"\xff raw")]


def test_callback_exceptions_do_not_escape():
    def boom(topic, payload):
        raise RuntimeError("bug")

    h = MQTTHandler(cfg(), LOG, boom, client_factory=FakeMQTTClient)
    deliver(h.client, "oikos-push/notification/x", b"{}")  # must not raise


def test_publish_passes_qos_and_retain():
    h = MQTTHandler(cfg(), LOG, lambda t, p: None, client_factory=FakeMQTTClient)
    h.publish("a/b", b"", qos=1, retain=True)
    p = h.client.published[0]
    assert (p.topic, p.payload, p.qos, p.retain) == ("a/b", b"", 1, True)


def test_heartbeat_is_published_not_retained_and_only_when_connected():
    h = MQTTHandler(cfg(), LOG, lambda t, p: None, client_factory=FakeMQTTClient)
    assert h.heartbeat_once() is False
    assert h.client.published == []
    connect_ok(h.client)
    assert h.heartbeat_once() is True
    p = h.client.published[0]
    assert p.topic == "oikos-push/heartbeat" and p.retain is False
    assert isinstance(json.loads(p.payload)["timestamp"], int)


def test_connect_retries_with_exponential_backoff():
    sleeps = []
    h = MQTTHandler(cfg(), LOG, lambda t, p: None, client_factory=lambda **kw: FakeMQTTClient(fail_connects=3, **kw),
                    sleep=sleeps.append)
    h.connect()
    assert sleeps == [1, 2, 4]
    assert h.client.connected_to == ("broker", 1884)


def test_disconnect_marks_not_connected():
    h = MQTTHandler(cfg(), LOG, lambda t, p: None, client_factory=FakeMQTTClient)
    connect_ok(h.client)
    h.client.on_disconnect(h.client, None, 1)
    assert h.is_connected is False
```

Create `tests/test_service.py`:

```python
import json
import logging

import pytest

from fcm_sender import SendResult
from normalize import Alert
from oikos_push_service import OikosPush
from tests.fake_mqtt import FakeMQTTClient, connect_ok, deliver

NOW = 1_700_000_000
LOG = logging.getLogger("test-service")
P = "oikos-push"


class FakeSender:
    def __init__(self):
        self.calls = []
        self.results = []

    def send_data(self, data, tokens, priority, ttl):
        self.calls.append({"data": data, "tokens": list(tokens), "priority": priority, "ttl": ttl})
        return self.results.pop(0) if self.results else SendResult(success_count=len(tokens))


def make_cfg(tmp_path, **over):
    base = {
        "mqtt_broker": "broker", "mqtt_port": 1883, "topic_prefix": P, "data_dir": str(tmp_path),
        "retry_interval": 60, "max_retries": 10, "history_size": 100, "history_days": 7,
        "heartbeat_enabled": False, "heartbeat_interval": 60,
        "auto_reconnect": True, "reconnect_delay_min": 1, "reconnect_delay_max": 60,
    }
    base.update(over)
    return base


@pytest.fixture
def svc(tmp_path):
    service = OikosPush(make_cfg(tmp_path), LOG, sender=FakeSender(),
                        mqtt_client_factory=FakeMQTTClient, clock=lambda: NOW)
    connect_ok(service.handler.client)
    service.handler.client.published.clear()   # drop the on-connect history publish
    return service


def register(svc, install_id, token):
    deliver(svc.handler.client, f"{P}/tokens/{install_id}", json.dumps({"token": token}))


def v1(**over):
    payload = {"v": 1, "id": "a1", "channel": "camera", "title": "Front door", "body": "Person"}
    payload.update(over)
    return json.dumps(payload)


def notify(svc, payload, source="frigate"):
    deliver(svc.handler.client, f"{P}/notification/{source}", payload)


def history_publishes(svc):
    return [p for p in svc.handler.client.published if p.topic == f"{P}/history"]


# ---------- connect ----------

def test_on_connect_subscribes_and_republishes_history_retained(tmp_path):
    service = OikosPush(make_cfg(tmp_path), LOG, sender=FakeSender(),
                        mqtt_client_factory=FakeMQTTClient, clock=lambda: NOW)
    client = service.handler.client
    connect_ok(client)
    assert client.subscriptions == [(f"{P}/notification/+", 1), (f"{P}/tokens/+", 1)]
    pub = history_publishes(service)
    assert len(pub) == 1 and pub[0].retain is True and json.loads(pub[0].payload) == []


def test_custom_prefix_drives_every_topic(tmp_path):
    service = OikosPush(make_cfg(tmp_path, topic_prefix="/home/push/"), LOG, sender=FakeSender(),
                        mqtt_client_factory=FakeMQTTClient, clock=lambda: NOW)
    connect_ok(service.handler.client)
    assert service.handler.client.subscriptions == [
        ("home/push/notification/+", 1), ("home/push/tokens/+", 1)]
    assert service.handler.client.published[0].topic == "home/push/history"


def test_reconnect_rebuilds_token_map_dropping_installs_unregistered_while_offline(svc):
    register(svc, "inst-a", "tokA")
    register(svc, "inst-b", "tokB")
    assert svc.registry.tokens() == ["tokA", "tokB"]
    svc.handler.client.on_disconnect(svc.handler.client, None, 1)
    connect_ok(svc.handler.client)               # broker will redeliver only what is still retained
    assert svc.registry.tokens() == []
    register(svc, "inst-a", "tokA")
    assert svc.registry.tokens() == ["tokA"]


# ---------- tokens ----------

def test_token_messages_register_update_and_unregister(svc):
    register(svc, "inst-a", "old")
    register(svc, "inst-a", "new")
    assert svc.registry.tokens() == ["new"]
    deliver(svc.handler.client, f"{P}/tokens/inst-a", b"")
    assert svc.registry.tokens() == []


def test_bad_token_messages_are_ignored(svc):
    register(svc, "inst-a", "keep")
    deliver(svc.handler.client, f"{P}/tokens/inst-a", b"not json")
    deliver(svc.handler.client, f"{P}/tokens/inst-a", b'{"nope":1}')
    deliver(svc.handler.client, f"{P}/tokens/a/b", b'{"token":"x"}')       # nested topic ignored
    assert svc.registry.tokens() == ["keep"]


# ---------- sending ----------

def test_v1_notification_is_sent_with_normalized_data_priority_and_ttl(svc):
    register(svc, "inst-a", "tokA")
    notify(svc, v1(ttl=600))
    call = svc.sender.calls[0]
    assert call["tokens"] == ["tokA"]
    assert call["priority"] == "high"                  # camera channel
    assert call["ttl"] == 600
    assert call["data"]["id"] == "a1" and call["data"]["source"] == "frigate"
    assert call["data"]["sent_at"] == str(NOW) and call["data"]["v"] == "1"


def test_legacy_notification_is_normalized_before_sending(svc):
    register(svc, "inst-a", "tokA")
    legacy = {"title": "Alarme", "body": "Intrusion", "priority": "high",
              "data": {"id": 9, "channelId": "alarme",
                       "actions": '[{"btn":"Désarmer","action":"disarm"}]'}}
    notify(svc, json.dumps(legacy), source="jeedom")
    data = svc.sender.calls[0]["data"]
    assert (data["id"], data["channel"], data["severity"]) == ("9", "alarm", "warning")
    assert json.loads(data["actions"]) == [{"id": "disarm", "label": "Désarmer"}]


def test_cancel_is_sent_with_300s_ttl(svc):
    register(svc, "inst-a", "tokA")
    notify(svc, json.dumps({"v": 1, "id": "a1", "cancel": True}))
    call = svc.sender.calls[0]
    assert call["ttl"] == 300
    assert call["data"]["cancel"] == "true"


def test_normal_priority_for_info_energy(svc):
    register(svc, "inst-a", "tokA")
    notify(svc, v1(channel="energy"))
    assert svc.sender.calls[0]["priority"] == "normal"


def test_history_is_updated_and_republished_retained_after_each_alert(svc):
    register(svc, "inst-a", "tokA")
    notify(svc, v1(id="a1"))
    notify(svc, v1(id="a2", severity="warning", silent=True))
    pub = history_publishes(svc)
    assert len(pub) == 2 and all(p.retain is True and p.qos == 1 for p in pub)
    entries = json.loads(pub[-1].payload)
    assert [e["id"] for e in entries] == ["a2", "a1"]
    assert entries[0]["silent"] is True and entries[0]["sent_at"] == NOW
    assert isinstance(entries[0]["actions"], list)


def test_no_tokens_means_history_only_and_nothing_queued(svc):
    notify(svc, v1())
    assert svc.sender.calls == []
    assert svc.queue.queue == []
    assert len(svc.history) == 1
    assert len(history_publishes(svc)) == 1


def test_non_notification_topics_are_ignored(svc):
    for topic in (f"{P}/responses", f"{P}/history", f"{P}/heartbeat", f"{P}/other/x", "elsewhere/notification/x"):
        deliver(svc.handler.client, topic, v1())
    assert svc.sender.calls == [] and len(svc.history) == 0


# ---------- failures ----------

def test_invalid_token_publishes_empty_retained_payload_and_clears_map(svc):
    register(svc, "inst-a", "tokA")
    svc.sender.results = [SendResult(invalid_tokens=["tokA"])]
    notify(svc, v1())
    clears = [p for p in svc.handler.client.published if p.topic == f"{P}/tokens/inst-a"]
    assert len(clears) == 1
    assert clears[0].payload in (b"", "") and clears[0].retain is True
    assert svc.registry.tokens() == []
    assert svc.queue.queue == []                      # invalid tokens are not retried


def test_invalid_token_shared_by_two_installs_clears_both_topics(svc):
    register(svc, "inst-a", "same")
    register(svc, "inst-b", "same")
    register(svc, "inst-c", "other")
    svc.sender.results = [SendResult(success_count=1, invalid_tokens=["same"])]
    notify(svc, v1())
    assert svc.sender.calls[0]["tokens"] == ["same", "other"]     # shared token sent to once
    cleared = sorted(p.topic for p in svc.handler.client.published
                     if p.topic.startswith(f"{P}/tokens/") and p.payload in (b"", ""))
    assert cleared == [f"{P}/tokens/inst-a", f"{P}/tokens/inst-b"]
    assert all(p.retain for p in svc.handler.client.published if p.topic.startswith(f"{P}/tokens/"))
    assert svc.registry.tokens() == ["other"]


def test_transient_failure_queues_message_for_failed_tokens_only(svc):
    register(svc, "inst-a", "tokA")
    svc.sender.results = [SendResult(transient_tokens=["tokA"])]
    notify(svc, v1(ttl=1200))
    item = svc.queue.queue[0]["notification"]
    assert item["tokens"] == ["tokA"] and item["ttl"] == 1200
    assert item["priority"] == "high" and item["data"]["id"] == "a1" and item["id"] == "a1"


def test_mixed_failures_queue_only_transient_tokens(svc):
    for install, token in (("a", "tokA"), ("b", "tokB"), ("c", "tokC")):
        register(svc, install, token)
    svc.sender.results = [
        SendResult(success_count=1, invalid_tokens=["tokB"], transient_tokens=["tokC"]),
        SendResult(success_count=1),
    ]
    notify(svc, v1())
    assert svc.sender.calls[0]["tokens"] == ["tokA", "tokB", "tokC"]
    assert len(svc.queue.queue) == 1
    assert svc.queue.queue[0]["notification"]["tokens"] == ["tokC"]       # tokA already succeeded
    assert svc.registry.installs_for_token("tokB") == []

    svc.queue.run_once(svc._retry_send)
    assert svc.sender.calls[1]["tokens"] == ["tokC"]                      # never re-sent to tokA
    assert svc.queue.queue == []


def test_retry_that_still_fails_keeps_only_the_failing_tokens(svc):
    register(svc, "a", "tokA")
    register(svc, "b", "tokB")
    svc.sender.results = [
        SendResult(transient_tokens=["tokA", "tokB"]),
        SendResult(success_count=1, transient_tokens=["tokB"]),
    ]
    notify(svc, v1())
    svc.queue.run_once(svc._retry_send)
    assert svc.queue.queue[0]["retry_count"] == 1
    assert svc.queue.queue[0]["notification"]["tokens"] == ["tokB"]


def test_retry_skips_tokens_no_longer_registered(svc):
    register(svc, "c", "tokC")
    svc.sender.results = [SendResult(transient_tokens=["tokC"])]
    notify(svc, v1())
    deliver(svc.handler.client, f"{P}/tokens/c", b"")                     # unregistered meanwhile
    svc.queue.run_once(svc._retry_send)
    assert len(svc.sender.calls) == 1                                     # no second FCM call
    assert svc.queue.queue == []                                          # item dropped, not retried


# ---------- garbage ----------

@pytest.mark.parametrize("raw", [
    b"\xff\xfe\x00", b"not json", b"", b"[1, 2]", b"42", b'"text"', b"null", b"true",
    b'{"v": 2, "id": "x", "title": "T"}',
    b'{"v": 1, "title": "no id"}',
    b'{"v": 1, "id": "x"}',
])
def test_garbage_payloads_are_dropped_without_side_effects(svc, raw):
    register(svc, "inst-a", "tokA")
    svc.on_message(f"{P}/notification/x", raw)           # must not raise
    deliver(svc.handler.client, f"{P}/notification/x", raw)
    assert svc.sender.calls == []
    assert len(svc.history) == 0
    assert history_publishes(svc) == []
    assert svc.queue.queue == []


# ---------- lifecycle / entry point ----------

def test_stop_stops_worker_and_disconnects(svc):
    svc.stop()
    assert svc.handler.client.disconnected is True


def test_main_is_importable():
    import main

    assert callable(main.main)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd $REPO && venv/bin/pytest tests/test_mqtt_handler.py tests/test_service.py -v`
Expected: collection errors `ModuleNotFoundError: No module named 'mqtt_handler'` / `'oikos_push_service'`.

- [ ] **Step 3: Write minimal implementation**

Create `mqtt_handler.py`:

```python
"""paho-mqtt wrapper: connect with backoff, subscribe on connect, publish, heartbeat."""
from __future__ import annotations

import json
import logging
import threading
import time
from typing import Any, Callable

import paho.mqtt.client as mqtt

CLIENT_ID = "oikos-push"


class MQTTHandler:
    def __init__(
        self,
        config: dict[str, Any],
        logger: logging.Logger,
        message_callback: Callable[[str, bytes], None],
        on_connected: Callable[[], None] | None = None,
        client_factory: Callable[..., Any] | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ):
        self.config = config
        self.logger = logger
        self.message_callback = message_callback
        self.on_connected = on_connected
        self._sleep = sleep
        self.is_connected = False
        self._heartbeat_thread: threading.Thread | None = None
        self._heartbeat_stop = threading.Event()
        self._delay_min = config.get("reconnect_delay_min", 1)
        self._delay_max = config.get("reconnect_delay_max", 60)
        self._delay = self._delay_min

        factory = client_factory or mqtt.Client
        # Fixed id + persistent session: QoS 1 producers keep their messages while we restart.
        self.client = factory(client_id=CLIENT_ID, clean_session=False)
        if config.get("mqtt_username"):
            self.client.username_pw_set(config["mqtt_username"], config.get("mqtt_password"))
        if config.get("auto_reconnect", True):
            self.client.reconnect_delay_set(min_delay=self._delay_min, max_delay=self._delay_max)
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.client.on_disconnect = self._on_disconnect

    # ----- callbacks -----

    def _on_connect(self, client, userdata, flags, rc):
        if rc != 0:
            self.logger.error("MQTT connect failed, return code %s", rc)
            self.is_connected = False
            return
        self.logger.info("Connected to MQTT broker")
        self.is_connected = True
        self._delay = self._delay_min
        for topic in self.config.get("topics", []):
            client.subscribe(topic, qos=1)
            self.logger.info("Subscribed to %s", topic)
        if self.on_connected:
            try:
                self.on_connected()
            except Exception as exc:
                self.logger.error("on_connected hook failed: %s", exc)
        if self.config.get("heartbeat_enabled", True):
            self._start_heartbeat()

    def _on_disconnect(self, client, userdata, rc):
        self.is_connected = False
        self._stop_heartbeat()
        if rc != 0:
            self.logger.warning("Unexpected MQTT disconnection (rc=%s); will reconnect", rc)

    def _on_message(self, client, userdata, msg):
        try:
            self.message_callback(msg.topic, msg.payload)
        except Exception as exc:
            self.logger.error("Error handling message on %s: %s", msg.topic, exc)

    # ----- operations -----

    def publish(self, topic: str, payload: str | bytes, qos: int = 1, retain: bool = False):
        return self.client.publish(topic, payload, qos=qos, retain=retain)

    def connect(self) -> None:
        broker = self.config.get("mqtt_broker")
        port = self.config.get("mqtt_port", 1883)
        if not broker:
            raise ValueError("mqtt_broker is required")
        while True:
            try:
                self.logger.info("Connecting to MQTT broker at %s:%s", broker, port)
                self.client.connect(broker, port, 60)
                return
            except Exception as exc:
                if not self.config.get("auto_reconnect", True):
                    raise
                self.logger.error("MQTT connect failed: %s. Retrying in %ss", exc, self._delay)
                self._sleep(self._delay)
                self._delay = min(self._delay * 2, self._delay_max)

    def start_loop(self) -> None:
        self.client.loop_forever(retry_first_connection=True)

    def disconnect(self) -> None:
        self._stop_heartbeat()
        self.client.disconnect()

    # ----- heartbeat -----

    def heartbeat_once(self) -> bool:
        """Publish one (non-retained) heartbeat. Returns False when not connected."""
        if not self.is_connected:
            return False
        topic = self.config.get("heartbeat_topic", "oikos-push/heartbeat")
        info = self.publish(topic, json.dumps({"timestamp": int(time.time())}), qos=1, retain=False)
        try:
            info.wait_for_publish(timeout=5)
        except Exception:
            pass
        return True

    def _heartbeat_worker(self) -> None:
        interval = self.config.get("heartbeat_interval", 60)
        while not self._heartbeat_stop.wait(timeout=interval):
            try:
                self.heartbeat_once()
            except Exception as exc:
                self.logger.error("Heartbeat failed: %s", exc)

    def _start_heartbeat(self) -> None:
        if self._heartbeat_thread and self._heartbeat_thread.is_alive():
            return
        self._heartbeat_stop.clear()
        self._heartbeat_thread = threading.Thread(target=self._heartbeat_worker, daemon=True)
        self._heartbeat_thread.start()

    def _stop_heartbeat(self) -> None:
        if self._heartbeat_thread and self._heartbeat_thread.is_alive():
            self._heartbeat_stop.set()
            if self._heartbeat_thread is not threading.current_thread():
                self._heartbeat_thread.join(timeout=5)
```

Create `oikos_push_service.py`:

```python
"""OikosPush: MQTT producer payloads -> normalized FCM data messages + retained history."""
from __future__ import annotations

import json
import logging
import time
from pathlib import Path
from typing import Any, Callable

from fcm_sender import FCMSender, android_priority, ttl_seconds
from history import History
from mqtt_handler import MQTTHandler
from normalize import Alert, Rejected, normalize, to_fcm_data
from notification_queue import NotificationQueue
from tokens import TokenRegistry


def setup_logger() -> logging.Logger:
    logger = logging.getLogger("OikosPush")
    logger.setLevel(logging.INFO)
    if not logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(asctime)s - %(name)s - %(levelname)s - %(message)s"))
        logger.addHandler(handler)
    return logger


class OikosPush:
    def __init__(
        self,
        cfg: dict[str, Any],
        logger: logging.Logger | None = None,
        *,
        sender: Any = None,
        queue: NotificationQueue | None = None,
        history: History | None = None,
        registry: TokenRegistry | None = None,
        mqtt_client_factory: Callable[..., Any] | None = None,
        clock: Callable[[], float] = time.time,
    ):
        self.cfg = cfg
        self.logger = logger or setup_logger()
        self.clock = clock
        self.prefix = str(cfg.get("topic_prefix", "oikos-push")).strip("/")
        self._notification_prefix = f"{self.prefix}/notification/"
        self._tokens_prefix = f"{self.prefix}/tokens/"
        self._history_topic = f"{self.prefix}/history"

        data_dir = Path(cfg.get("data_dir", "data"))
        data_dir.mkdir(parents=True, exist_ok=True)
        self.registry = registry or TokenRegistry(self.logger)
        self.history = history or History(
            data_dir / "history.json",
            size=int(cfg.get("history_size", 100)),
            days=int(cfg.get("history_days", 7)),
            clock=clock,
            logger=self.logger,
        )
        self.queue = queue or NotificationQueue(
            data_dir / "notification_queue.json",
            retry_interval=int(cfg.get("retry_interval", 60)),
            max_retries=int(cfg.get("max_retries", 10)),
            logger=self.logger,
        )
        self.sender = sender or FCMSender(logger=self.logger)
        self.handler = MQTTHandler(
            {
                **cfg,
                "topics": [f"{self._notification_prefix}+", f"{self._tokens_prefix}+"],
                "heartbeat_topic": f"{self.prefix}/heartbeat",
            },
            self.logger,
            self.on_message,
            on_connected=self.on_connected,
            client_factory=mqtt_client_factory,
        )

    # ----- MQTT events -----

    def on_connected(self) -> None:
        """Rebuild the token map from the retained messages and refresh the history topic."""
        self.registry.clear()
        self.history.trim()
        self._publish_history()

    def on_message(self, topic: str, payload: bytes) -> None:
        if topic.startswith(self._tokens_prefix):
            install_id = topic[len(self._tokens_prefix):]
            if not install_id or "/" in install_id:
                self.logger.warning("Ignoring token message on unexpected topic %s", topic)
                return
            status = self.registry.handle(install_id, payload)
            self.logger.info("Token message for %s: %s", install_id, status)
        elif topic.startswith(self._notification_prefix):
            self._handle_notification(topic, payload)
        else:
            self.logger.debug("Ignoring message on %s", topic)

    def _handle_notification(self, topic: str, payload: bytes) -> None:
        try:
            parsed = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, ValueError) as exc:
            self.logger.warning("Dropping non-JSON payload on %s: %s", topic, exc)
            return
        alert = normalize(topic, parsed, now=self.clock())
        if isinstance(alert, Rejected):
            self.logger.warning("Dropping payload on %s: %s", topic, alert.reason)
            return
        self.dispatch(alert)

    # ----- sending -----

    def dispatch(self, alert: Alert) -> None:
        self.history.apply(alert)
        self._publish_history()

        tokens = self.registry.tokens()
        if not tokens:
            self.logger.info("No registered tokens; alert %s kept in history only", alert.id)
            return

        notification = {
            "id": alert.id,
            "data": to_fcm_data(alert),
            "tokens": tokens,
            "priority": android_priority(alert),
            "ttl": ttl_seconds(alert),
        }
        result = self.sender.send_data(
            notification["data"], tokens, notification["priority"], notification["ttl"])
        self._cleanup_invalid(result.invalid_tokens)
        if result.transient_tokens:
            self.queue.add_notification({**notification, "tokens": list(result.transient_tokens)})

    def _retry_send(self, notification: dict[str, Any]) -> bool:
        """Queue callback. True = finished with this item (sent, or nothing left to send to)."""
        live = set(self.registry.tokens())
        tokens = [t for t in notification["tokens"] if t in live]
        if not tokens:
            return True
        result = self.sender.send_data(
            notification["data"], tokens, notification["priority"], notification["ttl"])
        self._cleanup_invalid(result.invalid_tokens)
        if result.transient_tokens:
            notification["tokens"] = list(result.transient_tokens)   # only the still-failing ones
            return False
        return True

    def _cleanup_invalid(self, tokens: list[str]) -> None:
        """Clear every install that uses an invalid token, on the broker and in the map."""
        for token in tokens:
            for install_id in self.registry.installs_for_token(token):
                self.handler.publish(f"{self._tokens_prefix}{install_id}", b"", qos=1, retain=True)
                self.registry.remove(install_id)
                self.logger.warning("Cleared invalid token of install %s", install_id)

    def _publish_history(self) -> None:
        self.handler.publish(self._history_topic, self.history.to_json(), qos=1, retain=True)

    # ----- lifecycle -----

    def start(self) -> None:
        self.logger.info("Starting OikosPush (prefix %s)", self.prefix)
        self.queue.start_retry_worker(self._retry_send)
        try:
            self.handler.connect()
            self.handler.start_loop()
        except KeyboardInterrupt:
            self.logger.info("Shutting down...")
        finally:
            self.stop()

    def stop(self) -> None:
        self.queue.stop_retry_worker()
        self.handler.disconnect()
```

Create `main.py`:

```python
#!/usr/bin/env python3
"""Entry point for the OikosPush service."""
import sys

import config
from fcm_sender import init_firebase
from oikos_push_service import OikosPush, setup_logger


def main() -> None:
    logger = setup_logger()
    try:
        init_firebase(config.FIREBASE_CREDENTIALS)
        OikosPush(config.as_dict(), logger).start()
    except KeyboardInterrupt:
        sys.exit(0)
    except Exception as exc:
        logger.error("Fatal error: %s", exc)
        sys.exit(1)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd $REPO && venv/bin/pytest tests/test_mqtt_handler.py tests/test_service.py -v` then the whole suite `venv/bin/pytest -q`.
Expected: all pass, 0 failed (whole suite roughly 150 cases). If `test_main_is_importable` fails because `firebase_admin` is missing, run `venv/bin/pip install -r requirements-dev.txt`.

- [ ] **Step 5: Commit**

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/OikosPush
git add mqtt_handler.py oikos_push_service.py main.py tests/fake_mqtt.py tests/test_mqtt_handler.py tests/test_service.py
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat: wire MQTT handler, token map, history and FCM sending into the service" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Docker, README contract, smoke test, and publishing (ask first)

**Files:**
- Create: `$REPO/Dockerfile`, `$REPO/docker-compose.yml`, `$REPO/entrypoint.sh`, `$REPO/.dockerignore`, `$REPO/.env.example`, `$REPO/README.md`
- Test: `$REPO/tests/test_repo_contract.py`

**Interfaces:**
Consumes: the env var names read in `config.py` (Task 1), the topics and mapping tables (Tasks 2 and 6).
Produces: a deployable image (`python:3.12-slim`, non-root user, `/app/data` volume for `history.json` and `notification_queue.json`, `credential.json` mounted read-only) and README sections documenting the §2 contract.

- [ ] **Step 1: Write the failing test**

Create `tests/test_repo_contract.py`:

```python
import re
from pathlib import Path

from normalize import CHANNELS, LEGACY_CHANNELS

ROOT = Path(__file__).resolve().parent.parent


def read(name):
    return (ROOT / name).read_text(encoding="utf-8")


def test_readme_documents_every_topic():
    readme = read("README.md")
    for topic in (
        "oikos-push/notification/<source>", "oikos-push/tokens/<installId>",
        "oikos-push/responses", "oikos-push/history", "oikos-push/heartbeat",
    ):
        assert topic in readme, topic


def test_readme_documents_channels_and_legacy_mapping():
    readme = read("README.md")
    for channel in CHANNELS:
        assert f"`{channel}`" in readme, channel
    for legacy in LEGACY_CHANNELS:
        assert f"`{legacy}`" in readme, legacy


def test_readme_documents_every_env_var_of_config():
    readme = read("README.md")
    names = set(re.findall(r'getenv\("([A-Z_]+)"', read("config.py")))
    names |= set(re.findall(r'_bool\("([A-Z_]+)"', read("config.py")))
    assert {"MQTT_BROKER", "TOPIC_PREFIX", "HISTORY_SIZE", "HISTORY_DAYS", "RETRY_INTERVAL",
            "MAX_RETRIES", "HEARTBEAT_INTERVAL", "FIREBASE_CREDENTIALS"} <= names
    for name in names:
        assert name in readme, name


def test_readme_documents_fcm_data_keys_and_limits():
    readme = read("README.md")
    for key in ("sent_at", "image_url", "video_url", "entity_id", "silent", "cancel", "4 KB", "43200", "300"):
        assert key in readme, key


def test_secrets_and_runtime_data_are_ignored():
    ignored = read(".gitignore").splitlines()
    for entry in ("credential.json", "data/", "venv/"):
        assert entry in ignored, entry
    assert "credential.json" in read(".dockerignore")


def test_dockerfile_uses_python_312_and_non_root():
    docker = read("Dockerfile")
    assert docker.splitlines()[0].startswith("FROM python:3.12")
    assert "USER appuser" in docker


def test_compose_mounts_credentials_read_only_and_data_dir():
    compose = read("docker-compose.yml")
    assert "./credential.json:/app/credential.json:ro" in compose
    assert "./data:/app/data" in compose


def test_licence_is_mit_by_gekkotron():
    licence = read("LICENSE")
    assert licence.startswith("MIT License")
    assert "Gekkotron" in licence
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd $REPO && venv/bin/pytest tests/test_repo_contract.py -v`
Expected: failures `FileNotFoundError: ... README.md` (and `.dockerignore`, `Dockerfile`, `docker-compose.yml`); `test_licence_is_mit_by_gekkotron` and `test_secrets_and_runtime_data_are_ignored` fail only on the missing `.dockerignore` read.

- [ ] **Step 3: Write minimal implementation**

Create `Dockerfile`:

```dockerfile
FROM python:3.12-slim

WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY *.py ./
COPY entrypoint.sh ./
RUN chmod +x entrypoint.sh

# credential.json is mounted at runtime (see docker-compose.yml); history and queue live in /app/data
RUN useradd -m -u 1000 appuser && \
    mkdir -p /app/data && \
    chown -R appuser:appuser /app

USER appuser

CMD ["./entrypoint.sh"]
```

Create `entrypoint.sh`:

```sh
#!/bin/sh
echo "Waiting for the MQTT broker to be ready..."
sleep 5

echo "Starting OikosPush with auto-restart..."
while true; do
    echo "[$(date)] Starting service..."
    python main.py
    EXIT_CODE=$?

    # Exit gracefully on normal stop / SIGINT / SIGTERM
    if [ $EXIT_CODE -eq 0 ] || [ $EXIT_CODE -eq 130 ] || [ $EXIT_CODE -eq 143 ]; then
        echo "[$(date)] Service stopped gracefully"
        exit 0
    fi

    echo "[$(date)] Service crashed with exit code $EXIT_CODE. Restarting in 5 seconds..."
    sleep 5
done
```

Create `.dockerignore`:

```
.git
.gitignore
venv/
__pycache__/
*.py[cod]
.pytest_cache/
tests/
data/
credential.json
.env
*.md
Dockerfile
docker-compose.yml
```

Create `.env.example`:

```
# MQTT
MQTT_BROKER=mosquitto
MQTT_PORT=1883
MQTT_USERNAME=
MQTT_PASSWORD=
TOPIC_PREFIX=oikos-push

# Firebase: service account of the Oikos project (oikos-b8268), mounted as ./credential.json
FIREBASE_CREDENTIALS=credential.json

# Retry queue
RETRY_INTERVAL=60
MAX_RETRIES=10

# History (catch-up buffer)
HISTORY_SIZE=100
HISTORY_DAYS=7

# Heartbeat
HEARTBEAT_ENABLED=True
HEARTBEAT_INTERVAL=60
```

Create `docker-compose.yml`:

```yaml
services:
  oikos-push:
    build: .
    container_name: oikos-push
    restart: unless-stopped
    dns:
      - 8.8.8.8
      - 1.1.1.1
    environment:
      - MQTT_BROKER=${MQTT_BROKER:-localhost}
      - MQTT_PORT=${MQTT_PORT:-1883}
      - MQTT_USERNAME=${MQTT_USERNAME:-}
      - MQTT_PASSWORD=${MQTT_PASSWORD:-}
      - TOPIC_PREFIX=${TOPIC_PREFIX:-oikos-push}
      - FIREBASE_CREDENTIALS=${FIREBASE_CREDENTIALS:-credential.json}
      - RETRY_INTERVAL=${RETRY_INTERVAL:-60}
      - MAX_RETRIES=${MAX_RETRIES:-10}
      - HISTORY_SIZE=${HISTORY_SIZE:-100}
      - HISTORY_DAYS=${HISTORY_DAYS:-7}
      - HEARTBEAT_ENABLED=${HEARTBEAT_ENABLED:-True}
      - HEARTBEAT_INTERVAL=${HEARTBEAT_INTERVAL:-60}
      - DATA_DIR=${DATA_DIR:-data}
    volumes:
      # Service-account key of the Oikos Firebase project (never committed)
      - ./credential.json:/app/credential.json:ro
      # history.json and notification_queue.json (must be writable by uid 1000)
      - ./data:/app/data
    networks:
      - home-automation_homeautomation

networks:
  home-automation_homeautomation:
    external: true   # same network as the Mosquitto broker; adjust to your setup
```

Create `README.md` (outer fence is four backticks because the file contains code fences):

````markdown
# OikosPush

MQTT to Firebase Cloud Messaging bridge for the Oikos Android app. Producers (Jeedom, Frigate, scripts...) publish alerts on MQTT; OikosPush normalizes them, sends a data-only FCM message to every registered Oikos install, and keeps a retained catch-up history for alerts FCM never delivered.

It mirrors Mqtt2FCMPush but has no Firestore or admin code, and it signs in to the Oikos Firebase project (`oikos-b8268`) with that project's service account. Mqtt2FCMPush and DomoticApp keep running unchanged.

Author: Gekkotron. Licence: MIT.

## Topics

The prefix `oikos-push` is configurable with `TOPIC_PREFIX`.

| Topic | Direction | Retained | Content |
|---|---|---|---|
| `oikos-push/notification/<source>` | producer -> OikosPush | no | v1 or legacy payload |
| `oikos-push/tokens/<installId>` | Oikos -> OikosPush | yes | `{token, device_name, lang, updated_at}`; empty payload = unregister |
| `oikos-push/responses` | Oikos -> producer | no | `{id, action, install_id, ts}` |
| `oikos-push/history` | OikosPush -> Oikos | yes | JSON array of normalized alerts |
| `oikos-push/heartbeat` | OikosPush | no | `{"timestamp": <unix s>}` |

`responses` sits outside `notification/#` on purpose (Mqtt2FCMPush hears its own responses because of that overlap). OikosPush never subscribes to or publishes on `oikos-push/responses`: producers subscribe to it themselves to receive the action buttons users tap.

`<source>` is a single topic segment (for example `frigate` or `jeedom`); it is copied to the `source` field.

## v1 payload

```json
{ "v": 1,
  "id": "frigate-front-1696",
  "channel": "camera",
  "severity": "critical",
  "title": "Front door", "body": "Person detected",
  "image_url": "http://192.168.1.20:5000/api/events/1696/snapshot.jpg",
  "video_url": "http://192.168.1.20:5000/api/events/1696/clip.mp4",
  "entity_id": "binary_sensor.front_door",
  "actions": [ { "id": "disarm", "label": "Disarm" } ],
  "silent": false, "ttl": 43200 }
```

- Required: `v` (the integer 1), `id`, `title`. Anything else on `v` is rejected.
- `channel` is one of `alarm`, `security`, `camera`, `energy`, `climate`, `system`, `misc`. An unknown or missing value becomes `misc`.
  - `alarm` is the alarm system itself (armed, disarmed, triggered, siren).
  - `security` is individual sensors outside the alarm (openings, motion, leaks, smoke).
- `severity` is `info`, `warning` or `critical` (default `info`).
- Keep `actions` to 3 or fewer; the app shows at most 3.
- The same `id` replaces the earlier alert, in the history and in the notification shade.
- `ttl` is the FCM time to live in seconds (default 43200).
- Cancel: `{ "v": 1, "id": "frigate-front-1696", "cancel": true }` dismisses the notification and marks the alert resolved. A cancel is sent with a TTL of 300 s.

Rejected payloads (v1 with no `id`, or no `title` and no `cancel`, non-JSON, not an object) are logged and dropped.

## Legacy payload (no `v`)

Mqtt2FCMPush's format, accepted as is so producers can move one at a time:
`{title, body, data:{id, channelId, image_url, video_url, actions, silent}, priority, ttl}`, where `actions` is a JSON string `[{"btn","action"}]`.

| Legacy | Normalized |
|---|---|
| `channelId` `alarme` / `nvr` / `pv` / `surveillance` / `Supervisor` / other | `alarm` / `camera` / `energy` / `climate` / `system` / `misc` |
| `data.id` (number or string) | `id` as a string; if missing, `<source>-<sha1(title+body)[:8]>` |
| `actions` `[{btn, action}]` | `[{label: btn, id: action}]` |
| empty or missing `title` | `cancel: true` |
| `priority: "high"` | `severity: "warning"`, otherwise `info` |
| other `data.*` keys | dropped |

## FCM data map (OikosPush -> Oikos)

FCM data values are strings: `v="1"`, `id`, `channel`, `severity`, `title`, `body`, `image_url`, `video_url`, `entity_id`, `actions` (a JSON array string `[{id,label}]`, `"[]"` when there are none), `silent` (`"true"` / `"false"`), `cancel`, `sent_at` (unix seconds), `source`.

- Absent optional fields (`image_url`, `video_url`, `entity_id`) are left out, and `cancel` is present (as `"true"`) only on cancels.
- The map must stay under FCM's 4 KB limit (UTF-8 bytes of all keys and values). If it does not, `body` is truncated and ends with the single character `…`.
- Messages are data-only. Android priority is `high` when severity is `warning` or `critical`, or the channel is `alarm`, `security` or `camera`; `normal` otherwise. TTL is `ttl` (default 43200 s), 300 s for a cancel.

## Tokens

Oikos publishes a retained message on `oikos-push/tokens/<installId>`:

```json
{ "token": "<FCM token>", "device_name": "Pixel 8", "lang": "fr", "updated_at": 1700000000 }
```

- The token map is rebuilt from the retained messages on every (re)connect.
- Only `token` is required; unknown fields are ignored. Bad JSON or a missing `token` is logged and ignored.
- An empty payload removes the entry. When two installs share a token, it is sent to only once.
- When FCM answers `UNREGISTERED` or `INVALID_ARGUMENT` for a token, OikosPush publishes an empty retained payload to every install topic that used it, clearing it on the broker and in memory.
- Any other FCM failure is queued for the failed tokens only, retried every `RETRY_INTERVAL` seconds, up to `MAX_RETRIES` attempts or TTL expiry. The queue survives restarts (`data/notification_queue.json`).
- With no registered token the alert is logged and kept in the history only; it is not queued.

## History (catch-up buffer)

`oikos-push/history` is a retained JSON array of normalized alerts, newest first, with the same keys as the FCM data map but typed JSON values (`v` and `sent_at` numbers, `silent` and `cancel` booleans, `actions` an array).

- A new `id` is prepended; an existing `id` is replaced and moved to the front (which clears its resolved state).
- A cancel sets `cancel: true` on the stored alert in place, or is stored on its own when the `id` is unknown.
- Trimmed to `HISTORY_SIZE` entries and `HISTORY_DAYS` days by `sent_at`; persisted to `data/history.json`, loaded on start.
- The whole list is republished retained after every change and on every broker (re)connect.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `MQTT_BROKER` | `localhost` | Broker host |
| `MQTT_PORT` | `1883` | Broker port |
| `MQTT_USERNAME` / `MQTT_PASSWORD` | empty | Optional broker credentials |
| `TOPIC_PREFIX` | `oikos-push` | Topic prefix |
| `FIREBASE_CREDENTIALS` | `credential.json` | Service-account key of the Oikos Firebase project |
| `RETRY_INTERVAL` | `60` | Seconds between retries of a failed send |
| `MAX_RETRIES` | `10` | Attempts before a queued message is discarded |
| `HISTORY_SIZE` | `100` | Maximum alerts kept |
| `HISTORY_DAYS` | `7` | Maximum age of an alert, by `sent_at` |
| `HEARTBEAT_ENABLED` | `True` | Publish the heartbeat |
| `HEARTBEAT_INTERVAL` | `60` | Seconds between heartbeats |
| `AUTO_RECONNECT` | `True` | Retry the broker connection with backoff |
| `RECONNECT_DELAY_MIN` / `RECONNECT_DELAY_MAX` | `1` / `60` | Backoff bounds in seconds |
| `DATA_DIR` | `data` | Directory for `history.json` and `notification_queue.json` |

## Deploy

1. In the Firebase console for project `oikos-b8268`: Project settings, Service accounts, Generate new private key. Save it as `credential.json` next to `docker-compose.yml`. It is git-ignored; never commit it.
2. `cp .env.example .env` and set `MQTT_BROKER` (and credentials if the broker needs them).
3. `mkdir -p data && sudo chown 1000:1000 data` (the container runs as uid 1000).
4. Adjust the external network name in `docker-compose.yml` to the one your Mosquitto uses.
5. `docker compose up -d --build`, then `docker compose logs -f oikos-push`.

## Try it

```bash
# watch the catch-up history
mosquitto_sub -h <broker> -v -t 'oikos-push/history'

# v1 alert
mosquitto_pub -h <broker> -q 1 -t 'oikos-push/notification/test' -m '{"v":1,"id":"smoke-1","channel":"camera","severity":"warning","title":"Front door","body":"Person detected","actions":[{"id":"disarm","label":"Disarm"}]}'

# legacy alert (Mqtt2FCMPush format)
mosquitto_pub -h <broker> -q 1 -t 'oikos-push/notification/jeedom' -m '{"title":"Alarme","body":"Intrusion salon","priority":"high","data":{"id":42,"channelId":"alarme"}}'

# cancel
mosquitto_pub -h <broker> -q 1 -t 'oikos-push/notification/test' -m '{"v":1,"id":"smoke-1","cancel":true}'
```

## Development

```bash
python3.12 -m venv venv
venv/bin/pip install -r requirements-dev.txt
venv/bin/pytest -q
```

MQTT and FCM are stubbed in the tests; no broker or credentials are needed.
````

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd $REPO && venv/bin/pytest -q`
Expected: the whole suite passes, 0 failed. Then validate the compose file and image when Docker is running (skip if the daemon is down):
`docker compose config -q && docker build -t oikos-push:dev .` -> both succeed with no error output.

- [ ] **Step 5: Commit**

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/OikosPush
git add Dockerfile docker-compose.yml entrypoint.sh .dockerignore .env.example README.md tests/test_repo_contract.py
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat: Docker deployment and README contract for the OikosPush topics and payloads" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 6: Manual smoke test (needs the real service-account key and the home broker; this is spec §10 step 1)**

Skip this step if `credential.json` for project `oikos-b8268` is not available yet; report that it was skipped. Never print or paste the key.

1. Deploy as described in the README, then `docker compose logs -f oikos-push` and expect `Connected to MQTT broker` and `Subscribed to oikos-push/notification/+` and `.../tokens/+`.
2. In a second terminal: `mosquitto_sub -h <broker> -v -t 'oikos-push/history' -t 'oikos-push/heartbeat'` and expect the retained `[]` (or the persisted history) at once, and a heartbeat every 60 s.
3. Publish the v1 alert and the legacy alert from the README "Try it" section. Expected on `oikos-push/history`: a new retained array with `smoke-1` (typed values: `"silent":false`, `"sent_at":<number>`, `"actions":[{...}]`) and then `42` at index 0 with `"channel":"alarm"`, `"severity":"warning"`. Logs: `No registered tokens; alert ... kept in history only`.
4. Publish the cancel. Expected: `smoke-1` keeps its position in the array with `"cancel":true`.
5. Register a deliberately fake token: `mosquitto_pub -h <broker> -q 1 -r -t 'oikos-push/tokens/smoke-install' -m '{"token":"not-a-real-token"}'`, with `mosquitto_sub -h <broker> -v -t 'oikos-push/tokens/+'` open, then publish the v1 alert again. Expected: FCM answers `INVALID_ARGUMENT`/`UNREGISTERED`, the log shows `Cleared invalid token of install smoke-install`, and the retained message on `oikos-push/tokens/smoke-install` is now empty (a fresh `mosquitto_sub -t 'oikos-push/tokens/+'` shows nothing).
6. Restart the container (`docker compose restart oikos-push`) and confirm the history topic is republished with the same entries (persistence).

- [ ] **Step 7: Run the full suite one last time**

Run: `cd $REPO && venv/bin/pytest -q && git status --short`
Expected: all tests pass; `git status --short` prints nothing (clean tree); `git log --oneline` shows 7 commits ending with the Docker/README one.

- [ ] **Step 8: ASK THE USER before creating or pushing to GitHub**

**Do not run any of the commands below until the user explicitly confirms.** Ask: "OikosPush is committed locally on `main` (7 commits, tests green). Create the private GitHub repo `Gekkotron/OikosPush` and push?" If the user says no or does not answer, stop here and report that the repo is local only.

If confirmed (the plain `github.com` host is the work account; use the Gekkotron identity and the SSH alias):

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/OikosPush
gh auth switch --user Gekkotron
gh repo create Gekkotron/OikosPush --private --description "MQTT to FCM push bridge for the Oikos app"
git remote add origin git@github.com-gekkotron:Gekkotron/OikosPush.git
git push -u origin main
gh auth status   # confirm which account is active afterwards; switch back if it was a different one before
```

Expected: the push succeeds over the `github.com-gekkotron` alias; `git log origin/main --oneline` matches local `main`. Confirm on GitHub that `credential.json` is NOT in the tree (`git ls-files | grep -c credential` prints `0`).

---

## Self-Review

### Spec coverage (§2, §3, §9 OikosPush tests, §10 step 1)

| Requirement | Task |
|---|---|
| §2.1 topic table (all five topics, prefix configurable, retained flags, `responses` outside `notification/#`) | Task 1 (`TOPIC_PREFIX`), Task 6 (subscriptions, `test_on_connect_subscribes_and_republishes_history_retained`, `test_custom_prefix_drives_every_topic`, `test_non_notification_topics_are_ignored`, heartbeat non-retained), Task 7 (README table) |
| §2.2 v1 payload: required fields, channel/severity defaults, same id replaces, cancel | Task 2 (`test_v1_*`, rejections), Task 4 (replace), Task 6 (`test_cancel_is_sent_with_300s_ttl`) |
| §2.3 legacy mapping table, id fallback, cancel on empty title, priority, dropped keys | Task 2 (`test_legacy_*`), Task 6 (`test_legacy_notification_is_normalized_before_sending`) |
| §2.4 FCM data map, strings, absent optionals, 4 KB truncation | Task 2 (`test_to_fcm_data_*`, `test_truncation_*`) |
| §3.1 layout and config env (`MQTT_*`, `TOPIC_PREFIX`, `FIREBASE_CREDENTIALS`, `RETRY_INTERVAL`, `MAX_RETRIES`, `HISTORY_SIZE`, `HISTORY_DAYS`, `HEARTBEAT_INTERVAL`), Python 3.12, MIT, Gekkotron, README as contract | Task 1, Task 7 (`test_readme_*`, `test_dockerfile_uses_python_312_and_non_root`, `test_licence_is_mit_by_gekkotron`) |
| §3.1 `mqtt_handler.py`: connect, reconnect with backoff, subscribe, heartbeat | Task 6 (`test_mqtt_handler.py`) |
| §3.1 `normalize.py` pure `normalize` / `to_fcm_data` | Task 2 |
| §3.1 `tokens.py` | Task 3 |
| §3.1 `fcm_sender.py` multicast, failed tokens per error kind | Task 5 |
| §3.1 `notification_queue.py` persistent retry queue | Task 5 |
| §3.1 `history.py` | Task 4 |
| §3.2 tokens: rebuild on connect, only `token` required, empty = remove, bad JSON ignored, duplicates sent once | Task 3, Task 6 (`test_reconnect_rebuilds_...`, `test_invalid_token_shared_by_two_installs_clears_both_topics`) |
| §3.3 sending: normalize/reject, history first, no tokens -> stop, data-only multicast, priority rule, TTL and cancel TTL 300 | Task 2, Task 5 (`test_android_priority`, `test_ttl_*`), Task 6 |
| §3.3 failures: invalid -> empty retained publish; other -> queue, `RETRY_INTERVAL`, `MAX_RETRIES`, TTL expiry | Task 5 (classification, queue), Task 6 (`test_invalid_token_*`, `test_transient_failure_*`, `test_mixed_failures_*`, `test_retry_*`) |
| §3.4 service does not touch `responses` | Task 6 (`test_non_notification_topics_are_ignored`; no subscription to it) |
| §3.5 history: prepend, replace-and-move, cancel (in place / alone), size and day caps, persistence, retained republish with typed values | Task 4, Task 6 (`test_history_is_updated_and_republished_retained_after_each_alert`) |
| §9 OikosPush tests: normalize (v1 fields, legacy table, rejections, truncation), token map, priority, invalid-token cleanup, retry queue, history | Tasks 2, 3, 5, 4, 6 |
| §10 step 1: create and deploy OikosPush with the `oikos-b8268` service account | Task 7 (Docker, compose, README deploy, manual smoke step, ask-first GitHub step) |

Out of scope, intentionally not planned: the Android app (§4 to §8), gateway rule (§10 step 2), QR regeneration (§10 steps 3 to 5).

### Placeholder scan

No "TBD", "similar to Task N", "add error handling" or bare "write tests" remain; every code step contains the actual code.

### Name and signature consistency (checked across tasks)

- `Alert` fields used by Task 4 (`to_history_entry`) and Task 5 (`android_priority`, `ttl_seconds`) match Task 2's definition (`id, channel, severity, title, body, image_url, video_url, entity_id, actions, silent, cancel, ttl, sent_at, source`).
- `TokenRegistry` methods called in Task 6 (`handle`, `tokens`, `installs_for_token`, `remove`, `clear`) are all defined in Task 3.
- `History` methods called in Task 6 (`apply`, `trim`, `to_json`, `__len__`) are defined in Task 4; the service constructs it with `(path, size=, days=, clock=, logger=)` as defined.
- `FCMSender.send_data(data, tokens, priority, ttl) -> SendResult` is the same signature in Task 5, the service (Task 6) and the Task 6 `FakeSender`; the queue item shape `{"id","data","tokens","priority","ttl"}` is produced in `dispatch` and consumed in `_retry_send`.
- `NotificationQueue(queue_file, retry_interval, max_retries, logger, clock, poll_interval)` and `run_once`, `start_retry_worker`, `stop_retry_worker`, `.queue` are used in Task 6 exactly as defined in Task 5.
- `MQTTHandler(config, logger, message_callback, on_connected, client_factory, sleep)` and its `publish(topic, payload, qos, retain)` match the service calls; the fake client's `FakeMQTTClient(client_id, clean_session, fail_connects)` matches the handler's `client_factory(client_id=..., clean_session=...)` call.
- Config dict keys from `config.as_dict()` (Task 1) match what the service and handler read (`topic_prefix`, `data_dir`, `history_size`, `history_days`, `retry_interval`, `max_retries`, `heartbeat_*`, `auto_reconnect`, `reconnect_delay_*`, `mqtt_*`).

### Review Focus tests present in owning tasks

1. `test_invalid_token_shared_by_two_installs_clears_both_topics` is in Task 6.
2. `test_mixed_failures_queue_only_transient_tokens` and `test_retry_skips_tokens_no_longer_registered` are in Task 6.
3. `test_truncation_multibyte_body_stays_valid_utf8_and_under_limit` is in Task 2.
4. `test_garbage_payloads_are_dropped_without_side_effects` is in Task 6; `test_non_object_payloads_are_rejected` is in Task 2.
5. `test_cancel_keeps_position_and_reposting_clears_resolved` is in Task 4.
