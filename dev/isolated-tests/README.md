# Dedicated integration-test container

This replacement runner is under validation. The quarantined host SSH tests remain disabled.

The build uses a clean Git commit, not the working directory or host Gradle cache. Building downloads dependencies but does not run tests. Test execution uses a separate container with no network interfaces except loopback, private PID/IPC namespaces, no privileged mode, bounded CPU/memory/process count, and no host HOME, `/tmp`, or Docker socket mounts. Only a fresh results directory and an optional read-only native CLI executable are mounted.

```sh
bash dev/isolated-tests/run.sh build
bash dev/isolated-tests/run.sh run app.yxi.desktop.RewindCommandStreamTest
```

For a subsequently reviewed real-CLI integration test, pass the native Linux executable as the final argument. Do not pass a launcher script, credentials, a user HOME, or additional mounts.

The runner inspects and validates the created container before starting it. Cleanup addresses the exact container ID and verifies its unique ownership label; it never sends host tmux commands. Skipped tests, missing XML, failures, timeouts, and unsuccessful container exits are not reported as passes.

Local guard validation (no containers or SSH processes are started):

```sh
python3 -m unittest discover -s dev/isolated-tests -p test_verify_container.py
```

Remaining verification: image build, an actual inspected container run, constrained cleanup under failure, and migration of the quarantined real SSH/CLI test. Passing the guard unit tests alone does not prove those items.
