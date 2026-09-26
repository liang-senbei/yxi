# Cross-runner handoff: next implementation slice

Read-only audit, source HEAD `0821f392`; no runtime, SSH, account, or attachment operation performed.

## Requirement and current gap

`design/local-workspace-prd.md:94-98` requires a NEW native conversation when switching runners, editable reviewed summary, unchanged source history, mutual links, and explicit destination machine/provider before sharing context. Summary sections: user requirements, completed work, unfinished work, key files, assumptions, risks. This is not native cross-runner resume or memory-directory copying.

Current code has NO completed handoff workflow or durable source/destination relation:

- `AgentConfigurationDialog.kt:56` explicitly says cross-runner conversation/summary handoff is not connected. Only Claude-to-Claude route application is implemented. Save-draft is limited to Claude/Codex despite six-engine choices.
- `CodexAgentConfigurationDialog.kt:41` also leaves cross-runner mode disabled; `applyCurrentConfiguration` is same-runner reconnection, not handoff.
- `AgentBindingStore.kt` keys remote bindings by tmux session NAME and stores desired/applied engine/profile plus process identity. This is not a stable cross-runner logical Agent identity, and `markApplied` must not be used to claim a new runner merely because a draft was saved.
- `LocalWorkspacePane.kt` can create/open all four manager families but has no per-Agent runner-change flow or handoff links. Registry records distinguish native engine/session identities; retain that distinction.
- `InstructionQueue.sourceTask` already stores a provenance key, but alone has no bidirectional relation, summary review snapshot, target creation journal, or handoff lifecycle.

## Reusable entry points

1. UI: configuration dialogs above are remote trigger points; add local conversation action in `LocalWorkspacePane`/conversation header. Share one review dialog; do not hide cross-runner selection behind provider-only route apply.
2. Native destination creation (creates a new ID, does not submit summary automatically):
   - LocalClaudeTasks.create(runtime, directory, title)
   - LocalCodexTasks.create(runtime, directory, title, model)
   - LocalOpenCodeTasks.create(runtime, directory, title, model)
   - LocalAcpTasks.prepare(runtime, directory), native authentication when required, then create(title)
   - Remote CodexWorkspace.create(conn,...), RemoteOpenCodeTasks.create(conn,...), RemoteAcpTasks.prepare/create.
   - Remote terminal Claude must use the existing managed launch path and capture a verified new native identity; do not restart the source tmux session as if it were the new target.
3. History input: LocalClaudeHistory.page with stable cursors; CodexTaskController history loaded through readThread; OpenCodeTaskController.messages currently bounded to 200; AcpTaskController.messages is current controller history. These are NOT all equivalent full histories. Summary UI must say which range was used and explicitly load older records where available; never label last 100/200 messages as the entire conversation.
4. Delivery: InstructionQueue.enqueue(destinationKey, reviewedSummary, id=handoffDeliveryId, sourceTask=sourceKey). Existing expected-ID controller dispatch protection can carry the specific summary. Use explicit user-facing Create and send reviewed summary action; if only created/imported to draft, clearly report not yet delivered. Native completion/unknown rules stay in existing controllers.
5. Navigation: AppState.openNotifiedTask(fullKey) already routes local registry and remote tasks. Both links should store immutable full keys rather than title/current engine.
6. Persistence: DurableFile local journal and existing managers' creation journals; RemoteAtomicJson for remote binding changes. Keep desired/applied distinction and CAS validation.

## Minimal complete implementation sequence

A. Add durable HandoffRecord (new file/store): handoff ID; stable logicalAgentId; source full key/native identity/engine/host/directory; target requested host/runtime/provider reference/model; summary reviewed text and content digest; source snapshot/range digest; stage; destination full key/native ID once known; delivery ID; timestamps. No credentials. Stages Draft -> Creating -> Created -> Queued -> Completed or Unknown/Failed. Record Creating BEFORE native creation. Crash or lost create receipt is Unknown; never create another destination automatically.

B. Introduce explicit logical Agent identity binding source and resulting sessions. Existing remote tmux name is display/legacy lookup only. Keep source accessible, link both native task keys, and switch Agent's active task pointer only after destination identity and provider have been verified. Failure leaves source active. Do not overwrite old applied runtime identity with a different session without a distinct relation.

C. Shared handoff review dialog: select destination runner and saved profile/runtime; show machine, working directory, actual provider/model; present six editable summary sections and selected source message range. Attachments excluded by default from the summary transfer, with visible selection/exclusion controls when supported. A deterministic transcript extract may seed fields but cannot invent completed-work facts; an optional AI-generated summary must use an explicitly chosen permitted local/source runner, not silently send source data to the destination during preview.

D. Destination adapter uses each existing create manager. It verifies selected profile is actually applicable; today's local Claude manager enforces official:claude and ACP records provider=native, so do not promise arbitrary saved-provider application yet. Disable unsupported profile combinations with a concrete reason while extending those adapters. After create receipt, persist target identity/relation BEFORE summary enqueue. Retry resumes the recorded stage, never duplicates creation or sends with a fresh delivery ID.

E. Queue reviewed text as context with clear provenance and user requirements separated from quoted history. Add sourceTask relation, use exact-ID send, preserve approval semantics, and derive delivery/completion from native receipts. New history remains native to destination. Both pages show “source conversation”/“continued in ...” links, surviving restart and target rename. User edits after queueing cannot silently alter submitted content.

F. Apply same orchestrator to remote configuration dialogs and local actions. A first local Claude↔Codex vertical slice can validate the engine-independent transaction, but is not completion of six-runner/remote PRD. Track remaining adapters explicitly.

## Required verification (bounded, not duplicate unit mirrors)

- Closed/failed target creation and lost receipt: no duplicate session on retry/restart; source remains intact.
- Summary edited before submit reaches exactly the new native ID once; original source never receives it.
- Unreadable/partial source history or changed snapshot forces review; range limitations visible.
- Native provider/profile changed during review is rechecked; no false “applied”.
- Approval waits remain interactive; unknown delivery is not automatically retried; target history association persists.
- Local and remote full-key navigation after rename/restart uses captured identities.
- Real native runner tests run only in isolated containers with fake loopback providers; Windows UI review/create/cancel/link acceptance separately. Never reuse production SSH/tmux AI sessions for tests.

## Highest-risk implementation gaps

1. Stable logical Agent identity is missing: simply saving desired.engine is not a working runner switch.
2. Two independent durable stores (manager creation + handoff) require reconciliation; attaching a target after a lost receipt cannot rely on title search.
3. Source history coverage differs across runners; recent window extraction must not be mislabeled full summary.
4. Official vs native/third-party provider validation differs across adapters; shared UI cannot assert support ahead of backend evidence.
