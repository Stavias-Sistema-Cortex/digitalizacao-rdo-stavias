# Cortex 3 secret and key audit

The release keeps credential material outside source and frontend bundles.
Production expects mounted files for CPF HMAC, OTP HMAC, offline-grant signing,
and SMTP credentials. The web bundle receives only the offline public-key
fingerprint, never private key material.

Validation commands:

```bash
bash scripts/security/scan-cortex-secrets.sh
mvn -o -f apps/api/pom.xml -Dtest=SecretMaterialLoaderTest,CpfLookupDigestConfigurationTest,OfflineGrantDeploymentConfigurationTest,OfflineGrantKeyConfigurationTest,OtpSecurityConfigurationTest,EmailConfigurationTest,ProductionSecurityContractTest test
npm --prefix apps/web run build
```

The scanner reports only file, line, detector name, and a truncated SHA-256
fingerprint of the matching line. It never prints a candidate value. Archived
content, test fixtures, documentation, skills, dependencies, targets, and prior
build output are excluded from literal-secret findings. Tracked runtime
configuration is scanned, and the freshly generated frontend bundle is scanned
in a separate pass.

Production authentication is PostgreSQL OTP/passkey mode. Direct CPF login is
disabled for every `production` profile, the web image requires an explicit
authentication mode, and the example proxy uses a fixed internal subnet that
is the only trusted forwarded-address source.
