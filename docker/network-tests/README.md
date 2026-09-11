# Ambiente de teste dos protocolos de rede (T6/T7, Fase 0.4 Seções 2/3)

Servidores reais (via Docker) para os protocolos que `rust/protocols` implementa,
usados pelos testes de integração em `rust/protocols/tests/`. Existem para preencher
a lacuna documentada em `docs/phases/PHASE-0.1-MVP.md` (T6.1/T6.3/T7.1: "nunca testado
contra um servidor real") e, mais recentemente, o achado R-04 de
`docs/reports/PHASE-0.4-08-VERIFICACAO-PROFUNDA.md` (DASH e WebDAV tinham a mesma lacuna).

Não use manualmente — rode `./scripts/test-network-protocols.sh` na raiz do repo, que
gera as fixtures, sobe os containers, roda os testes Rust e derruba tudo.

## Serviços

| Serviço | Protocolo | Porta host | Credenciais |
|---|---|---|---|
| `smb-test` | SMB2/3 (`dperson/samba`) | 14450 (mapeada de 445) | `authshare`: user `vruser` / senha `vrpass123`. `guestshare`: sem autenticação. |
| `http-test` | HTTP puro (nginx) | 18080 | — |
| `https-test` | HTTPS com TLS auto-assinado (nginx) | 18443 | Certificado gerado em runtime, ver abaixo |
| `ftp-test` | FTP (`delfer/alpine-ftp-server`, vsftpd) | 12121 (mapeada de 21) + 12100-12110 (passivo) | user `vruser` / senha `vrpass123`, home `/ftp/vruser` |
| `sftp-test` | SFTP (`atmoz/sftp`, OpenSSH) | 12222 (mapeada de 22) | user `vruser` / senha `vrpass123` **ou** chave `ssh-keys/vrplayer_test_key` (as duas funcionam simultaneamente), home `/home/vruser`, fixture em `fixtures/` dentro do home |
| `webdav-test` | WebDAV (`bytemark/webdav`, Apache + mod_dav_fs) | 18099 | user `vruser` / senha `vrpass123` (Basic), dados em `fixtures/webdav/` (diretório próprio, não compartilhado com os outros serviços) |
| — (DASH) | MPD servido pelo próprio `http-test` | 18080 | Sem autenticação — `fixtures/dash/template/manifest.mpd` (SegmentTemplate) e `fixtures/dash/singlefile/manifest.mpd` (SegmentBase), gerados por ffmpeg a cada execução |

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
(`smbclient`, `curl --cacert`, e para o FTP um cliente Rust ad-hoc via `suppaftp`)
para confirmar que a sintaxe do `dperson/samba`, a config de TLS/range requests do
nginx e o modo passivo do `delfer/alpine-ftp-server` estavam corretos — os quatro
bateram sha256 idêntico ao arquivo original via leitura completa (FTP também via
range reads parciais simulando os seeks de cue do MKV, ver
`rust/protocols/tests/ftp_integration.rs`).

Para o `sftp-test`: validado manualmente com os clientes `ssh`/`sftp` reais do
sistema — `ssh -i ssh-keys/vrplayer_test_key -p 12222 vruser@127.0.0.1 -s sftp`
(autenticação por chave, sem senha) e uma sessão `sftp` interativa listando
`fixtures/` e lendo `testfile.bin` — antes de rodar a suite Rust
(`rust/protocols/tests/sftp_integration.rs`, 6 testes incluindo dois dedicados a
autenticação por chave), que também bateu sha256 idêntico via leitura completa,
blocos pequenos forçando múltiplos seeks reais no handle SFTP, e salto para trás
depois de leitura sequencial.

## Gotcha: modo passivo do FTP dentro do Docker

Como o doc (`PHASE-0.2-3D-NETWORK.md`, seção 6) avisa, modo ativo nunca funciona
atrás de NAT — mas modo passivo *dentro do Docker* tem sua própria armadilha: o
servidor FTP, ao responder a um `PASV`, anuncia o endereço IP em que o cliente deve
abrir a conexão de dados. Por padrão o `vsftpd` anunciaria o IP interno do container
(algo como `172.17.0.x`), que o cliente rodando no host não alcança — a conexão de
dados trava/expira mesmo com a porta de controle funcionando perfeitamente.
Resolvido fixando `ADDRESS=127.0.0.1` nas envs do `ftp-test`: como os testes deste
projeto *sempre* rodam no mesmo host que o Docker (nunca de outra máquina),
anunciar `127.0.0.1` funciona — o `docker-proxy` do Docker encaminha as portas
passivas mapeadas (`MIN_PORT`/`MAX_PORT` = 12100-12110) de volta para o mesmo
`127.0.0.1` que o cliente já usa para a porta de controle. Se algum dia isto
precisar rodar contra Docker remoto (CI em outra máquina, por exemplo), `ADDRESS`
teria que virar o IP realmente alcançável pelo cliente, não mais `127.0.0.1`.

## Gotcha: chroot e chave pública do `atmoz/sftp`

Duas armadilhas de permissão específicas dessa imagem (OpenSSH real, que é
bem mais rígido que `vsftpd`/`smbd` sobre isso):

1. **Chroot exige owner root no home.** OpenSSH recusa `ChrootDirectory` (o
   `atmoz/sftp` usa `%h`, i.e. `/home/vruser`) se esse diretório — ou
   qualquer um dos seus pais — não for dono `root:root` e não-gravável por
   grupo/outros ("bad ownership or modes for chroot directory", conexão cai
   silenciosamente do lado do cliente). O entrypoint da imagem já faz
   `chown root:root`/`chmod 755` em `/home/vruser` a cada subida, então a
   fixture **não pode** ser montada por cima do home em si — só um
   subdiretório dentro dele. Por isso `docker-compose.yml` monta
   `./fixtures:/home/vruser/fixtures:ro` (não `:/home/vruser:ro`), e os
   testes Rust listam/abrem arquivos dentro de `fixtures/...`, não na raiz —
   diferente de SMB/FTP, onde a fixture *é* a raiz.
2. **Convenção de chave pública:** `atmoz/sftp` não lê `authorized_keys`
   diretamente de um mount — ele concatena qualquer arquivo dentro de
   `~usuario/.ssh/keys/` num `authorized_keys` novo a cada subida (script
   `create-sftp-user` da própria imagem), com o `chown`/`chmod 600`
   corretos automaticamente. Por isso o mount é
   `./ssh-keys/vrplayer_test_key.pub:/home/vruser/.ssh/keys/vrplayer_test_key.pub:ro`
   (o nome do arquivo dentro de `keys/` não importa). A chave de teste
   (`ssh-keys/vrplayer_test_key{,.pub}`, ed25519, sem senha) é descartável,
   gerada só para este ambiente — nunca reaproveite uma chave real aqui.

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

## Gotcha: manifesto DASH `SegmentBase` é escrito à mão, não gerado pelo ffmpeg

`rust/protocols/src/dash` só entende `SegmentBase` com um `<Initialization range="...">` filho
(um único segmento de mídia = o arquivo inteiro após o init) — **não** `SegmentList` (múltiplas
`<SegmentURL>` explícitas), que é o que `ffmpeg -f dash -single_file 1` de fato produz. Em vez de
depender do XML exato que uma versão específica de ffmpeg gera para `SegmentList` (frágil entre
versões), `scripts/test-network-protocols.sh` concatena o init segment de vídeo
(`template/init-0.m4s`) com todos os chunks de vídeo já gerados para o manifesto
`SegmentTemplate` (`template/chunk-0-*.m4s`) num único arquivo fMP4 contínuo válido
(`singlefile/video.mp4` — init+moof/mdat concatenados é exatamente o que um `SegmentBase` de
arquivo único contém) e escreve `singlefile/manifest.mpd` à mão, com o byte-range do
`Initialization` computado do tamanho real do init segment (`stat -c%s`). Isso garante um MPD
`SegmentBase` sintaticamente correto independente da versão do ffmpeg instalada, testando o
código de produção real (achado R-02/R-03) contra um arquivo de mídia genuíno.

## Sintaxe de share do `dperson/samba`

Ordem dos campos em `-s`: `nome;path;browsable;readonly;guest;users;admins;writelist;comment`.
Fácil de errar (a ordem `readonly` antes de `guest` não é óbvia) — se mexer no
`docker-compose.yml`, valide de novo com `docker run --rm dperson/samba -h`.
