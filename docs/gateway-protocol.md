# Remote gateway compatibility

## Evidence and target

The Android adapter follows Hermes's canonical session-authority remote API, not the legacy standalone sidecar dialect. Source was inspected on 2026-11-13 under `/opt/hermes`, including:

- `tui_gateway/ws.py` — authenticated WebSocket transport and newline-coalesced notifications.
- `tui_gateway/contracts/canonical.py` and `canonical_sessions.py` — runtime description, session snapshots, admissions.
- `gateway/session_controls.py` and `session_authority.py` — implemented runtime capabilities and admission receipts.
- `tui_gateway/contracts/events.py` — message and tool event envelopes.
- `apps/shared/src/gateway-canonical.ts` and Desktop connection configuration — existing client behavior.

The installed source tree has no Git metadata; its complete version is not established. The research comparison used upstream commit `5163cc678bf38cb6b9e730716ca665914fccc0ad`; the installed `tui_gateway/ws.py` matched that revision, but other files differed. Do not claim the entire installed tree is that commit. The newer upstream session-list pagination is deliberately not assumed.

Reference: official Hermes docs, `/docs/user-guide/desktop` and `/docs/user-guide/multi-connection-desktop` at `hermes-agent.nousresearch.com`.

## Implemented subset

1. Validated HTTPS root origin; WebSocket upgrade at `/api/ws?token=<encoded gateway session token>` using standard TLS verification. No redirects or automatic connection retries.
2. Require `gateway.ready.payload.session_authority == true` and successful `runtime.describe` with `durable-admission-v1`, `event-replay-v1`, GUI creation source, and advertised creation parameters.
3. `session.list` with `scope=stored`, `limit=100`, `include_hidden=false`. This is a bounded recent list, not all server history.
4. `session.create` with a fresh request UUID, `source=gui`, and a title only if advertised.
5. `session.resume` for persisted message content. Structured text parts are read; non-text/image/tool blocks are not rendered as an invented assistant response.
6. Before sending, resume the authoritative session and refuse another submission while it is running, queued, or awaiting interaction.
7. `prompt.submit` with a fresh input UUID. Its receipt is an admission acknowledgement, never assistant completion. No automatic prompt retries.
8. Consume session-filtered `message.delta`, `message.complete`, tool start/progress/complete, and interaction/error events. Reconcile final text, reject failed/interrupted completion, deduplicate sequence numbers within the snapshot epoch, and honor UTF-16 offsets. Events arriving before the receipt are buffered; admission/generation IDs are checked when supplied.
9. Canonical approvals/clarifications are reported as requiring desktop interaction. Server-initiated interactive RPC requests get an explicit unsupported-method response, never an automatic approval.
10. Socket failure, client close, or timeouts unblock waiting calls. Disconnecting this viewer does not send a session interrupt.

## Deliberately unsupported

- Legacy sidecar RPC dialect, native browser PKCE/refresh, reverse-proxy path prefixes, multi-profile selection.
- Automatic reconnect and event replay. Negotiated replay capability is a compatibility gate, not a claim that Android reconnect recovery is implemented.
- Server-side approval/clarification controls, stop/cancel, attachments, voice, artifacts, notifications.
- Rendering every structured transcript type or all Desktop features.

A timed-out or disconnected prompt may continue on the server. Reconnect and inspect the authoritative session before deciding whether to send another message.

## Test boundary

`HermesGatewayTest` drives real OkHttp WebSocket frames against a local MockWebServer fixture. Its injected test factory rewrites the upgrade to a loopback test server; production has no cleartext switch or trust-all certificate client. Those tests exercise protocol handling, not successful access to a real Hermes deployment.

The home-server address and authentication deployment have not been provided. A live HTTPS/authentication/session/chat test remains required before claiming end-to-end integration with Dan's server. No home-server settings or credentials were read or changed.
