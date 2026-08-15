# Security Policy

## Reporting a vulnerability

Please report security issues privately to **taisoluciones@gmail.com** (do not open a public issue). Include:

- The affected version / commit.
- Steps to reproduce.
- Impact and any suggested fix.

You will receive an acknowledgement within a few days.

## Security notes for this project

- The app **never stores node private keys** and never writes secrets to disk. PKI admin keys are read from the node (read-only display).
- Destructive NavaTastic commands are always gated behind the `CONFIRMAR` dialog; do not bypass it.
- Control commands to NavaTastic repeaters require a valid PKI admin key (firmware-side validation).
- When building or contributing: **never commit private keys, node-card URLs, key material, or personal data**. The repo's `.gitignore` excludes session artifacts (`testplan/`, snapshots, backups, local SDK paths). If you must share a baseline, strip `privateKey`/`publicKey` first.
- GitHub tokens must never be placed in source files, logs, or commit messages.
