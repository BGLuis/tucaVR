# nginx with a self-signed TLS certificate, only for the T7.1/T7.2 integration tests
# (protocols::http::HttpsRangeSource). The certificate is generated at runtime
# (see entrypoint.sh) inside a volume mounted at /etc/nginx/tls, so that
# the public certificate is accessible on the host and the setup script
# can copy it to fixtures/cert.pem — used by the Rust test to
# trust it through VRPLAYER_TEST_CA_CERT (the "integration-tests" feature, see
# rust/protocols/src/http.rs). Generating it at build time (RUN openssl) would not work
# because the volume mounted at /etc/nginx/tls on `docker compose up` would hide
# the file generated in the image layer.
FROM nginx:1.27-alpine

RUN apk add --no-cache openssl

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY entrypoint.sh /docker-entrypoint.d/50-gen-tls-cert.sh
RUN chmod +x /docker-entrypoint.d/50-gen-tls-cert.sh
