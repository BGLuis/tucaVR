# Security Policy

## Supported versions

Only the latest release and the current `main` branch receive security fixes.

## Reporting a vulnerability

Please do **not** open a public issue, pull request or discussion for a security vulnerability.

Report it privately through GitHub's [private vulnerability reporting](https://github.com/BGLuis/tucaVR/security/advisories/new) for this repository, and include:

- the affected version or commit,
- a description of the impact and the steps to reproduce it,
- any proof of concept, logs or media samples needed to trigger it.

You can expect an acknowledgement within a few days. Please allow a reasonable time for a fix before disclosing the issue publicly.

## Scope

Areas where a report is especially valuable:

- credential storage (`EncryptedSharedPreferences` in `app/src/main/java/com/tucavr/network/`) and credentials leaking into logs or URIs;
- the network protocol clients in `rust/protocols` (SMB, NFS, FTP, SFTP, WebDAV, DLNA, HTTP(S), HLS, DASH) handling malicious servers or manifests;
- demuxer and subtitle parsers handling malformed media files;
- the in-app web browser panel (GeckoView).
