# Dedicated integration-test container

## Current dependency baseline (2026-09-22)

Use the fixed, verified dependency image below for subsequent offline builds. Do not continually use each newly built application image as the next base: that preserves obsolete `/workspace` and Gradle layers and previously grew the image to about 13 GB.

```sh
YXI_TEST_BASE_IMAGE_REVISION=27a1a26cbd685a0cf8e883e0e278b8254a79cc3f bash dev/isolated-tests/run.sh build
```

On hk13, that image descends from the flattened `7a3f01bf7e91ea43062e8f34c69d1b4553244df2` image (`sha256:dbe8b2a1d1ac8e1bfa86655f276b353d52929eb65e75aea41bda9f32655fc821`). The flatten operation removed only container-local Gradle build-cache entries, preserved dependency artifacts and runtime configuration, and was followed by the real Claude isolation test in `run.A7FDtb` (passed). Application source must still be built and tested at its own commit; a dependency baseline is not evidence for newer source.

The verified export is retained on Windows at `C:\Users\dfhzw\Documents\ChatGPT\Yunxi\.artifacts\yxi-test-image-7a3f01b.tar.gz`; SHA-256 `0f24b362357a750f2478229e6d3aa4f41a54b87abd28e4bb7752254e895939ca`, 1,390,140,246 compressed bytes, 2,496,356,726 file bytes. Original/imported image metadata are beside it. Server audit: `~/.cache/yxi-isolated-tests/retired-test-images-1790071524108342303.json`. Only 82 unused images with the Yxi test ownership label and exclusively Yxi test tags were retired; container-referenced images were protected. No production images, volumes, release files or rollback packages were removed. An exact-ID build-cache prune reported 0 B; the freed space came from image retirement, not that prune.

Keep the fixed baseline and its verified export when retiring later application test images. Changing dependencies may require a separately reviewed baseline update. Runtime test isolation requirements below are unchanged.

## Runner and historical validation

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
