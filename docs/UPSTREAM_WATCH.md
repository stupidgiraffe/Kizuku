# Kizuku upstream radar

This is a **source intelligence feed**, not an automatic merge/update bot. The daily GitHub Action tracks Shizuku managers and forks, the Axeron/AxManager family, companion interfaces, and optional privilege-adjacent projects.

- **Seeded watchlist:** [tools/upstream-sources.json](../tools/upstream-sources.json) includes the supplied source URLs mapped to repositories and other verified reference projects.
- **Beyond the seed list:** discovery uses the Shizuku implementations section of [awesome-shizuku](https://github.com/timschneeb/awesome-shizuku), plus four GitHub repository searches. Results are filtered and capped; discovery is best effort, not exhaustive.
- **Daily comparison:** captures latest upstream HEAD and release tags, reviews intervening commit titles and release notes for Shizuku/Android/ADB/Axeron/security/UX changes, and keeps a history in [tools/upstream-state.json](../tools/upstream-state.json).
- **Pinned release review:** the same workflow checks stable and prerelease tags against [reviewed release pins](../upstream-releases.json). It records the tag's commit and updates one review issue when relevant source changes are found. A manual run can optionally download affected APKs as three-day CI artifacts.
- **Living report:** [UPSTREAM_RADAR.md](UPSTREAM_RADAR.md) is refreshed only for substantive HEAD/release/watchlist changes. The workflow summary includes counts and failures. No update means no empty daily commit.
- **Execution:** scheduled once daily around 11:23 JST, manually dispatchable under GitHub Actions, also triggered by edits to the watcher, manifest, or workflow. Runs only on GitHub's standard hosted runner using the repository's temporary GITHUB_TOKEN, with no external hosting, paid backend, signing keys, or phone connection.

**Review gate:** an upstream release/commit is not proof of correctness or feature parity. Before adopting code, verify exact diffs, relevant Android versions, license and transitive assets, and integration with Kizuku's one-host, Binder identity, capability-scoped grants, and native Axeron/Shizuku separation. The workflow leaves upstream APKs untouched and runs no Android builds.

Existing [UPSTREAMS.md](UPSTREAMS.md) remains the *manually audited integration/attribution ledger*; this radar does not overwrite it. Explicitly pin source revisions and update notices before any actual port.

For high-value changes, prioritize security/identity, full stock compatibility, Dhizuku/SU bridge boundaries, Axeron runtime and WebUI behavior, and recovery. Do not turn low-signal, unmaintained mirrors into dependencies merely to expand the source count.
