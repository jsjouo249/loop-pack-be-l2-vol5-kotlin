"""실제 Lua 리소스를 임시 로컬 Redis에서 검증한다. 기존 Redis나 DB에는 연결하지 않는다."""

from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import json
import shutil
import socket
import subprocess
import tempfile
import threading
import time


def read_response(stream):
    prefix = stream.read(1)
    line = stream.readline().removesuffix(b"\r\n")
    if prefix == b":":
        return int(line)
    if prefix == b"+":
        return line.decode()
    if prefix == b"-":
        raise RuntimeError(line.decode())
    if prefix == b"*":
        length = int(line)
        return None if length == -1 else [read_response(stream) for _ in range(length)]
    if prefix == b"$":
        length = int(line)
        if length == -1:
            return None
        value = stream.read(length)
        assert stream.read(2) == b"\r\n"
        return value.decode()
    raise RuntimeError(f"Unexpected Redis response: {prefix!r}")


def command(address, *parts, user=None):
    payload = f"*{len(parts)}\r\n".encode()
    for part in parts:
        value = str(part).encode()
        payload += f"${len(value)}\r\n".encode() + value + b"\r\n"

    with socket.socket(socket.AF_UNIX) as connection:
        connection.settimeout(5)
        connection.connect(str(address))
        if user is not None:
            auth = f"*3\r\n$4\r\nAUTH\r\n${len(user)}\r\n{user}\r\n$0\r\n\r\n".encode()
            connection.sendall(auth)
            with connection.makefile("rb") as stream:
                assert read_response(stream) == "OK"
        connection.sendall(payload)
        with connection.makefile("rb") as stream:
            return read_response(stream)


def verify(address, scripts):
    """독립 연결에서 점유를 유지하며 원자적인 상한·소유권·표식 보호 결과를 확인한다."""
    key = "coupon:admission:{issuance}:smoke"
    marker = "primary-smoke"

    def evaluate(name, *args):
        return command(address, "EVAL", scripts[name], 2, key, key + ":state", *args)

    assert evaluate("initialize", marker) == 1
    start = threading.Event()

    def acquire(index):
        start.wait()
        token = f"execution-{index}"
        result = evaluate("acquire", marker, token, 3)
        return token if result == 1 else None

    with ThreadPoolExecutor(max_workers=16) as pool:
        pending = [pool.submit(acquire, index) for index in range(60)]
        start.set()
        accepted = [token for future in pending if (token := future.result()) is not None]

    assert len(accepted) == 3
    assert command(address, "SCARD", key) == 3
    assert evaluate("acquire", marker, "fourth", 3) == 0
    assert evaluate("release", marker, "foreign-token") == 0
    assert command(address, "SCARD", key) == 3
    assert command(address, "PTTL", key) == -1

    for invalid_capacity in (0, "inf"):
        try:
            evaluate("acquire", marker, "invalid-capacity", invalid_capacity)
        except RuntimeError as failure:
            assert "INVALID_COUPON_PERMIT" in str(failure)
        else:
            raise AssertionError("Invalid capacity must fail before changing active tokens")
    assert command(address, "SCARD", key) == 3

    assert evaluate("acquire", marker, accepted[0], 3) == 1
    assert command(address, "SCARD", key) == 3
    for token in accepted:
        assert evaluate("release", marker, token) == 1
        assert evaluate("release", marker, token) == 0
    assert command(address, "SCARD", key) == 0
    assert evaluate("release", marker, marker) == 0
    assert command(address, "SCARD", key) == 0


def verify_faults(address, scripts):
    key = "coupon:admission:{issuance}:faults"
    marker = "primary-faults"

    def evaluate(name, *args):
        return command(address, "EVAL", scripts[name], 2, key, key + ":state", *args)

    assert evaluate("acquire", marker, "token", 3) == -1
    assert command(address, "EXISTS", key) == 0
    command(address, "SET", key, "wrong-type")
    assert evaluate("acquire", marker, "token", 3) == -1
    assert command(address, "GET", key) == "wrong-type"

    command(address, "DEL", key)
    assert evaluate("initialize", marker) == 1
    assert evaluate("acquire", marker, "token", 3) == 1
    command(address, "PEXPIRE", key, 60000)
    assert evaluate("acquire", marker, "another-token", 3) == -1
    assert command(address, "SCARD", key) == 1
    command(address, "PERSIST", key)
    command(address, "DEL", key + ":state")
    assert evaluate("acquire", marker, "token", 3) == -1
    # No issuance is running in this isolated test; explicit recovery may clear its stale token.
    command(address, "SET", "unrelated", "preserved")
    assert evaluate("initialize", "confirmed-primary") == 1
    assert command(address, "SCARD", key) == 0
    assert command(address, "GET", key + ":state") == "confirmed-primary"
    assert command(address, "GET", "unrelated") == "preserved"


def verify_ssot(address, scripts):
    """실제 발급 Lua의 한도·중복·Stream pending/ACK·부분 변경 fail-closed를 검증한다."""
    import uuid

    def scenario(label, limit):
        prefix = f"coupon:ssot:{{issuance}}:{label}"
        meta, campaign, events = (prefix + suffix for suffix in (":meta", ":campaign", ":events"))
        permits, state = prefix + ":active", prefix + ":state"

        def evaluate(name, keys, *args, user=None):
            return command(address, "EVAL", scripts[name], len(keys), *keys, *args, user=user)

        assert evaluate("prepare", [meta, campaign, events], 1, "campaign", "benefit", limit, 1, 1, "coupon-db") == "{}"
        assert evaluate("initialize", [permits, state], "primary") == 1

        def issue(user_id, user=None):
            token = str(uuid.uuid4())
            assert evaluate("acquire", [permits, state], "primary", token, 64) == 1
            result = json.loads(evaluate("issue", [meta, campaign, prefix + f":owner:{user_id}", events, permits, state],
                                         user_id, 1, "2026-11-13T00:00:00Z", token, "primary", user=user))
            assert evaluate("release", [permits, state], "primary", token) == 1
            return result

        return prefix, meta, campaign, events, evaluate, issue

    prefix, meta, campaign, events, evaluate, issue = scenario("quota", 5)
    start = threading.Event()

    def competing(user):
        start.wait()
        return issue(user)

    with ThreadPoolExecutor(max_workers=8) as pool:
        pending = [pool.submit(competing, user) for user in range(1, 9)]
        start.set()
        results = [future.result() for future in pending]
    assert sum(result["status"] == "CREATED" for result in results) == 5
    assert sum(result["status"] == "SOLD_OUT" for result in results) == 3
    assert command(address, "HGET", campaign, "issued") == "5"
    assert command(address, "XLEN", events) == 5
    first = next(result["coupon"] for result in results if result["status"] == "CREATED")
    duplicate = issue(first["userId"])
    assert duplicate["status"] == "EXISTING" and duplicate["coupon"] == first
    command(address, "HSET", campaign, "enabled", "0")
    assert issue(first["userId"])["coupon"] == first
    assert command(address, "XLEN", events) == 5

    def read():
        return json.loads(evaluate("events", [meta, events], 20, "coupon-db", "local"))

    records = read()
    assert len(records) == 5 and read() == records
    assert evaluate("ack", [meta, events], "coupon-db", records[0]["recordId"]) == "{}"
    assert len(read()) == 4
    for record in records[1:]:
        evaluate("ack", [meta, events], "coupon-db", record["recordId"])
    assert read() == [] and command(address, "XLEN", events) == 5

    owned = json.loads(evaluate("read", [meta, prefix + f":owner:{first['userId']}"], "owned", "", first["userId"]))
    assert owned == [first]
    assert json.loads(evaluate("read", [meta, prefix + ":owner:999"], "owned", "", 999)) == []
    # Owner metadata must detect a lost hash instead of admitting a duplicate.
    command(address, "DEL", prefix + f":owner:{first['userId']}")
    try:
        issue(first["userId"])
    except RuntimeError as failure:
        assert "COUPON_STATE_UNAVAILABLE" in str(failure)
    else:
        raise AssertionError("A lost owner hash must not admit duplicate issuance")

    # Reopening the semaphore must not alter coupons, quota, or Stream history.
    evaluate("initialize", [prefix + ":active", prefix + ":state"], "primary")
    assert command(address, "HGET", campaign, "issued") == "5"
    command(address, "DEL", campaign)
    try:
        evaluate("prepare", [meta, campaign, events], 1, "campaign", "benefit", 5, 1, 0, "coupon-db")
    except RuntimeError as failure:
        assert "COUPON_STATE_UNAVAILABLE" in str(failure)
    else:
        raise AssertionError("A lost registered campaign must never reset its quota")
    assert command(address, "EXISTS", campaign) == 0

    prefix, meta, campaign, events, evaluate, issue = scenario("wrongtype", 5)
    command(address, "SET", prefix + ":owner:1", "wrong-type")
    try:
        issue(1)
    except RuntimeError:
        pass
    else:
        raise AssertionError("Wrong type must fail before business writes")
    assert command(address, "HGET", campaign, "issued") == "0"
    assert command(address, "HGET", meta, "health") == "READY"

    # Denying only XADD triggers a real command error after quantity and owner writes.
    prefix, meta, campaign, events, evaluate, issue = scenario("partial", 5)
    command(address, "ACL", "SETUSER", "coupon-fault", "on", "nopass", "~*", "+@all", "-xadd")
    try:
        issue(1, user="coupon-fault")
    except RuntimeError as failure:
        # Redis 7.0과 8.x는 같은 Lua 내부 ACL 거절을 서로 다른 오류 문구로 반환한다.
        message = str(failure).lower()
        assert any(marker in message for marker in ("permission", "noperm", "can't run this command"))
    else:
        raise AssertionError("Denied XADD must cause an actual runtime error")
    assert command(address, "HGET", campaign, "issued") == "1"
    assert command(address, "HEXISTS", prefix + ":owner:1", "1") == 1
    assert command(address, "XLEN", events) == 0
    assert command(address, "HGET", meta, "health") == "DIRTY"
    for name, keys, args in [
        ("read", [meta], ["ready"]),
        ("events", [meta, events], [20, "coupon-db", "local"]),
        ("prepare", [meta, campaign, events], [1, "campaign", "benefit", 5, 1, 0, "coupon-db"]),
    ]:
        try:
            evaluate(name, keys, *args)
        except RuntimeError as failure:
            assert "COUPON_STATE_UNAVAILABLE" in str(failure)
        else:
            raise AssertionError("DIRTY authority must remain closed")
    evaluate("initialize", [prefix + ":active", prefix + ":state"], "primary")
    assert command(address, "HGET", meta, "health") == "DIRTY"


def main():
    """테스트 소유 Redis만 시작·종료하며 실제 스크립트의 원자 수용과 장애 시 결과를 검증한다."""
    executable = shutil.which("redis-server")
    if executable is None:
        raise RuntimeError("Install redis-server or run the Testcontainers integration suite")

    root = Path(__file__).resolve().parents[3]
    resources = root / "apps/commerce-api/src/main/resources/coupon"
    scripts = {name: (resources / f"{name}.lua").read_text() for name in ("initialize", "acquire", "release")}
    common = (resources / "ssot-common.lua").read_text()
    for name in ("prepare", "issue", "read", "events", "ack"):
        scripts[name] = common + "\n" + (resources / f"{name}.lua").read_text()

    with tempfile.TemporaryDirectory(prefix="c8-") as directory:
        address = Path(directory) / "r.sock"
        with (Path(directory) / "redis.log").open("w+") as log:
            server = subprocess.Popen(
                [executable, "--port", "0", "--unixsocket", str(address), "--save", "", "--appendonly", "yes", "--appendfsync", "always", "--maxmemory-policy", "noeviction"],
                cwd=directory,
                stdout=log,
                stderr=subprocess.STDOUT,
            )
            try:
                deadline = time.monotonic() + 5
                while True:
                    if server.poll() is not None or time.monotonic() >= deadline:
                        log.seek(0)
                        raise RuntimeError(log.read())
                    try:
                        if command(address, "PING") == "PONG":
                            break
                    except OSError:
                        pass
                    except RuntimeError as failure:
                        if not str(failure).startswith("LOADING"):
                            raise
                    time.sleep(0.01)

                verify(address, scripts)
                verify_faults(address, scripts)
                verify_ssot(address, scripts)
                server.terminate()
                server.wait(timeout=5)
                server = subprocess.Popen(
                    [executable, "--port", "0", "--unixsocket", str(address), "--save", "", "--appendonly", "yes", "--appendfsync", "always"],
                    cwd=directory, stdout=log, stderr=subprocess.STDOUT,
                )
                deadline = time.monotonic() + 5
                while True:
                    try:
                        if command(address, "PING") == "PONG":
                            break
                    except OSError:
                        pass
                    except RuntimeError as failure:
                        if not str(failure).startswith("LOADING"):
                            raise
                    if server.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError("AOF restart failed")
                    time.sleep(0.01)
                assert command(address, "HGET", "coupon:ssot:{issuance}:quota:meta", "sequence") == "5"
                assert command(address, "XLEN", "coupon:ssot:{issuance}:quota:events") == 5
                assert command(address, "HGET", "coupon:ssot:{issuance}:partial:meta", "health") == "DIRTY"
                print(json.dumps({"aof_restart": "passed", "contenders": 60, "capacity": 3, "accepted": 3, "ssot_quota": "5/8", "partial_write": "DIRTY", "stream_replay_ack": "passed", "checks": "passed"}))
            finally:
                server.terminate()
                server.wait(timeout=5)


if __name__ == "__main__":
    main()
