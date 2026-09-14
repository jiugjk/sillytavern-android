# Diagnostic TLS fixture

`localhost-key.pem` is a deliberately public, throwaway test key, **not a production secret**.
The self-signed certificate covers only `localhost`. It is used by the runtime probe to
check both certificate-chain and hostname validation. Never use it for production HTTPS.
The certificate is time-bounded; regenerate the fixture when it expires rather than
turning off TLS verification. No test calls `rejectUnauthorized: false`.
