# Per-Agent configuration

User decision, 2026-09-22: changing runtime creates a new native conversation and imports a summary of the previous conversation. Preserve the original history. Do not attempt to resume Claude history as a Codex native session, or vice versa.

Required final flow: Agent menu → 配置 → runtime → saved provider profile → apply with process-specific isolation. Another Agent, including one in the same working directory, must keep its original provider. Host-global `Lines.apply` is not a valid implementation of this operation.

Implemented in 6d90d54:

- Menu entry and two-step draft selection for existing Claude/Codex profile catalogs.
- Server-side `~/.yxi/agent-bindings.json`, profile references only, desired and applied separate, compare-and-swap updates.
- Explicit draft-only wording. No running process is changed and no applied receipt is manufactured.
- Other runtime adapters are explicitly unavailable rather than represented as operational.

Still required before describing this feature as complete or shipping it as runtime switching:

- Process-specific application of saved settings and verification against a fresh process identity (tmux identity alone survives respawn).
- Runtime-specific history reference and recovery, summary preparation/import, preservation of old conversation.
- Two actual agents using different providers in the same project, including reconnect/restart and stale-receipt coverage.
- Full chooser UI interaction testing; current icon render test does not exercise this dialog.

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
