# nginx com TLS auto-assinado, so para os testes de integracao de T7.1/T7.2
# (protocols::http::HttpsRangeSource). O certificado e gerado em runtime
# (ver entrypoint.sh) dentro de um volume montado em /etc/nginx/tls, para
# que o certificado publico fique acessivel no host e o script de setup
# possa copia-lo para fixtures/cert.pem — usado pelo teste Rust para
# confiar nele via VRPLAYER_TEST_CA_CERT (feature "integration-tests", ver
# rust/protocols/src/http.rs). Gerar no build (RUN openssl) nao funcionaria
# porque o volume montado em /etc/nginx/tls no `docker compose up` esconderia
# o arquivo gerado na camada da imagem.
FROM nginx:1.27-alpine

RUN apk add --no-cache openssl

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY entrypoint.sh /docker-entrypoint.d/50-gen-tls-cert.sh
RUN chmod +x /docker-entrypoint.d/50-gen-tls-cert.sh
