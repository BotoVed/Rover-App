"""Rover RNS transport layer — minimal API for Android via Chaquopy."""
from __future__ import annotations

import os
import signal
import sys
import time

import RNS
import LXMF


_incoming_callback = None
_router = None
_delivery_dest = None
_log_f = None

# Server identity/destination pre-loaded from QR public key
_server_identity = None   # RNS.Identity with only public key
_server_dest = None       # RNS.Destination(lxmf/delivery) for the server


def _init_file_log(config_dir: str) -> None:
    """Redirect sys.stdout to tee into debug.log (captures our prints + RNS internals)."""
    global _log_f
    log_path = os.path.join(config_dir, "debug.log")
    _log_f = open(log_path, "a", buffering=1)
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    _log_f.write(f"\n=== Py session {ts} ===\n")
    _log_f.flush()
    _orig = sys.stdout

    class _Tee:
        def write(self, text):
            try: _orig.write(text)
            except Exception: pass
            try: _log_f.write(text)
            except Exception: pass
        def flush(self):
            try: _orig.flush()
            except Exception: pass
            try: _log_f.flush()
            except Exception: pass
        def fileno(self):
            return _orig.fileno()

    sys.stdout = _Tee()


def set_incoming_callback(cb):
    global _incoming_callback
    _incoming_callback = cb


def _on_lxmf_message(message: LXMF.LXMessage) -> None:
    if _incoming_callback is None:
        print("[LXMF_IN] callback is None, dropping")
        return
    source_hex = message.source_hash.hex()[:16] if message.source_hash else ""
    if isinstance(message.fields, dict):
        fields = message.fields
    else:
        fields = {}
    print(f"[LXMF_IN] src={source_hex} fields_keys={list(fields.keys())}")
    try:
        import json
        _incoming_callback.onMessage(source_hex, json.dumps(fields, default=str))
    except Exception as ex:
        print(f"[LXMF_IN] EXCEPTION: {ex}")


def start(config_dir: str, host: str, port: int) -> str:
    global _router, _delivery_dest

    os.makedirs(config_dir, exist_ok=True)
    _init_file_log(config_dir)

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
    _router = router
    _delivery_dest = dest

    return identity_hash[:16]


def set_server_pk(pk_b64: str) -> bool:
    """Pre-compute server LXMF delivery destination from QR public key.

    The QR contains the server identity hash + raw public key (base64).
    RNS path table is keyed by *destination hash* (lxmf/delivery), not identity hash,
    so we build the destination object once here and reuse it everywhere.
    """
    global _server_identity, _server_dest
    import base64
    try:
        pk_bytes = base64.b64decode(pk_b64)
        identity = RNS.Identity(create_keys=False)
        identity.load_public_key(pk_bytes)
        dest = RNS.Destination(
            identity,
            RNS.Destination.OUT,
            RNS.Destination.SINGLE,
            "lxmf", "delivery",
        )
        _server_identity = identity
        _server_dest = dest
        print(f"[SET_SERVER] identity={identity.hash.hex()[:16]} delivery_dest={dest.hexhash}")
        return True
    except Exception as e:
        print(f"[SET_SERVER] failed: {e}")
        return False


def request_path_and_wait(dest_hex: str, timeout_s: float = 3.0) -> bool:
    # Use delivery destination hash if server pk was pre-loaded; otherwise fall back.
    if _server_dest is not None:
        dest_bytes = _server_dest.hash
        print(f"[PATH_WAIT] using delivery dest {dest_bytes.hex()}")
    else:
        dest_bytes = bytes.fromhex(dest_hex)
        print(f"[PATH_WAIT] WARNING: server pk not set, using raw dest_hex (may fail)")
    if RNS.Transport.has_path(dest_bytes):
        print(f"[PATH_WAIT] has_path=true immediately")
        return True
    RNS.Transport.request_path(dest_bytes)
    deadline = time.monotonic() + timeout_s
    started = time.monotonic()
    last_log = 0.0
    last_request = time.monotonic()
    while time.monotonic() < deadline:
        now = time.monotonic()
        elapsed = now - started
        has_it = RNS.Transport.has_path(dest_bytes)
        if has_it or elapsed - last_log >= 1.0:
            last_log = elapsed
            print(f"[PATH_WAIT] t={elapsed:.1f}s elapsed={elapsed:.0f}s deadline_in={deadline-now:.0f}s has_path={has_it}")
        if has_it:
            return True
        # Re-request every 5s — initial request may be lost if TCP was reconnecting
        if now - last_request >= 5.0:
            RNS.Transport.request_path(dest_bytes)
            last_request = now
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
            return "offline"
    return "none"


def send(dest_hex: str, fields: dict, await_path: bool = False, path_timeout_s: float = 15.0) -> bool:
    if _router is None or _delivery_dest is None:
        print("[SEND] router or delivery_dest not initialized")
        return False

    if await_path:
        if not request_path_and_wait(dest_hex, timeout_s=path_timeout_s):
            print(f"[SEND] path request failed for {dest_hex[:16]}")
            return False

    # Prefer pre-loaded server identity (from QR pk) over recall by identity hash.
    # recall() is keyed by *delivery destination hash*, not identity hash, so it
    # would fail if we pass the identity hash from the QR.
    if _server_identity is not None and _server_dest is not None:
        identity = _server_identity
        remote_dest = _server_dest
    else:
        dest_bytes = bytes.fromhex(dest_hex)
        identity = RNS.Identity.recall(dest_bytes)
        if identity is None:
            RNS.Transport.request_path(dest_bytes)
            print(f"[SEND] identity not in cache for {dest_hex[:16]}, path requested")
            return False
        remote_dest = RNS.Destination(
            identity,
            RNS.Destination.OUT,
            RNS.Destination.SINGLE,
            "lxmf", "delivery",
        )

    msg = LXMF.LXMessage(
        destination=remote_dest,
        source=_delivery_dest,
        content=b"",
        title=b"",
        desired_method=LXMF.LXMessage.DIRECT,
    )
    msg.fields = fields

    try:
        _router.handle_outbound(msg)
        print(f"[SEND] dest={dest_hex[:16]} fields_keys={list(fields.keys())}")
        return True
    except Exception as ex:
        print(f"[SEND] failed: {ex}")
        return False


def send_cmd(server_dest_hex: str, fields_json: str) -> bool:
    import json
    raw = json.loads(fields_json)
    fields = {int(k): v for k, v in raw.items()}
    return send(server_dest_hex, fields, await_path=True)


def send_register(server_dest_hex: str, uid: str) -> bool:
    fields = {0: 9, 1: uid}
    # 90s timeout: server announces every 60s, plus TCP reconnect overhead
    return send(server_dest_hex, fields, await_path=True, path_timeout_s=90.0)


def send_ping(server_dest_hex: str, hashes_json: str = "{}") -> bool:
    import json
    section_hashes = json.loads(hashes_json)
    fields = {0: 6}
    if section_hashes:
        fields[1] = section_hashes
    return send(server_dest_hex, fields, await_path=True)


def send_req(server_dest_hex: str, sections_csv: str = "m,u,a,d") -> bool:
    sections = [s for s in sections_csv.split(",") if s]
    fields = {0: 8, 5: sections}
    return send(server_dest_hex, fields, await_path=True)


_identity_cache = {}

def get_identity(config_dir: str = None) -> str:
    """Return client identity hash if RNS is already running (for reinit case)."""
    if config_dir is None:
        config_dir = os.path.join(os.getcwd(), "rover_rns")
    identity_path = os.path.join(config_dir, "rover_identity")
    if os.path.exists(identity_path):
        identity = RNS.Identity.from_file(identity_path)
        return identity.hash.hex()[:16]
    return ""


def dump_diagnostics() -> str:
    lines = []
    lines.append("=== RNS Diagnostics ===")

    lines.append(f"Path table entries: {len(RNS.Transport.path_table)}")
    for dh, entry in list(RNS.Transport.path_table.items())[:20]:
        ts, received_from, hops, expires, blobs, iface, ph = entry
        lines.append(f"  path dest={dh.hex()} hops={hops} expires_in={expires-time.time():.0f}s iface={iface}")
    if len(RNS.Transport.path_table) > 20:
        lines.append(f"  ... ({len(RNS.Transport.path_table)-20} more)")

    lines.append(f"Local destinations: {len(RNS.Transport.destinations)}")
    for d in RNS.Transport.destinations[:10]:
        lines.append(f"  dest={d}")

    lines.append(f"Interfaces: {len(RNS.Transport.interfaces)}")
    for iface in RNS.Transport.interfaces:
        name = str(iface)
        online = getattr(iface, "online", "?")
        lines.append(f"  iface={name} online={online}")

    lines.append(f"Announce table entries: {len(RNS.Transport.announce_table)}")

    result = "\n".join(lines)
    RNS.log(result, RNS.LOG_NOTICE)
    return result
