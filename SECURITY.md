# Security Policy

## Supported versions

Security fixes are applied to the latest release on the `master` branch. Older builds should be
updated before a fix can be verified against them.

## Reporting a vulnerability

Please **do not open a public issue** for an undisclosed vulnerability.

Use GitHub's **Report a vulnerability** form under **Security** on the repository page. If that
option is unavailable, email `techflyermail@gmail.com` privately before publishing technical
details.

Include, when possible:

- the affected version or commit;
- the Android version and device ABI;
- reproduction steps and expected versus actual behavior;
- a crash log, screenshot, or proof-of-concept with secrets removed.

Do not attach production passwords, private keys, access tokens, or user files. You should receive
an acknowledgement within seven days. Please allow reasonable time for a fix and release before
public disclosure.

## Scope

Reports about remote-file authentication, path containment, web-share access control, host-key
verification, credential storage, Vault encryption, exported components, and URI grants are welcome.
Reports that require an already-compromised device, a malicious local administrator account, or
user-controlled access to unrestricted filesystem privileges are generally outside the project's
threat model.

The in-app terminal, PRoot, root/Shizuku access, and APK installation features are intentionally
power-user capabilities. Their presence does not by itself constitute a vulnerability.
