# Frigate login for alert media — design

Date: 2026-10-08 · Status: draft · Author: Gekkotron

## 1. Goal

Alert snapshots and clips sent by the Frigate bridge (`OikosPush/bridges/frigate`) must load in Oikos when Frigate
requires a login (its authenticated port, 8971), both on the home Wi‑Fi and through the gateway. The setup stays
generic: an unauthenticated Frigate (port 5000) keeps working with no credentials.

**Success criteria**
- With a Frigate URL, username and password configured, the snapshot and clip of a Frigate alert load on the LAN and
  over mobile data (gateway).
- An expired or missing token is renewed without user action.
- Credentials and token never leave the phone except toward the configured Frigate host (LAN or gateway, which is
  end-to-end encrypted).
- No credentials configured → behaviour is exactly today's.

**Facts this relies on (checked 2026-10-08)**
- The user's Frigate on 8971 serves plain HTTP (`http://192.168.1.91:8971/api/version` → 401; HTTPS → TLS error).
  The app already permits cleartext to LAN hosts (`network_security_config.xml`), so no certificate handling is needed.
- `POST /api/login` with `{"user","password"}` returns the JWT only in a `Set-Cookie: frigate_token=…` header.
  Authenticated requests send `Cookie: frigate_token=<jwt>`.
- Gateway v1.3 forwards request headers on both `http` and raw `download`, verifies upstream TLS, and returns only
  status, body and (raw) content type — no response headers. Hence §2.

**Out of scope:** HTTPS Frigate with a self-signed certificate; live view / RTSP; Frigate proxy-auth headers.

## 2. Gateway v1.4 (Athena-HttpMqttGateway)

- `server/services/http_service.py`, non-raw path: the response payload gains `"headers": {name: value}` with the
  upstream response headers, names lower-cased. Repeated headers (several `Set-Cookie`) are joined with `\n`.
  The raw path is unchanged.
- SDK: `GatewayResponse` gains `headers: Map<String, String> = emptyMap()` (lower-case keys), parsed when present.
  Older servers simply yield an empty map.
- Tests: pytest that a `Set-Cookie` upstream header comes back; SDK decoder test with and without `headers`.
- Release: tag `v1.4` (JitPack). README documents the new field. Deployed with the existing `update.sh`.

## 3. Oikos: model, storage and Settings

- `ConnectionProfile.Direct` gains `frigate: FrigateConfig? = null`.
  `FrigateConfig(url: String, username: String?, password: String?)`; `toString` masks the password.
  `url` is normalised to `scheme://host[:port]` (http or https, no path, no trailing slash).
- `ProfileRepository` stores it next to `historyUrl`; the password is sealed with `SecretStore` like the broker
  password. An edit method updates it in place (no new pairing, rooms and route kept), like `setHistoryUrl`.
  "Set up again" clears it with the rest of the profile.
- Settings: a "Frigate" row opening a dialog with three fields:
  - URL — prefilled with the profile's value, else the generic default `http://frigate:8971`; hint
    "e.g. http://frigate:8971 (login) or http://frigate:5000 (no login)".
  - Username and password — optional; the password field is masked and shows "unchanged" when one is stored.
  - Save validates the URL (same rule as §5) and stores it; a blank URL removes the Frigate config (as the history
    dialog does). A blank password keeps the stored one only when the username is unchanged.
- Nothing specific to one house is hard-coded.

## 4. Oikos: `FrigateAuth` (core/alerts)

One class, one job: give the media fetcher the headers a Frigate URL needs.

- `headersFor(url): Map<String, String>` — empty unless a Frigate config with a username and password exists **and**
  the URL's scheme, host and port equal the configured URL's (default ports filled in). Otherwise
  `Cookie: frigate_token=<token>`, logging in first when no token is cached.
- `onUnauthorized(url)` — drops the cached token and logs in again; returns whether a new token was obtained.
- Login: `POST <frigate url>/api/login`, JSON `{"user","password"}`, through the same route choice as media
  (LAN when the probe answers and the host is a LAN host, otherwise the gateway `http` call). The token is read from
  `Set-Cookie` (`frigate_token=` up to `;`). 401/400 → `AuthFailed`; other failures → `Unavailable`.
- Token cache: in memory only (a cold start logs in once; that is one cheap request). Logins are serialised with a
  `Mutex`: concurrent fetches share one login.
- Changing or clearing the Frigate config drops the cached token.

**Wiring**
- `LanOrGatewayGet.get` gains `headers: Map<String, String> = emptyMap()`, sent on both the LAN request and the
  gateway `download`. `GatewayDownloader.download` gains the same parameter.
- `LanOrGatewayGet` gains `post(url, json): HttpPostResult(status, headers)` for the login, with the same route choice;
  over the gateway it uses `AthenaGatewayClient.http` (behind a new `GatewayDownloader.post`) and returns status +
  headers.
- `MediaFetcher.bytes`: `headers = auth.headersFor(url)`; on a 401 with non-empty headers, `onUnauthorized` then one
  retry; a second 401 (or a failed login) becomes the new `MediaFailure.AuthFailed`. A 401 without Frigate headers
  stays `Unavailable("http 401")` as today. Never more than one re-login per fetch.
- UI: `AuthFailed` shows "Frigate login failed — check Settings" where "Media no longer available" shows today.
- Video (`VideoCache`) goes through `MediaFetcher`, so clips get the same treatment.

## 5. Setup payload (`v:1`) and QR tool

- New optional top-level section, additive to format `v:1` (old apps ignore unknown keys):
  `"frigate": {"url": "http://192.168.1.91:8971", "user": "admin", "pass": "…"}`.
  - `url` required inside the section: `http(s)://host[:port]`, else `InvalidField("frigate.url")`.
  - `user` / `pass` optional; `pass` without `user` → `InvalidField("frigate.user")`.
- `OnboardingPayload.parse` fills `ConnectionProfile.Direct.frigate`. A profile set up from a QR shows the values in
  Settings, where they can be edited.
- `tools/setup-qr.py`: `--frigate-url URL`, `--frigate-user NAME`, `--frigate-pass-file PATH` (read from a file,
  never printed, like the gateway key), `--no-frigate` to remove the section. Without these flags the section is left
  as it is. The summary line lists `frigate` among the written sections.

## 6. Deployment notes

- OikosPush root `.env`: `FRIGATE_URL=http://192.168.1.91:8971` (the bridge builds snapshot/clip URLs from it);
  `.env.example` comments show both forms (`http://frigate:8971` with login, `http://frigate:5000` without).
  Publishing port 5000 is no longer needed.
- Gateway key rules: allow `192.168.1.91:8971`.
- Oikos: `athenaGatewayClient = "v1.4"`.

## 7. Testing

- Gateway: pytest for response headers; SDK decoder tests.
- Oikos unit tests:
  - `FrigateAuth`: URL matching (host, port, default ports, other hosts get nothing), `Set-Cookie` parsing (several
    cookies, attributes), cache reuse, one login for concurrent callers, re-login on 401, config change drops token.
  - `MediaFetcher`: headers sent; 401 → re-login → retry succeeds; second 401 → `AuthFailed`; no loop.
  - `LanOrGatewayGet`: headers on LAN and gateway paths.
  - `OnboardingPayload`: frigate section valid / missing url / bad url / pass without user.
  - `ProfileRepository`: frigate round-trip with the password sealed.
- `setup-qr.py`: manual run with and without the frigate flags.
- Device check: a Frigate alert's snapshot and clip on Wi‑Fi, then on mobile data.
