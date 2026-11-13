"""Compose와 같은 Redis 7.0에서 실제 Lua를 검증하고 테스트 소유 컨테이너만 정리한다."""

import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import sys
import time
import uuid


def docker(*parts):
    return subprocess.run(["docker", *parts], check=True, capture_output=True, text=True).stdout.strip()


def load_probe():
    sys.dont_write_bytecode = True
    source = Path(__file__).with_name("verify-permit-lua.py")
    spec = importlib.util.spec_from_file_location("coupon_lua_probe", source)
    probe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(probe)

    def encode(parts):
        values = [str(part).encode() for part in parts]
        return f"*{len(values)}\r\n".encode() + b"".join(
            f"${len(value)}\r\n".encode() + value + b"\r\n" for value in values
        )

    def command(address, *parts, user=None):
        with socket.create_connection(address, timeout=5) as connection:
            with connection.makefile("rb") as stream:
                if user is not None:
                    connection.sendall(encode(["AUTH", user, ""]))
                    assert probe.read_response(stream) == "OK"

                connection.sendall(encode(parts))
                return probe.read_response(stream)

    probe.command = command
    return probe


def endpoint(name):
    info = json.loads(docker("inspect", name))[0]
    return "127.0.0.1", int(info["NetworkSettings"]["Ports"]["6379/tcp"][0]["HostPort"])


def await_ready(probe, address):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        try:
            if probe.command(address, "PING") == "PONG":
                return
        except (OSError, RuntimeError):
            pass
        time.sleep(0.05)
    raise RuntimeError("Test-owned Docker Redis did not become ready")


def main():
    """기존 smoke와 같은 검증을 수행하고 정상 재시작 뒤 AOF를 대조한다. HA failover 검증은 아니다."""
    probe = load_probe()
    resources = Path(__file__).resolve().parents[3] / "apps/commerce-api/src/main/resources/coupon"
    scripts = {name: (resources / f"{name}.lua").read_text() for name in ("initialize", "acquire", "release")}
    common = (resources / "ssot-common.lua").read_text()
    scripts.update({name: common + "\n" + (resources / f"{name}.lua").read_text()
                    for name in ("prepare", "issue", "read", "events", "ack")})
    name = "coupon-lua-redis70-" + uuid.uuid4().hex[:10]

    docker("run", "--rm", "--detach", "--name", name, "--publish", "127.0.0.1::6379", "redis:7.0",
           "redis-server", "--save", "", "--appendonly", "yes", "--appendfsync", "always",
           "--maxmemory-policy", "noeviction")
    try:
        address = endpoint(name)
        await_ready(probe, address)
        version = next(line.split(":", 1)[1] for line in probe.command(address, "INFO", "server").splitlines()
                       if line.startswith("redis_version:"))
        assert version.startswith("7.0.")

        probe.verify(address, scripts)
        probe.verify_faults(address, scripts)
        probe.verify_ssot(address, scripts)

        docker("restart", name)
        address = endpoint(name)
        await_ready(probe, address)
        assert probe.command(address, "HGET", "coupon:ssot:{issuance}:quota:meta", "sequence") == "5"
        assert probe.command(address, "XLEN", "coupon:ssot:{issuance}:quota:events") == 5
        assert probe.command(address, "HGET", "coupon:ssot:{issuance}:partial:meta", "health") == "DIRTY"

        print(json.dumps({"image": "redis:7.0", "redis_version": version, "contenders": 60, "capacity": 3,
                          "accepted": 3, "ssot_quota": "5/8", "stream_replay_ack": "passed",
                          "partial_write": "DIRTY", "aof_normal_restart": "passed", "checks": "passed"}))
    finally:
        docker("stop", name)


if __name__ == "__main__":
    main()
