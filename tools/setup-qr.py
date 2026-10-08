#!/usr/bin/env python3
"""Builds the Oikos setup QR code (format v:1, direct-connection spec §4.4).

Starts from an existing setup JSON (the `local` section, `prefix`, `push`) and adds the optional
`gateway` section: {"url", "key", "broker_host"?}. Without --gateway-url the gateway section is left
as it is, and --no-gateway removes it. The key is read from a file and never printed.

The optional `history` section ({"url"}, an override of http://<broker host>:8090) is set with --history-url http://192.168.1.91:8090 and
removed with --no-history; without either it is left as it is.

    tools/setup-qr.py --gateway-url https://house.example.ts.net \\
        --gateway-key-file ../HttpMqttEnd2EndEncryption/secret_key.txt --broker-host 192.168.1.91

Writes oikos-setup.json and oikos-setup.png (both gitignored; they hold secrets). Needs `qrencode`.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent


def fail(message):
    sys.exit(f"setup-qr: {message}")


def read_key(path):
    text = Path(path).read_text().strip()
    if not re.fullmatch(r"[0-9a-fA-F]{64}", text):
        fail(f"{path} must hold the gateway key as 64 hex characters (32 bytes); its content is not shown")
    return text.lower()


def check_url(url):
    parts = urlsplit(url)
    if parts.scheme != "https" or not parts.hostname:
        fail("--gateway-url must be an https URL with a host")
    if parts.port is not None and not 1 <= parts.port <= 65535:
        fail("--gateway-url has a port outside 1..65535")
    return url


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--in", dest="source", default=ROOT / "oikos-setup.json", type=Path)
    parser.add_argument("--out", default=ROOT / "oikos-setup", type=Path, help="path without extension")
    parser.add_argument("--gateway-url")
    parser.add_argument("--gateway-key-file")
    parser.add_argument("--broker-host", help="the broker as the gateway sees it (optional)")
    parser.add_argument("--no-gateway", action="store_true", help="remove the gateway section")
    parser.add_argument("--history-url", help="override the OikosHistory URL (default: http://<broker host>:8090), e.g. http://192.168.1.91:8090")
    parser.add_argument("--no-history", action="store_true", help="remove the history section")
    args = parser.parse_args()

    payload = json.loads(args.source.read_text())
    if payload.get("v") != 1 or not isinstance(payload.get("local"), dict):
        fail(f"{args.source} is not a v:1 setup payload with a local section")

    if args.no_gateway:
        payload.pop("gateway", None)
    elif args.gateway_url:
        if not args.gateway_key_file:
            fail("--gateway-url needs --gateway-key-file")
        gateway = {"url": check_url(args.gateway_url), "key": read_key(args.gateway_key_file)}
        if args.broker_host:
            gateway["broker_host"] = args.broker_host
        payload["gateway"] = gateway
    elif args.gateway_key_file or args.broker_host:
        fail("--gateway-key-file and --broker-host need --gateway-url")

    if args.no_history:
        payload.pop("history", None)
    elif args.history_url:
        if not re.fullmatch(r"https?://[A-Za-z0-9._-]+(:\d{1,5})?/?", args.history_url):
            fail("--history-url must be http(s)://host[:port]")
        payload["history"] = {"url": args.history_url.rstrip("/")}

    text = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    json_path, png_path = args.out.with_suffix(".json"), args.out.with_suffix(".png")
    json_path.write_text(text + "\n")
    subprocess.run(["qrencode", "-l", "M", "-s", "8", "-o", str(png_path), text], check=True)
    sections = ", ".join(k for k in ("local", "gateway", "push", "history") if k in payload)
    print(f"wrote {json_path.name} and {png_path.name} ({sections})")


if __name__ == "__main__":
    main()
