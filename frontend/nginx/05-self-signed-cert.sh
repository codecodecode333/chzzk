#!/bin/sh
# Generates a self-signed certificate at container start unless one is mounted at /etc/nginx/certs.
# Generated per container (not at build) so no private key is baked into the image.
set -eu

dir=/etc/nginx/certs
if [ -s "$dir/tls.crt" ] && [ -s "$dir/tls.key" ]; then
    exit 0
fi

mkdir -p "$dir"
openssl req -x509 -nodes -days 365 \
    -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
    -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
    -keyout "$dir/tls.key" -out "$dir/tls.crt" 2>/dev/null
echo "$0: generated a self-signed certificate for localhost"
