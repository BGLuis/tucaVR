#!/bin/sh
# Runs automatically through the nginx image's official entrypoint (executable
# files in /docker-entrypoint.d/ are run before nginx starts).
# It generates the self-signed certificate in /etc/nginx/tls (a volume mounted from
# the host, see docker-compose.yml) the first time the container starts, so that
# the public certificate is available on the host after the container
# starts.
set -e

TLS_DIR=/etc/nginx/tls
mkdir -p "$TLS_DIR"

if [ ! -f "$TLS_DIR/cert.pem" ] || [ ! -f "$TLS_DIR/key.pem" ]; then
    echo "[https-test] generating a self-signed TLS certificate..."
    # basicConstraints=CA:FALSE is mandatory here: without it, this OpenSSL
    # config (Alpine) marks self-signed "-x509" certificates with CA:TRUE by
    # default, and rustls (the TLS client used by reqwest/protocols::http, see
    # src/http.rs) rejects that as a leaf/end-entity certificate
    # ("CaUsedAsEndEntity" error) even though curl/openssl accept it without complaint.
    openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
        -keyout "$TLS_DIR/key.pem" \
        -out "$TLS_DIR/cert.pem" \
        -subj "/CN=localhost" \
        -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
        -addext "basicConstraints=critical,CA:FALSE" \
        -addext "keyUsage=critical,digitalSignature,keyEncipherment" \
        -addext "extendedKeyUsage=serverAuth" \
        2>/dev/null
fi
