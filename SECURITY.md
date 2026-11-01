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

No production key has been created. Before the first durable release, generate one
Kizuku-specific signing identity, store it securely in APIVault, configure encrypted
CI variables, and publish its SHA-256 certificate fingerprint here. Never use the
unrelated Reseam key. Never commit a private key, keystore, password or ADB token.

Report vulnerabilities privately through the repository owner's GitHub contact.
Do not publish ADB pairing secrets or unredacted diagnostic output in an issue.
