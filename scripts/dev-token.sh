#!/usr/bin/env bash
# Mints a local development JWT.
#
# DEV ONLY. This signs with the symmetric HS256 secret in application.yml, which means this script --
# and anything else holding that secret -- can mint any token it likes. That is exactly why it is not
# how production works: there, an identity provider issues tokens signed with a private key nobody
# else has, and the services verify them against its public JWKS.
#
#   scripts/dev-token.sh                      # scope payments:write, for the customer-facing API
#   scripts/dev-token.sh ledger:internal      # for POST /internal/transfers
#   TTL_SECONDS=86400 scripts/dev-token.sh    # a longer-lived token
set -euo pipefail

SECRET="${JWT_HMAC_SECRET:-dev-only-hs256-secret-not-for-production-0123456789}"
ISSUER="${JWT_ISSUER:-payments-ledger-dev}"
SCOPE="${1:-payments:write}"
SUBJECT="${2:-dev-user}"
TTL="${TTL_SECONDS:-3600}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
exp=$((now + TTL))
header=$(printf '%s' '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"iss":"%s","sub":"%s","scope":"%s","iat":%s,"exp":%s}' \
  "$ISSUER" "$SUBJECT" "$SCOPE" "$now" "$exp" | b64url)
signature=$(printf '%s.%s' "$header" "$payload" \
  | openssl dgst -sha256 -hmac "$SECRET" -binary | b64url)

printf '%s.%s.%s\n' "$header" "$payload" "$signature"
