# Security Policy

## Supported versions

Only the **latest release** receives security fixes. Always update to the newest
APK from the [Releases page](https://github.com/yanisplugg/olcvpn-client/releases/latest)
before reporting an issue.

| Version | Supported |
|---------|-----------|
| latest release | ✅ |
| older releases | ❌ |

## Reporting a vulnerability

**Please do not open a public issue for security vulnerabilities.**

Report privately through GitHub's
[**Security Advisories**](https://github.com/yanisplugg/olcvpn-client/security/advisories/new)
("Report a vulnerability"). Include:

- a description of the issue and its impact,
- steps to reproduce or a proof of concept,
- the app version and your device / Android version.

We aim to acknowledge reports within a few days and will keep you updated on the fix.
Please give us reasonable time to release a patch before any public disclosure.

## Scope

This is a circumvention tool. Especially valuable reports include:

- traffic, DNS, or IP leaks outside the tunnel (IPv4 **or** IPv6),
- fingerprints that let an observer reliably distinguish YPtun traffic,
- crashes or memory issues reachable from untrusted subscription/config input,
- mishandling of user secrets (configs, keys, cookies) on the device.

Thank you for helping keep users safe. 🛡️
