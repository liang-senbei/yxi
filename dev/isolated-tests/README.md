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

Verified on hk13: image source `4caf4f7` built after setting a UTF-8 locale; runner `09c6f59` validated Docker's canonical `CAP_` capability names without expanding the allowlist. An intentionally blocked pre-start attempt was removed, and the subsequent inspected container ran `RewindCommandStreamTest` with 3 executed tests, no failures or skips, then was removed. Evidence is retained under the run directories in `~/.cache/yxi-isolated-tests/`.

To validate a runner-only change against an already built, explicitly recorded image, set `YXI_TEST_IMAGE_REVISION` to its full commit SHA. Results record both runner and image revisions; this is not evidence for newer application code.

Remaining verification: runtime timeout/failure cleanup and migration of the quarantined real SSH/CLI test. The 3-test smoke run does not prove the complete rewind workflow.
