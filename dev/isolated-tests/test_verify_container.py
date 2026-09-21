import copy
import pathlib
import unittest
from verify_container import verify


class ContainerBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.results = str(pathlib.Path("fixture-results").resolve())
        self.safe = {
            "Config": {"Labels": {"org.yxi.isolated-test": "run-1"}},
            "HostConfig": {
                "NetworkMode": "none", "PidMode": "", "IpcMode": "private",
                "Privileged": False, "CapDrop": ["ALL"], "CapAdd": ["SETUID", "SETGID", "SYS_CHROOT"],
                "SecurityOpt": ["no-new-privileges"], "Memory": 4 * 1024**3,
                "MemorySwap": 4 * 1024**3, "NanoCpus": 2 * 10**9, "PidsLimit": 512,
            },
            "Mounts": [{"Type": "bind", "Destination": "/results", "Source": self.results, "RW": True}],
        }

    def test_expected_boundary(self):
        verify(self.safe, "run-1", self.results)

    def test_host_namespaces_and_privileges_rejected(self):
        for field, value in [("NetworkMode", "host"), ("PidMode", "host"), ("IpcMode", "host"),
                             ("Privileged", True), ("CapAdd", ["SYS_ADMIN"]), ("PidsLimit", -1)]:
            with self.subTest(field=field):
                candidate = copy.deepcopy(self.safe)
                candidate["HostConfig"][field] = value
                with self.assertRaises(ValueError):
                    verify(candidate, "run-1", self.results)

    def test_host_home_or_socket_mount_rejected(self):
        for source in ["/root", "/tmp", "/var/run/docker.sock"]:
            candidate = copy.deepcopy(self.safe)
            candidate["Mounts"].append({"Type": "bind", "Source": source, "Destination": "/host", "RW": True})
            with self.assertRaises(ValueError):
                verify(candidate, "run-1", self.results)

    def test_wrong_owner_rejected(self):
        with self.assertRaises(ValueError):
            verify(self.safe, "different-run", self.results)


if __name__ == "__main__":
    unittest.main()
