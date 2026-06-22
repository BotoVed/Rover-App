"""Rover RNS transport layer — minimal API for Android via Chaquopy."""
from __future__ import annotations

import os
import signal
import sys
import time

import RNS
import LXMF


_incoming_callback = None


def set_incoming_callback(cb):
    global _incoming_callback
    _incoming_callback = cb


def _on_lxmf_message(message: LXMF.LXMessage) -> None:
    if _incoming_callback is None:
        return
    source_hex = message.source_hash.hex()[:16] if message.source_hash else ""
    if isinstance(message.fields, dict):
        fields = message.fields
    elif isinstance(message.fields, bytes):
        try:
            import msgpack
            fields = msgpack.unpackb(message.fields)
        except Exception:
            fields = {}
    else:
        fields = {}
    try:
        _incoming_callback(source_hex, fields)
    except Exception as ex:
        import traceback
        traceback.print_exc()


def start(config_dir: str, host: str, port: int) -> str:
    os.makedirs(config_dir, exist_ok=True)

    identity_path = os.path.join(config_dir, "rover_identity")
    if os.path.exists(identity_path):
        identity = RNS.Identity.from_file(identity_path)
    else:
        identity = RNS.Identity()
        identity.to_file(identity_path)
    identity_hash = identity.hash.hex()

    # RNS tries to install signal handlers — block them on Android
    _orig_signal = signal.signal
    signal.signal = lambda signum, handler: None
    try:
        config_path = os.path.join(config_dir, "config")
        config_content = f"""
[reticulum]
enable_transport = True
share_instance = No
loglevel = 7

[interfaces]
  [[Rover TCP Client]]
    type = TCPClientInterface
    enabled = Yes
    target_host = {host}
    target_port = {port}
"""
        with open(config_path, "w") as f:
            f.write(config_content.strip())

        _rns = RNS.Reticulum(configdir=config_dir)
        RNS.loglevel = RNS.LOG_EXTREME
        RNS.logdest = RNS.LOG_STDOUT

        router = LXMF.LXMRouter(
            identity=identity,
            storagepath=os.path.join(config_dir, "lxmf_storage"),
        )
    finally:
        signal.signal = _orig_signal

    dest = router.register_delivery_identity(identity)
    router.register_delivery_callback(_on_lxmf_message)

    return identity_hash[:16]


def request_path_and_wait(dest_hex: str, timeout_s: float = 3.0) -> bool:
    dest_bytes = bytes.fromhex(dest_hex)
    if RNS.Transport.has_path(dest_bytes):
        print(f"[PATH_WAIT] has_path=true immediately")
        return True
    RNS.Transport.request_path(dest_bytes)
    deadline = time.monotonic() + timeout_s
    started = time.monotonic()
    last_log = 0.0
    while time.monotonic() < deadline:
        now = time.monotonic()
        elapsed = now - started
        has_it = RNS.Transport.has_path(dest_bytes)
        # Log every 1s or on path found
        if has_it or elapsed - last_log >= 1.0:
            last_log = elapsed
            print(f"[PATH_WAIT] t={elapsed:.1f}s elapsed={elapsed:.0f}s deadline_in={deadline-now:.0f}s has_path={has_it}")
        if has_it:
            return True
        time.sleep(0.05)
    total = time.monotonic() - started
    print(f"[PATH_WAIT] TIMED OUT after {total:.0f}s (timeout_s={timeout_s})")
    return False


def active_channel() -> str:
    for iface in RNS.Transport.interfaces:
        name = str(iface)
        if "Rover TCP Client" in name or "TCP" in name:
            status = getattr(iface, "online", True)
            if status:
                return "TCP"
            return "TCP (offline)"
    return "none"


def dump_diagnostics() -> str:
    lines = []
    lines.append("=== RNS Diagnostics ===")

    # Path table
    lines.append(f"Path table entries: {len(RNS.Transport.path_table)}")
    for dh, entry in list(RNS.Transport.path_table.items())[:20]:
        ts, received_from, hops, expires, blobs, iface, ph = entry
        lines.append(f"  path dest={dh.hex()} hops={hops} expires_in={expires-time.time():.0f}s iface={iface}")
    if len(RNS.Transport.path_table) > 20:
        lines.append(f"  ... ({len(RNS.Transport.path_table)-20} more)")

    # Destination table (local destinations)
    lines.append(f"Local destinations: {len(RNS.Transport.destinations)}")
    for d in RNS.Transport.destinations[:10]:
        lines.append(f"  dest={d}")

    # Interfaces
    lines.append(f"Interfaces: {len(RNS.Transport.interfaces)}")
    for iface in RNS.Transport.interfaces:
        name = str(iface)
        online = getattr(iface, "online", "?")
        lines.append(f"  iface={name} online={online}")

    # Announce table
    lines.append(f"Announce table entries: {len(RNS.Transport.announce_table)}")

    result = "\n".join(lines)
    RNS.log(result, RNS.LOG_NOTICE)
    return result
