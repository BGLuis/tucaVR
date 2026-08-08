#!/bin/sh
# Roda automaticamente pelo entrypoint oficial da imagem nginx (arquivos
# executaveis em /docker-entrypoint.d/ sao executados antes do nginx subir).
# Gera o certificado auto-assinado em /etc/nginx/tls (volume montado do
# host, ver docker-compose.yml) na primeira vez que o container sobe, para
# que o certificado publico fique disponivel no host apos o container
# iniciar.
set -e

TLS_DIR=/etc/nginx/tls
mkdir -p "$TLS_DIR"

if [ ! -f "$TLS_DIR/cert.pem" ] || [ ! -f "$TLS_DIR/key.pem" ]; then
    echo "[https-test] gerando certificado TLS auto-assinado..."
    # basicConstraints=CA:FALSE e obrigatorio aqui: sem isso, o OpenSSL deste
    # config (Alpine) marca certificados "-x509" auto-assinados com CA:TRUE por
    # padrao, e o rustls (cliente TLS usado pelo reqwest/protocols::http, ver
    # src/http.rs) rejeita isso como certificado de leaf/end-entity
    # (erro "CaUsedAsEndEntity") mesmo que curl/openssl aceitem sem reclamar.
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
