# Kizuku engineering contract

- Read `docs/ARCHITECTURE.md`, `docs/STATUS.md`, `docs/UPSTREAMS.md` before changes.
- Plane Kizuku project `570b46d9-5e4b-49d9-ad57-0f775c90f8df` is the work board.
  Mainline item: `68ff91b5-1e6c-4a37-b755-8d284b4fffb4`. Preserve the separate DexorX item.
- Deliver working vertical paths. No wholesale fork imports or speculative layers.
- Preserve the manager identity and stock wire compatibility. Every privileged
  operation validates Android's UID, installed certificates and its own capability.
- Never infer native shell/runtime authority from a Shizuku grant.
- One activation/lifecycle controller; distinguish launching, connected, stopped,
  disconnected and failed. Never show an active mock service.
- Keep production keys out of git. No scheduled Actions or external cloud backend.
- Run `./gradlew :core:test :manager:assembleDebug :test-client:assembleDebug
  :manager:lintDebug :test-client:lintDebug :compat:lintDebug` for changes.
- `scripts/emulator-smoke.sh` exercises real cross-UID Binder/authorization paths.
  Compiling is not runtime acceptance. Record exactly which device/API was tested.
- Do not install or change the user's physical phone without explicit authorization.
- Add exact upstream revisions and notices when copying code. Keep edits to vendored
  protocol sources minimal and document them. No upstream artwork/branding reuse.
- Keep handoff state and meaningful verification in `docs/STATUS.md`; update the
  existing Plane item at milestones rather than generating tiny duplicate tasks.
