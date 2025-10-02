#!/usr/bin/env python3
"""
Simple MQTT subscriber utility for RCBoat Gateway topics.

- Supports ssl:// and tcp:// URLs or bare hostnames
- Subscribes to:
    boats/<boat_id>/from_vehicle
    boats/<boat_id>/to_vehicle
    boats/<boat_id>/status
  or wildcard topics with --wildcard
- Prints text status messages and hex-dumps MAVLink payloads

Usage examples:
  python scripts/mqtt_sniffer.py \
    --host ssl://p4639933.ala.asia-southeast1.emqxsl.com \
    --username saturn-base --password Boattimes33 \
    --boat-id sea_serpent_01

  python scripts/mqtt_sniffer.py \
    --host ssl://p4639933.ala.asia-southeast1.emqxsl.com \
    --username saturn-base --password Boattimes33 \
    --wildcard
"""
from __future__ import annotations

import argparse
import binascii
import signal
import ssl
import sys
import time
from typing import List, Tuple
from urllib.parse import urlparse

import paho.mqtt.client as mqtt


def parse_host_port(host_arg: str, port_arg: int | None) -> Tuple[str, int, bool]:
    """Parse host/port from various forms and return (host, port, use_tls)."""
    use_tls = True
    host = host_arg
    port = port_arg

    if "://" in host_arg:
        u = urlparse(host_arg)
        if u.scheme not in ("ssl", "tcp"):
            raise ValueError(f"Unsupported scheme '{u.scheme}'. Use ssl:// or tcp://")
        use_tls = (u.scheme == "ssl")
        host = u.hostname or host_arg
        if port is None:
            port = u.port or (8883 if use_tls else 1883)
    else:
        # Bare hostname; default TLS:8883 unless overridden
        if port is None:
            port = 8883
        use_tls = (port == 8883)
    return host, int(port), bool(use_tls)


def topics_for_boat(boat_id: str) -> List[str]:
    base = f"boats/{boat_id}"
    return [
        f"{base}/from_vehicle",
        f"{base}/to_vehicle",
        f"{base}/status",
    ]


def wildcard_topics() -> List[str]:
    return [
        "boats/+/from_vehicle",
        "boats/+/to_vehicle",
        "boats/+/status",
    ]


def is_mostly_text(payload: bytes) -> bool:
    if not payload:
        return True
    printable = sum(1 for b in payload if 32 <= b <= 126 or b in (9, 10, 13))
    return printable / max(1, len(payload)) > 0.85


def hexdump(b: bytes, width: int = 16) -> str:
    lines = []
    for i in range(0, len(b), width):
        chunk = b[i : i + width]
        hexpart = " ".join(f"{x:02x}" for x in chunk)
        asciipart = "".join(chr(x) if 32 <= x <= 126 else "." for x in chunk)
        lines.append(f"{i:04x}  {hexpart:<{width*3}}  {asciipart}")
    return "\n".join(lines)


def on_connect(client: mqtt.Client, userdata, flags, rc):
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    if rc == 0:
        print(f"[{ts}] Connected to broker")
        # Re-subscribe on reconnect
        subs: List[Tuple[str, int]] = userdata.get("subs", [])
        for topic, qos in subs:
            client.subscribe(topic, qos=qos)
            print(f"[{ts}] Subscribed to {topic} (QoS {qos})")
    else:
        print(f"[{ts}] Connect failed with rc={rc}")


def on_message(client: mqtt.Client, userdata, msg: mqtt.MQTTMessage):
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    topic = msg.topic
    payload = msg.payload or b""
    info = f"[{ts}] {topic} (QoS {msg.qos}, {len(payload)} bytes)"

    try:
        if topic.endswith("/status") and is_mostly_text(payload):
            print(f"{info}: {payload.decode('utf-8', errors='replace')}")
        else:
            print(info)
            print(hexdump(payload))
    except Exception as e:
        print(f"[{ts}] Error printing message: {e}")


def build_client(client_id: str, use_tls: bool, username: str | None, password: str | None) -> mqtt.Client:
    client = mqtt.Client(client_id=client_id, clean_session=True)
    if username:
        client.username_pw_set(username, password or "")
    if use_tls:
        ctx = ssl.create_default_context()
        # Enforce TLS 1.2+
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        client.tls_set_context(ctx)
    client.on_connect = on_connect
    client.on_message = on_message
    # Backoff between auto-reconnect attempts
    client.reconnect_delay_set(min_delay=1, max_delay=60)
    return client


def main(argv: List[str]) -> int:
    ap = argparse.ArgumentParser(description="Subscribe to RCBoat MQTT topics")
    ap.add_argument("--host", required=True, help="Broker host or URL (e.g. ssl://host or host)")
    ap.add_argument("--port", type=int, default=None, help="Port (defaults 8883 for ssl, 1883 for tcp)")
    ap.add_argument("--username", default=None)
    ap.add_argument("--password", default=None)
    ap.add_argument("--boat-id", default="sea_serpent_01", help="Boat ID to subscribe to")
    ap.add_argument("--wildcard", action="store_true", help="Subscribe to all boats (uses +)")
    ap.add_argument("--qos", type=int, default=1, choices=[0, 1, 2])
    args = ap.parse_args(argv)

    try:
        host, port, use_tls = parse_host_port(args.host, args.port)
    except Exception as e:
        print(f"Invalid host: {e}")
        return 2

    client_id = f"rcboat_sniffer_{int(time.time())}"
    client = build_client(client_id, use_tls, args.username, args.password)

    # Prepare subscriptions and stash in userdata for reconnect
    subs: List[Tuple[str, int]] = []
    if args.wildcard:
        for t in wildcard_topics():
            subs.append((t, args.qos))
    else:
        for t in topics_for_boat(args.boat_id):
            subs.append((t, args.qos))

    client.user_data_set({"subs": subs})

    def handle_sigint(signum, frame):
        print("\nDisconnecting...")
        try:
            client.disconnect()
        except Exception:
            pass
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_sigint)

    try:
        print(f"Connecting to {host}:{port} TLS={use_tls}")
        client.connect(host, port=port, keepalive=60)
        # Subscribe after initial connect in on_connect; also subscribe right away in case connect is already up
        for topic, qos in subs:
            client.subscribe(topic, qos=qos)
        client.loop_forever()
    except Exception as e:
        print(f"Connection error: {e}")
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

