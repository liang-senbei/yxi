# Dedicated integration-test container

Full builds now include the desktop JRE and CJK fonts for actual Compose window fixtures. To upgrade an older headless dependency image, explicitly set `YXI_TEST_INSTALL_GUI=1` together with `YXI_TEST_BASE_IMAGE_REVISION` for that one build. This enables package downloads during building only; runtime containers remain network-isolated. Subsequent builds from the upgraded image can use the normal offline incremental path. `RewindDialogUiTest` renders the actual shared editor at two widths and verifies Escape cancels without invoking an action; its screenshots are component evidence, not Windows application acceptance.

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

Image `6253431` was rebuilt offline from validated dependency image `4caf4f7` using `YXI_TEST_BASE_IMAGE_REVISION`. In the resulting network-isolated container, `IsolatedSshTransportTest` ran one real OpenSSH/JSch test with a pinned generated host key, strict filesystem permissions, password authentication disabled, and a forced private HOME. SSH connection and UTF-8 file write/read succeeded; XML reported 1 executed, 0 failures, 0 skips. The exact labelled container was removed. Evidence: `run.Ht4l7s` in the private test cache. No tmux or agent process was started by this test.

Image `a9d53aa` extends that smoke test with a private `TMUX_TMPDIR`. It creates one inert `sleep` session inside the container, reads back `#{socket_path}` and checks the exact fixture-owned socket, then kills only that named session using explicit `-S`. XML in `run.2sm447` reports 1 executed, 0 failures, 0 skips; container removal was independently checked. This validates the fixture transport and targeted cleanup, not Claude or the full rewind workflow.

Image `2f69ecb`, with the existing native Claude 2.1.278 executable mounted read-only, passed `IsolatedNativeCliTest` (1 executed, no failures/skips). The real CLI created a conversation, resumed its returned session ID in a second process, and sent both the first and follow-up markers to the loopback stub using only fake authentication. The container had no external network and was removed afterward. Evidence: `run.FS9WL1`. This verifies native CLI persistence and the stub protocol, not history rollback or the app's complete workflow.
