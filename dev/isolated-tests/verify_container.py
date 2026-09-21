"""Reject a test container before start if it can reach host namespaces or files."""
import json
import pathlib
import sys


def check(condition, message="Unsafe container configuration"):
    if not condition:
        raise ValueError(message)


def verify(container, run_id, results, native=None):
    config = container["HostConfig"]
    check(container["Config"].get("Labels", {}).get("org.yxi.isolated-test") == run_id)
    check(config["NetworkMode"] == "none")
    check(config.get("PidMode", "") == "")
    check(config["IpcMode"] == "private")
    check(not config.get("Privileged"))
    check(not config.get("PortBindings") and not config.get("PublishAllPorts"))
    check(not config.get("Devices") and not config.get("DeviceRequests") and not config.get("VolumesFrom"))
    check(set(config.get("CapDrop") or []) == {"ALL"})
    check(set(config.get("CapAdd") or []) <= {"SETUID", "SETGID", "SYS_CHROOT"})
    check("no-new-privileges" in (config.get("SecurityOpt") or []))
    check(0 < config["Memory"] <= 4 * 1024**3)
    check(config["MemorySwap"] == config["Memory"])
    check(0 < config["NanoCpus"] <= 2 * 10**9)
    check(0 < config["PidsLimit"] <= 512)
    expected = {"/results": (str(pathlib.Path(results).resolve()), True)}
    if native:
        expected["/opt/native/claude"] = (str(pathlib.Path(native).resolve()), False)
    actual = {}
    for mount in container.get("Mounts", []):
        check(mount["Type"] == "bind")
        check(mount["Destination"] not in actual, "Duplicate mount destination")
        actual[mount["Destination"]] = (mount["Source"], mount["RW"])
    check(actual == expected, "Unexpected host mount")


if __name__ == "__main__":
    inspect, run_id, results, *native = sys.argv[1:]
    items = json.loads(pathlib.Path(inspect).read_text())
    check(len(items) == 1)
    verify(items[0], run_id, results, native[0] if native else None)
    print("Container isolation configuration verified; no test has started yet")
