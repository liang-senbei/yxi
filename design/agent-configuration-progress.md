# Per-Agent configuration

User decision, 2026-09-22: changing runtime creates a new native conversation and imports a summary of the previous conversation. Preserve the original history. Do not attempt to resume Claude history as a Codex native session, or vice versa.

Required final flow: Agent menu → 配置 → runtime → saved provider profile → apply with process-specific isolation. Another Agent, including one in the same working directory, must keep its original provider. Host-global `Lines.apply` is not a valid implementation of this operation.

Initial draft implementation in 6d90d54:

- Menu entry and two-step draft selection for existing Claude/Codex profile catalogs.
- Server-side `~/.yxi/agent-bindings.json`, profile references only, desired and applied separate, compare-and-swap updates.
- Explicit draft-only wording. No running process is changed and no applied receipt is manufactured.
- Other runtime adapters are explicitly unavailable rather than represented as operational.

Still required before describing this feature as complete or shipping it as runtime switching:

- Codex and other runtime adapters for process-specific profile application; the Claude same-runtime path below is now implemented.
- Runtime-specific history reference and recovery, summary preparation/import, preservation of old conversation.
- Interrupted-application recovery and catalog revision tracking beyond the existing desired-selection CAS checks.
- Rewind integration: `Rewind.Capture` currently categorizes `--settings` as unpreserved, so normal automatic in-place rewind rejects the new launch context. Do not remove this guard without preserving the private settings and environment in rewind/relaunch. The original native rewind remains available.
- Full chooser UI interaction testing; current icon render test does not exercise this dialog.

## Claude process-specific application

Implemented and verified at `7a3f01bf7e91ea43062e8f34c69d1b4553244df2`:

- Apply a saved Claude profile to the selected idle native conversation through a private `~/.yxi/agent-settings/<uuid>.json`, mode 0600, and `--settings`.
- Preserve its original native session ID and current permission mode. Startup prompts are not resubmitted.
- Verify tmux identity, exact pane, original process and unchanged screen before restarting; verify a fresh live process token, settings hash, native registration and permission footer afterward.
- Secrets travel through SFTP and private restart capsules, not CLI arguments or model-menu responses.
- Clear inherited provider/model mapping values; do not write shared user/project settings.
- Read model-menu choices from that live Agent's private settings. Unverifiable managed settings fail explicitly rather than falling back to an unrelated global profile.
- Block application while a previous model switch is pending or uncertain.

Latest evidence (dedicated network-none container, real Claude 2.1.278, real SSH/SFTP and private tmux; HTTP endpoints are loopback fixtures):

- Build: `/root/.cache/yxi-isolated-tests/run.HZIVUy`.
- `ConversationRouteApplyTest`: `/root/.cache/yxi-isolated-tests/run.IaP2hj`; 1 passed, no failures or skips. Two real CLI instances share one project. Assertions cover distinct endpoint/key/model routing, preserved original history, unchanged second process and global settings, SSH reconnect, model-menu mapping, a second switch back, private-file permissions, and rejection of a pending model change before restart.
- Machine-readable assertion result: `/root/.cache/yxi-isolated-tests/run.IaP2hj/results/route-isolation-proof.json`.
- Existing model mapping parser regression: `/root/.cache/yxi-isolated-tests/run.sgLHQ3`.
- Earlier single-switch proof: `/root/.cache/yxi-isolated-tests/run.x2dGoz` at `c425242ba06601c0d00315848b617ea9f991ef06`.

This proves the same-runtime Claude application path, not cross-runtime switching, real provider availability, or complete UI acceptance. No production Agent was restarted during these tests.

Verification on hk13, isolated container, source 6d90d54afe76c4ff06c7a72433701a8f41491b33:

- Compile successful: `/root/.cache/yxi-isolated-tests/run.lTR2o8`.
- AgentBindingStoreTest: 3 passed, 0 skipped, `/root/.cache/yxi-isolated-tests/run.gkgCwr`.
- RunnerBrandIconTest rendered actual Compose controls, `/root/.cache/yxi-isolated-tests/run.xocSx9`. Visual inspection caught a wrong OpenAI docs book icon and poorly rendered SpaceXAI favicon; these assets were subsequently replaced.

Corrected icon verification, source 0bf1cbdd1baade1c3b11aa8ba0599d7b2729f303:

- Compile successful: `/root/.cache/yxi-isolated-tests/run.N8s4ta`.
- Actual Compose render passed and screenshot visually inspected: `/root/.cache/yxi-isolated-tests/run.z7sh9e/results/official-runtime-icons.png`.
- Codex uses the official OpenAI developer-site publisher mark; it is not claimed to be a Codex-specific logo. Grok uses the actual product-site mark. This screenshot verifies the light theme; dark-theme visual verification is still outstanding.

Provider model discovery verification at source 5b5e92b7989b74a631b89442093c83cc63c4c317:

- Production Python endpoint/HTTP tests: `/root/.cache/yxi-isolated-tests/run.COic5f` (wrapper runs 6 Python cases).
- Actual isolated SSH stdin transport: `/root/.cache/yxi-isolated-tests/run.tAOA90`.
- Qualified model IDs: `/root/.cache/yxi-isolated-tests/run.2sk7TJ`.

These changes are development commits, not part of published 1.4.12.
