#!/usr/bin/env python3
"""Seed the disposable ARC glyph catalog and keep its 120-second lease fresh."""

import argparse
import json
import os
import socket
import sys
import time


HOST = os.environ.get("ARC_E2E_REDIS_HOST", "127.0.0.1")
PORT = int(os.environ.get("ARC_E2E_REDIS_PORT", "6385"))
HASH_KEY = "arc:chat-glyphs:v1:catalog"
FIELD = "spawn"
HEARTBEAT_SECONDS = 30
RETRY_SECONDS = 5


def redis_command(*parts: bytes) -> bytes:
    request = bytearray(f"*{len(parts)}\r\n".encode("ascii"))
    for part in parts:
        request.extend(f"${len(part)}\r\n".encode("ascii"))
        request.extend(part)
        request.extend(b"\r\n")

    with socket.create_connection((HOST, PORT), timeout=3) as connection:
        connection.settimeout(3)
        connection.sendall(request)
        with connection.makefile("rb") as response:
            line = response.readline()
    if not line.startswith(b":"):
        raise RuntimeError(f"Unexpected Redis HSET response: {line[:120]!r}")
    return line.rstrip(b"\r\n")


def publish() -> None:
    envelope = {
        "schemaVersion": 1,
        "serverId": "spawn",
        "publishedAtMillis": time.time_ns() // 1_000_000,
        "definitions": [
            {"id": "e2e:test", "unicode": "🧪", "technical": False},
        ],
    }
    payload = json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    redis_command(
        b"HSET",
        HASH_KEY.encode("ascii"),
        FIELD.encode("ascii"),
        payload,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--once", action="store_true", help="publish one fresh snapshot and exit")
    mode.add_argument("--heartbeat", action="store_true", help="refresh the snapshot every 30 seconds")
    args = parser.parse_args()

    if args.once:
        publish()
        print(f"Seeded {HASH_KEY}[{FIELD}] on disposable Redis {HOST}:{PORT}")
        return 0

    while True:
        try:
            publish()
        except (OSError, RuntimeError) as failure:
            print(f"Could not refresh disposable ARC glyph catalog: {failure}", file=sys.stderr, flush=True)
            time.sleep(RETRY_SECONDS)
            continue
        time.sleep(HEARTBEAT_SECONDS)


if __name__ == "__main__":
    raise SystemExit(main())
