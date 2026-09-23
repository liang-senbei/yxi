import copy
import pathlib
import unittest
from verify_container import verify


class ContainerBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.results = str(pathlib.Path("fixture-results").resolve())
        self.image = "sha256:" + "1" * 64
        self.safe = {
            "Image": self.image, "State": {"Status": "created", "Running": False},
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
        verify(self.safe, "run-1", self.results, self.image)

    def test_ssh_pty_audit_capability_requires_explicit_scope_and_label(self):
        candidate = copy.deepcopy(self.safe)
        candidate["HostConfig"]["CapAdd"].append("AUDIT_WRITE")
        with self.assertRaises(ValueError):
            verify(candidate, "run-1", self.results, self.image)
        with self.assertRaises(ValueError):
            verify(candidate, "run-1", self.results, self.image, ssh_pty=True)
        candidate["Config"]["Labels"]["org.yxi.ssh-pty"] = "true"
        verify(candidate, "run-1", self.results, self.image, ssh_pty=True)
        candidate["HostConfig"]["CapAdd"].append("SYS_ADMIN")
        with self.assertRaises(ValueError):
            verify(candidate, "run-1", self.results, self.image, ssh_pty=True)

    def test_docker_cap_prefix_preserves_the_same_allowlist(self):
        candidate = copy.deepcopy(self.safe)
        candidate["HostConfig"]["CapAdd"] = ["CAP_SETUID", "CAP_SETGID", "CAP_SYS_CHROOT"]
        verify(candidate, "run-1", self.results, self.image)
        candidate["HostConfig"]["CapAdd"].append("CAP_SYS_ADMIN")
        with self.assertRaises(ValueError):
            verify(candidate, "run-1", self.results, self.image)

    def test_host_namespaces_and_privileges_rejected(self):
        for field, value in [("NetworkMode", "host"), ("PidMode", "host"), ("IpcMode", "host"),
                             ("Privileged", True), ("RestartPolicy", {"Name": "always"}), ("CapAdd", ["SYS_ADMIN"]), ("PidsLimit", -1)]:
            with self.subTest(field=field):
                candidate = copy.deepcopy(self.safe)
                candidate["HostConfig"][field] = value
                with self.assertRaises(ValueError):
                    verify(candidate, "run-1", self.results, self.image)

    def test_host_home_or_socket_mount_rejected(self):
        for source in ["/root", "/tmp", "/var/run/docker.sock"]:
            candidate = copy.deepcopy(self.safe)
            candidate["Mounts"].append({"Type": "bind", "Source": source, "Destination": "/host", "RW": True})
            with self.assertRaises(ValueError):
                verify(candidate, "run-1", self.results, self.image)

    def test_wrong_owner_rejected(self):
        with self.assertRaises(ValueError):
            verify(self.safe, "different-run", self.results, self.image)

    def test_started_container_rejected(self):
        candidate = copy.deepcopy(self.safe)
        candidate["State"] = {"Status": "running", "Running": True}
        with self.assertRaises(ValueError):
            verify(candidate, "run-1", self.results, self.image)

    def test_wrong_image_rejected(self):
        with self.assertRaises(ValueError):
            verify(self.safe, "run-1", self.results, "sha256:" + "2" * 64)


if __name__ == "__main__":
    unittest.main()
