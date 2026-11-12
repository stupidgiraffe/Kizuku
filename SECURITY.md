# Security and signing

This repository is under development. Do not use this foundation as a trusted
production privilege broker. Unsupported operations fail explicitly.

Grants are bound to Android UID, package membership and SHA-256 signing identities.
Shared-UID packages share a Linux security principal: the UI must disclose all
members. Certificate changes currently require reauthorization; signing-lineage
rotation and permission migration need dedicated acceptance tests before release.

Debug APKs use Android's development signing key. A CI debug identity is not a
permanent release identity and may differ between runs. Uninstalling loses app
data; do not present debug builds as a durable update channel.

Permanent Kizuku signing identity (RSA-4096, alias `kizuku`, created 2026-11-02 JST):

```
SHA-256: 83c9b70c57b2666f8eb48f0cfe74013053c804fd0b452addfdeed451629d2bce
```

The private keystore is held only in encrypted GitHub Actions secrets and an
independently encrypted recovery backup outside git. APIVault holds the recovery
key (`Kizuku backup key`) and keystore password (`Kizuku signing password`).
APIVault's API rejects larger payloads, so the authenticated encrypted backup is
stored separately as `Kizuku-signing-v1.encrypted.json` in the owner's files.
Decrypt using PyNaCl SecretBox and the base64 recovery key; the resulting JSON
contains the PKCS12 base64, alias, passwords and certificate fingerprint. Recovery
was byte-verified before saving. The current MCP connection lacks `keys:reveal`;
recovery requires the owner UI or a connection authorized to reveal those records.
This does not block builds using the encrypted GitHub secrets. Never regenerate this identity or use another
project's key. No private signing material is in the repository.

Signed release publication is manual and depends on build, lint, unit tests,
and runtime acceptance of the same signed APKs as UID 2000 and UID 0 on
Android 16 and 17, including an in-place upgrade from the published Preview 2.
The compatibility APK is built with the same certificate and bundled byte-for-byte.

Report vulnerabilities privately through the repository owner's GitHub contact.
Do not publish ADB pairing secrets or unredacted diagnostic output in an issue.
