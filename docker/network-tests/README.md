# Ambiente de teste dos protocolos de rede (T6/T7)

Servidores reais (via Docker) para os protocolos que `rust/protocols` implementa,
usados pelos testes de integração em `rust/protocols/tests/`. Existem para preencher
a lacuna documentada em `docs/phases/PHASE-0.1-MVP.md` (T6.1/T6.3/T7.1: "nunca testado
contra um servidor real").

Não use manualmente — rode `./scripts/test-network-protocols.sh` na raiz do repo, que
gera as fixtures, sobe os containers, roda os testes Rust e derruba tudo.

## Serviços

| Serviço | Protocolo | Porta host | Credenciais |
|---|---|---|---|
| `smb-test` | SMB2/3 (`dperson/samba`) | 14450 (mapeada de 445) | `authshare`: user `vruser` / senha `vrpass123`. `guestshare`: sem autenticação. |
| `http-test` | HTTP puro (nginx) | 18080 | — |
| `https-test` | HTTPS com TLS auto-assinado (nginx) | 18443 | Certificado gerado em runtime, ver abaixo |

Todos servem o conteúdo de `fixtures/` (gerado pelo script de setup — um arquivo
binário aleatório, `testfile.bin`, com o `sha256` salvo ao lado).

## Certificado HTTPS

O `https-test` gera um certificado auto-assinado (`CN=localhost`) na primeira vez que
sobe, via `entrypoint.sh` rodando dentro do hook oficial `/docker-entrypoint.d/` da
imagem nginx — não no build da imagem, porque o volume montado em `/etc/nginx/tls`
esconderia qualquer coisa gerada em tempo de build. O certificado público fica em
`https-test/certs/cert.pem` (no host, via bind mount) e é passado para os testes Rust
como `VRPLAYER_TEST_CA_CERT`, usado por `protocols::http::base_client()` **apenas**
quando a feature de cargo `integration-tests` está habilitada (nunca em produção —
ver `rust/protocols/src/http.rs`).

## Validado manualmente nesta sessão

Antes de escrever os testes automatizados, cada serviço foi testado manualmente
(`smbclient`, `curl --cacert`) para confirmar que a sintaxe do `dperson/samba` e a
config de TLS/range requests do nginx estavam corretas — os três bateram sha256
idêntico ao arquivo original via leitura completa e via range reads parciais.

## Troubleshooting

**`Permission denied` ao apagar `https-test/certs/`**: o certificado é gerado
*dentro* do container como root, então o arquivo no host também fica com dono
root. `rm -rf` direto do seu usuário falha. Use:
```
docker run --rm -v "$(pwd)/https-test/certs:/certs" alpine sh -c "rm -rf /certs/*"
```
Isso só é necessário se quiser forçar a regeneração do certificado (ex: extensões
X.509 mudaram) — o script normal reaproveita o certificado existente entre execuções.

**`invalid peer certificate: Other(OtherError(CaUsedAsEndEntity))`** vindo do
`rustls`: o certificado foi gerado sem `basicConstraints=CA:FALSE` explícito.
O OpenSSL do Alpine, por padrão, marca certificados `-x509` auto-assinados como
`CA:TRUE` — `curl`/OpenSSL aceitam isso ao verificar, mas o `rustls` (usado pelo
`reqwest` em `protocols::http`) rejeita como leaf/end-entity cert. Corrigido em
`entrypoint.sh` com `-addext "basicConstraints=critical,CA:FALSE"` — se você
editar a geração do certificado, não remova essa extensão.

## Sintaxe de share do `dperson/samba`

Ordem dos campos em `-s`: `nome;path;browsable;readonly;guest;users;admins;writelist;comment`.
Fácil de errar (a ordem `readonly` antes de `guest` não é óbvia) — se mexer no
`docker-compose.yml`, valide de novo com `docker run --rm dperson/samba -h`.
