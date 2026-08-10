# Security policy

## Supported versions

Security fixes are provided for the latest `1.0.x` release.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting for this repository. Include the
affected version, impact, and a minimal synthetic reproduction. Do not include
credentials, patient data, production database contents, or other sensitive
information.

Do not open a public issue for a suspected vulnerability.

## Trust model

MigrationReplay is a local testing tool for trusted repository inputs. Its SQL
validation reduces accidental external effects but is not a security boundary
for malicious SQL. See [docs/safety-model.md](docs/safety-model.md) before using
the tool with third-party bundles.
