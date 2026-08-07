# Fase 0.2 — 3D & Network

> **Objetivo**: Adicionar reprodução de conteúdo 3D/VR (SBS, OU, 360°, 180°), protocolos de rede adicionais (NFS, FTP, SFTP, DLNA, HLS), legendas, busca na biblioteca e monitoramento térmico.  
> **Pré-requisito**: Fase 0.1 completa e estável.  
> **Resultado esperado**: O usuário pode assistir conteúdo VR imersivo de qualquer fonte de rede comum, com legendas e uma experiência fluida.

---

## 📋 Índice

1. [Reprodução 3D — Side-by-Side e Over/Under](#1-reprodução-3d--side-by-side-e-overunder)
2. [Reprodução 360° e 180° VR](#2-reprodução-360-e-180-vr)
3. [Auto-detecção de Formato 3D](#3-auto-detecção-de-formato-3d)
4. [Head Tracking para Conteúdo 360°](#4-head-tracking-para-conteúdo-360)
5. [Protocolo NFS](#5-protocolo-nfs)
6. [Protocolos FTP e SFTP](#6-protocolos-ftp-e-sftp)
7. [DLNA/UPnP](#7-dlnaupnp)
8. [HLS Streaming](#8-hls-streaming)
9. [Legendas (SRT, VTT)](#9-legendas-srt-vtt)
10. [Descoberta Automática de Servidores](#10-descoberta-automática-de-servidores)
11. [Gerenciamento de Conexões](#11-gerenciamento-de-conexões)
12. [Busca e Filtros na Biblioteca](#12-busca-e-filtros-na-biblioteca)
13. [Metadados de Mídia](#13-metadados-de-mídia)
14. [Monitoramento Térmico](#14-monitoramento-térmico)
15. [Cuidados Transversais da Fase 0.2](#15-cuidados-transversais-da-fase-02)

---

## 1. Reprodução 3D — Side-by-Side e Over/Under

### Conceito

Vídeos 3D estereoscópicos empacotam dois "olhos" em um único vídeo:

```
Side-by-Side (SBS):                Over/Under (OU):
┌────────┬────────┐               ┌────────────────┐
│  Olho  │  Olho  │               │   Olho Left    │
│  Left  │  Right │               ├────────────────┤
└────────┴────────┘               │   Olho Right   │
                                  └────────────────┘

Half SBS: cada metade tem metade da resolução horizontal
Full SBS: cada metade tem resolução completa (vídeo tem 2x width)
```

### Tarefas

- [ ] **T1.1** — Implementar shader de separação SBS (C++/GLSL):
  ```glsl
  // Vertex shader — renderiza quad diferente para cada olho
  #extension GL_OVR_multiview2 : require
  layout(num_views = 2) in;
  
  out vec2 vTexCoord;
  
  void main() {
      // Para olho esquerdo (view 0): UV.x = [0.0, 0.5]
      // Para olho direito (view 1): UV.x = [0.5, 1.0]
      float xOffset = float(gl_ViewID_OVR) * 0.5;
      vTexCoord = vec2(texCoord.x * 0.5 + xOffset, texCoord.y);
      gl_Position = viewProjection[gl_ViewID_OVR] * modelMatrix * vec4(position, 1.0);
  }
  ```
- [ ] **T1.2** — Implementar shader de separação OU (C++/GLSL):
  ```glsl
  void main() {
      // Para olho esquerdo (view 0): UV.y = [0.0, 0.5] (metade superior)
      // Para olho direito (view 1): UV.y = [0.5, 1.0] (metade inferior)
      float yOffset = float(gl_ViewID_OVR) * 0.5;
      vTexCoord = vec2(texCoord.x, texCoord.y * 0.5 + yOffset);
      gl_Position = viewProjection[gl_ViewID_OVR] * modelMatrix * vec4(position, 1.0);
  }
  ```
- [ ] **T1.3** — Suportar variantes Half e Full:
  - **Half SBS/OU**: Aspect ratio do quad baseado em metade da resolução
  - **Full SBS/OU**: Cada olho recebe resolução completa
  - Detecção: Full SBS tem width ≥ 3840 com aspect ~32:9; Half SBS tem aspect ~16:9
- [ ] **T1.4** — Implementar seleção de modo no UI:
  - Botão de ciclo: `2D → SBS → SBS(half) → OU → OU(half) → 2D`
  - Ícone visual indicando o modo ativo
  - Persistir preferência por arquivo no histórico
- [ ] **T1.5** — Swap eyes: Opção para inverter olho esquerdo/direito (alguns conteúdos estão invertidos)

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Olho trocado causa náusea**: Se o olho esquerdo recebe a imagem do direito e vice-versa, o efeito de profundidade se inverte (objetos próximos parecem distantes) e pode causar desconforto forte. SEMPRE ofereça o botão "swap eyes" de fácil acesso.

> [!WARNING]
> **Aspect ratio em Half SBS**: Um vídeo Half SBS de 1920x1080 tem aspect ratio aparente 16:9, mas cada olho tem 960x1080 (quase 1:1). O quad 3D deve renderizar em 16:9, não em 1:1. Calcule: `display_aspect = (width / 2) * sar / height` para Half SBS.

> [!IMPORTANT]
> **IPD (Interpupillary Distance)**: O Quest 3 ajusta IPD automaticamente. NÃO aplique ajuste manual de IPD nos UVs do shader 3D. Use as poses fornecidas pelo OpenXR (`XrView.pose`) que já incorporam o IPD do usuário.

---

## 2. Reprodução 360° e 180° VR

### Conceito

Conteúdo 360° e 180° é projetado em uma esfera (ou semi-esfera) ao redor do usuário:

```
360° Equirectangular:                180° VR:
    ┌────────────────────┐           ┌──────────┐
    │ Projeção 360° em   │           │ Meia     │
    │ textura retangular │           │ esfera   │
    │ (2:1 aspect ratio) │           │ frontal  │
    └────────────────────┘           └──────────┘
    → mapeada em esfera              → mapeada em
      completa ao redor                semi-esfera
      do usuário                       à frente
```

### Tarefas

- [ ] **T2.1** — Gerar geometria de **esfera UV** (C++):
  - Resolução: 64 segmentos × 32 anéis (mínimo para qualidade VR)
  - Normals apontando para dentro (inside-out rendering)
  - UV mapping equirectangular padrão
  ```cpp
  // Gerar esfera invertida (normals para dentro)
  for (int ring = 0; ring <= rings; ring++) {
      float phi = M_PI * ring / rings;  // 0 a PI
      for (int seg = 0; seg <= segments; seg++) {
          float theta = 2.0f * M_PI * seg / segments;  // 0 a 2*PI
          float x = sin(phi) * cos(theta);
          float y = cos(phi);
          float z = sin(phi) * sin(theta);
          
          vertices.push_back({
              .position = {x * radius, y * radius, z * radius},
              .normal = {-x, -y, -z},  // Invertido!
              .uv = {(float)seg / segments, (float)ring / rings}
          });
      }
  }
  ```
- [ ] **T2.2** — Implementar shader para projeção **equirectangular**:
  - Textura 2:1 → esfera completa
  - Sampler com wrap mode `REPEAT` em U, `CLAMP_TO_EDGE` em V
- [ ] **T2.3** — Implementar **180° VR**:
  - Usar semi-esfera (apenas metade frontal)
  - Ou: esfera completa com UV mapping limitado a 180° horizontal
  - Padrão VR180: aspect ratio 1:1 por olho
- [ ] **T2.4** — Implementar **360° estereoscópico**:
  - Combinar esfera 360° com separação SBS/OU
  - SBS 360°: metade esquerda da textura → olho esquerdo, metade direita → olho direito
  - OU 360°: metade superior → olho esquerdo, metade inferior → olho direito
  ```glsl
  // Fragment shader para 360° stereo SBS
  void main() {
      vec2 uv = vTexCoord;
      // Separar por olho
      uv.x = uv.x * 0.5 + float(gl_ViewID_OVR) * 0.5;
      vec4 color = texture(videoTexture, uv);
      fragColor = color;
  }
  ```
- [ ] **T2.5** — Implementar **180° estereoscópico**:
  - Similar a 360° stereo, mas geometria limitada a 180°
- [ ] **T2.6** — Posicionar o **usuário no centro** da esfera:
  - A esfera deve estar centrada na posição da cabeça do usuário
  - Usar `XR_REFERENCE_SPACE_TYPE_VIEW` ou atualizar posição da esfera cada frame
- [ ] **T2.7** — Desabilitar tela virtual e ambientes durante reprodução 360°/180°:
  - O conteúdo 360° É o ambiente — não renderize nada por trás

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Renderizar 360° dentro de uma esfera com normals erradas = vídeo invertido/espelhado**. As normals DEVEM apontar para dentro (o usuário está dentro da esfera). Se apontar para fora, a textura aparece espelhada e o culling descarta os fragmentos visíveis.
> ```cpp
> // CORRETO: normals para dentro + desabilitar backface culling
> glDisable(GL_CULL_FACE);  // Ou garantir front-face = CW para esfera invertida
> ```

> [!CAUTION]
> **Latência em 360° causa motion sickness SEVERA**: Qualquer latência entre movimento da cabeça e atualização da imagem causa náusea intensa. O head tracking DEVE atualizar a cada frame com < 20ms de latência. Se o vídeo não está disponível (buffering), mantenha o último frame + reprojeção. NUNCA mostre tela preta durante buffering em 360°.

> [!WARNING]
> **Polo superior/inferior da esfera**: Projeção equirectangular tem distorção extrema nos polos (topo/fundo). A geometria da esfera precisa de resolução suficiente nos polos para evitar artefatos. 64×32 segmentos é o mínimo.

> [!WARNING]
> **Resolução efetiva por olho**: Em conteúdo 360° stereo SBS, um vídeo 4K (3840×1920) resulta em apenas 1920×1920 por olho, espalhado em 360° — a resolução visível por momento é muito baixa (~10 PPD). Para qualidade aceitável, recomende vídeos de 5.7K ou 8K.

> [!IMPORTANT]
> **Depth buffer para 360°**: Desabilite depth test/write durante rendering da esfera 360°. A esfera está "infinitamente longe" e não deve interagir com depth de UI overlays.

---

## 3. Auto-detecção de Formato 3D

### O que fazer

Detectar automaticamente se um arquivo é 2D, SBS, OU, 360°, ou 180° sem intervenção do usuário.

### Tarefas

- [ ] **T3.1** — Detecção por **metadados do container**:
  - MP4: `st3d` box (stereoscopic 3D), `sv3d` box (spherical video)
  - MKV: `StereoMode` element, `Projection` element
  - WebM: `Projection` header
  ```rust
  // Metadados comuns
  enum VideoStereoMode {
      Mono,           // 2D
      SideBySideLeft, // SBS, olho esquerdo primeiro
      SideBySideRight,
      TopBottomLeft,  // OU, olho esquerdo no topo
      TopBottomRight,
  }
  
  enum VideoProjection {
      Rectangular,     // 2D flat
      Equirectangular, // 360°
      Cubemap,
      EquiangularCubemap, // EAC
      HalfEquirectangular, // 180°
  }
  ```
- [ ] **T3.2** — Detecção por **filename pattern** (fallback):
  ```rust
  fn detect_from_filename(name: &str) -> Option<Format3D> {
      let lower = name.to_lowercase();
      
      // Padrões comuns em nomes de arquivo
      let patterns = [
          ("_sbs", Format3D::SBS),
          ("-sbs", Format3D::SBS),
          ("_3d_sbs", Format3D::SBS),
          ("_hsbs", Format3D::HalfSBS),
          ("_half_sbs", Format3D::HalfSBS),
          ("_ou", Format3D::OverUnder),
          ("_tab", Format3D::OverUnder),  // Top-And-Bottom
          ("_hou", Format3D::HalfOU),
          ("_360", Format3D::Spherical360),
          ("_180", Format3D::Spherical180),
          ("_vr180", Format3D::VR180),
          ("_vr_", Format3D::VR180),
          ("_180x180", Format3D::VR180SBS),
          ("_360x180", Format3D::Spherical360),
          ("_lr", Format3D::SBS),  // Left-Right
      ];
      
      patterns.iter()
          .find(|(pattern, _)| lower.contains(pattern))
          .map(|(_, format)| *format)
  }
  ```
- [ ] **T3.3** — Detecção por **heurística de resolução** (último recurso):
  - Aspect ratio ~32:9 ou 4:1 → provavelmente Full SBS
  - Aspect ratio 2:1 e resolução ≥ 3840 → provavelmente 360° mono
  - Aspect ratio 1:1 e resolução ≥ 3840 → provavelmente 360° stereo OU
- [ ] **T3.4** — UI de **confirmação/override**:
  - Mostrar formato detectado: "Detectado como: 360° SBS"
  - Botão para mudar manualmente se detecção estiver errada
  - Salvar preferência por arquivo

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **Auto-detecção nunca é 100% confiável**: Muitos vídeos SBS/OU não têm metadados. Filenames variam muito. SEMPRE permita override manual fácil. O pior cenário é reproduzir um vídeo 360° SBS como 2D — causa confusão, não dano. Prefira ser conservador: na dúvida, abrir como 2D e deixar o usuário escolher.

> [!IMPORTANT]
> **Cache de detecção**: Salve o formato detectado/escolhido no banco de dados local. Na segunda reprodução, use a preferência salva sem re-detectar.

---

## 4. Head Tracking para Conteúdo 360°

### O que fazer

Atualizar a orientação da esfera 360° com base no movimento da cabeça do usuário a cada frame.

### Tarefas

- [ ] **T4.1** — Obter **pose da cabeça** a cada frame via OpenXR:
  ```cpp
  // No render loop, para cada frame:
  XrSpaceLocation headLocation = {XR_TYPE_SPACE_LOCATION};
  xrLocateSpace(headSpace, referenceSpace, predictedDisplayTime, &headLocation);
  
  // headLocation.pose.orientation → quaternion da orientação da cabeça
  XrQuaternionf headOrientation = headLocation.pose.orientation;
  
  // Converter para matriz de view
  glm::mat4 viewMatrix = glm::mat4_cast(glm::quat(
      headOrientation.w, headOrientation.x, 
      headOrientation.y, headOrientation.z
  ));
  ```
- [ ] **T4.2** — Aplicar rotação à esfera 360°:
  - NÃO mover a esfera — rotacionar o view baseado na orientação
  - A esfera é estática; a "câmera" dentro dela gira com a cabeça
- [ ] **T4.3** — Implementar **recenter**:
  - Botão para resetar a orientação "frente" do conteúdo
  - Long press no botão Menu do controller
  - Útil quando o conteúdo está "girado" em relação ao usuário
- [ ] **T4.4** — Suportar **6DOF vs 3DOF** para conteúdo 360°:
  - 360°: apenas rotação importa (3DOF — o conteúdo é uma esfera infinita)
  - Ignorar translação da cabeça para conteúdo 360° (não tem paralaxe)
  - Para 180° VR: pode permitir leve translação para efeito de paralaxe

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Usar `predictedDisplayTime`, não o tempo atual**: OpenXR fornece o tempo em que o frame será EXIBIDO, não o tempo atual. Use esse tempo para obter a pose predita da cabeça. Usar o tempo atual adiciona latência de um frame (~11ms a 90Hz).

> [!WARNING]
> **Não interpolar/filtrar a orientação da cabeça**: O runtime do Quest já faz prediction e filtering. Aplicar filtro adicional ADICIONA latência e causa motion sickness. Use a pose crua do OpenXR.

> [!IMPORTANT]
> **Rotação apenas para 360°**: Em conteúdo 360°, se o usuário andar fisicamente no espaço, a esfera NÃO deve se mover (não tem dados de paralaxe). Ignora a posição translacional, use apenas a rotação.

---

## 5. Protocolo NFS

### O que fazer

Conectar a exports NFS para navegar e reproduzir mídia de NAS Linux.

### Tarefas

- [ ] **T5.1** — Integrar cliente NFS no Rust:
  - Opção A: Bindings para `libnfs` (biblioteca C madura)
  - Opção B: Crate `nfs-client` (puro Rust, pode ser menos maduro)
  - Recomendação: `libnfs` é mais robusta e testada
- [ ] **T5.2** — Implementar operações:
  - Mount export
  - Listar diretórios
  - Ler arquivos (streaming, com seek)
  - Stat (tamanho, data de modificação)
- [ ] **T5.3** — Implementar I/O callback para FFmpeg (mesmo padrão do SMB)
- [ ] **T5.4** — UI para configurar conexão NFS:
  - Host + Export path (ex: `192.168.1.100:/media/videos`)
  - Opções de mount (versão NFS: v3 vs v4)

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **NFS não tem autenticação por padrão**: NFS v3 usa IP-based access control (configurado no servidor). NFS v4 suporta Kerberos. Para uso doméstico, geralmente é "acesso livre por IP". Não espere autenticação user/password como SMB.

> [!WARNING]
> **NFS v3 vs v4**: NFS v4 é stateful e mais robusto. NFS v3 é stateless e mais simples. Muitos NAS domésticos suportam ambos. Tente v4 primeiro, fallback para v3.

> [!IMPORTANT]
> **Cross-compile `libnfs` para Android**: Precisa compilar `libnfs` com o NDK toolchain para `aarch64-linux-android`. Configure:
> ```bash
> export CC=aarch64-linux-android29-clang
> export CFLAGS="--sysroot=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
> ./configure --host=aarch64-linux-android --prefix=/output/path
> make && make install
> ```

> [!NOTE]
> **Performance NFS vs SMB**: NFS geralmente tem throughput ~10-20% melhor que SMB para arquivos grandes (streaming de vídeo) em redes locais, pois tem menos overhead de protocolo.

---

## 6. Protocolos FTP e SFTP

### O que fazer

Conectar a servidores FTP/SFTP para navegar e reproduzir mídia remota.

### Tarefas

- [ ] **T6.1** — Implementar cliente **FTP** no Rust:
  - Crate: `suppaftp` (ou `ftp` crate)
  - Login (user/password) + anonymous
  - Listar diretórios (`LIST`, `MLSD`)
  - Download com offset (`REST` + `RETR`) para seek
  - Modo passivo (obrigatório — modo ativo não funciona atrás de NAT/firewall)
- [ ] **T6.2** — Implementar cliente **SFTP** no Rust:
  - Crate: `ssh2` (bindings para libssh2) ou `russh-sftp`
  - Autenticação: password, key-based
  - Operações: opendir, readdir, stat, open, read, seek
- [ ] **T6.3** — Integrar com FFmpeg via custom I/O:
  - FTP: leitura sequencial + seek via reconnect com REST
  - SFTP: read + seek nativo (SFTP suporta random access)
- [ ] **T6.4** — UI para configurar conexão:
  - Tipo: FTP / SFTP
  - Host + Porta (21 / 22)
  - User + Password
  - Para SFTP: opção de key file (avançado)

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **FTP seek é problemático**: FTP não suporta random read nativamente. Para seek, é necessário:
> 1. Enviar `REST <offset>` (set restart position)
> 2. Enviar `RETR <file>` (iniciar download a partir do offset)
> 3. Isso é lento — cada seek fecha e reabre a transferência
> 
> Para vídeos, isso é aceitável se a frequência de seek for baixa. MAS o demuxer do FFmpeg faz seeks frequentes em MKV (para ler cues no final do arquivo). **Solução**: para FTP, baixe o índice do arquivo (primeiros e últimos KB) antes de iniciar o playback.

> [!WARNING]
> **FTP senha em texto claro**: FTP transmite credenciais em texto plano na rede local. Para uso doméstico é aceitável, mas documente a recomendação de usar SFTP quando possível.

> [!WARNING]
> **libssh2 cross-compile**: Se usar `ssh2` crate (que depende de `libssh2`), precisa cross-compilar `libssh2` + `openssl` para Android ARM64. Alternativa: `russh` é puro Rust e não requer cross-compile de libs C.

> [!IMPORTANT]
> **FTP modo passivo**: O Quest 3 está na rede Wi-Fi, geralmente atrás de NAT. Modo ativo FTP (servidor conecta de volta ao cliente) não funciona. Use **sempre** modo passivo (`PASV` / `EPSV`).

> [!NOTE]
> **Timeout agressivo**: Conexões FTP/SFTP podem ficar idle e serem fechadas pelo servidor. Implemente keepalive (`NOOP` para FTP, SSH keepalive para SFTP) e reconexão automática.

---

## 7. DLNA/UPnP

### O que fazer

Descobrir automaticamente servidores de mídia na rede via UPnP/DLNA e navegar seu conteúdo.

### Tarefas

- [ ] **T7.1** — Implementar **SSDP Discovery** no Rust:
  - Enviar `M-SEARCH` multicast para `239.255.255.250:1900`
  - Filtrar por `urn:schemas-upnp-org:device:MediaServer:1`
  - Parsear responses para obter `LOCATION` do device description XML
  ```rust
  // SSDP M-SEARCH request
  let search_msg = format!(
      "M-SEARCH * HTTP/1.1\r\n\
       HOST: 239.255.255.250:1900\r\n\
       MAN: \"ssdp:discover\"\r\n\
       MX: 3\r\n\
       ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"
  );
  ```
- [ ] **T7.2** — Parsear **Device Description XML**:
  - Obter `friendlyName`, `modelName`, ícone
  - Localizar `ContentDirectory` service URL
- [ ] **T7.3** — Implementar cliente **ContentDirectory** (SOAP):
  - `Browse` action: navegar diretórios de conteúdo
  - Parsear DIDL-Lite XML (metadados de cada item)
  - Extrair `res` element (URL de streaming do conteúdo)
- [ ] **T7.4** — Integrar com pipeline de playback:
  - DLNA fornece URL HTTP do conteúdo → reproduzir via HTTP playback (já implementado na v0.1)
- [ ] **T7.5** — UI para DLNA:
  - Lista de servidores descobertos automaticamente
  - Navegação hierárquica do conteúdo do servidor
  - Mostrar metadados (título, thumbnail, duração, resolução)
- [ ] **T7.6** — Opcionalmente usar crate `rupnp` para simplificar:
  ```rust
  // Usando rupnp para discovery
  use rupnp::ssdp::{SearchTarget, URN};
  
  let search_target = SearchTarget::URN(URN::device("schemas-upnp-org", "MediaServer", 1));
  let devices = rupnp::discover(&search_target, Duration::from_secs(3)).await?;
  
  pin_utils::pin_mut!(devices);
  while let Some(device) = devices.try_next().await? {
      println!("Found: {} at {}", device.friendly_name(), device.url());
  }
  ```

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **SSDP multicast no Android**: Android pode bloquear multicast por padrão para economizar bateria. Você precisa adquirir um `MulticastLock`:
> ```kotlin
> val wifi = getSystemService(Context.WIFI_SERVICE) as WifiManager
> val multicastLock = wifi.createMulticastLock("dlna_discovery")
> multicastLock.setReferenceCounted(true)
> multicastLock.acquire()
> // ... após terminar discovery:
> multicastLock.release()
> ```

> [!WARNING]
> **DIDL-Lite XML é complexo**: O formato XML de resposta do ContentDirectory é verboso e cheio de edge cases. Use um parser robusto (`quick-xml` em Rust). Não tente parsear com regex.

> [!IMPORTANT]
> **DLNA ≠ browse + play**: DLNA apenas descobre e fornece URLs HTTP. A reprodução em si é via HTTP (que já está implementado). Não confunda com controle de mídia (que é DLNA DMR/DMC — não necessário para um player).

> [!NOTE]
> **Servidores DLNA comuns**: miniDLNA (Linux), Plex (expõe DLNA opcionalmente), Synology MediaServer, Windows Media Player sharing. Teste com pelo menos 2 implementações diferentes.

---

## 8. HLS Streaming

### O que fazer

Suportar HTTP Live Streaming (HLS) — formato de streaming adaptativo usado por muitos servidores e serviços.

### Tarefas

- [ ] **T8.1** — Implementar parser de **M3U8 playlist** no Rust:
  - Master playlist (lista de variantes com diferentes qualidades)
  - Media playlist (lista de segments)
  - Crate: `hls_m3u8` ou custom parser
  ```rust
  // Estrutura de uma playlist HLS
  struct HlsMasterPlaylist {
      variants: Vec<HlsVariant>,
  }
  
  struct HlsVariant {
      bandwidth: u64,      // bits/s
      resolution: Option<(u32, u32)>,
      codecs: String,
      url: String,         // URL da media playlist
  }
  
  struct HlsMediaPlaylist {
      target_duration: f64,
      segments: Vec<HlsSegment>,
      is_live: bool,       // #EXT-X-ENDLIST ausente = live
  }
  
  struct HlsSegment {
      duration: f64,
      url: String,
  }
  ```
- [ ] **T8.2** — Implementar **downloader de segments**:
  - Download de segments sequenciais
  - Prefetch: baixar 2-3 segments à frente
  - Adaptive bitrate: selecionar qualidade baseado na velocidade de download
- [ ] **T8.3** — Integrar com FFmpeg:
  - Opção A: FFmpeg já suporta HLS nativamente via `avformat_open_input("url.m3u8")`
  - Opção B: Custom segment download + concatenação → feed ao demuxer
  - Recomendação: Use FFmpeg nativo para HLS (é robusto e testado)
- [ ] **T8.4** — Implementar **adaptive bitrate selection**:
  - Monitorar velocidade de download de cada segment
  - Se `download_time > 0.8 * segment_duration` → reduzir qualidade
  - Se `download_time < 0.3 * segment_duration` → aumentar qualidade
  - Hysteresis: não mudar qualidade a cada segment (esperar 3-5 consecutivos)
- [ ] **T8.5** — Seek em HLS:
  - Calcular segment correspondente ao timestamp desejado
  - Descartar buffer, baixar segment correto, decodificar a partir do keyframe
- [ ] **T8.6** — UI: indicador de qualidade atual + opção de forçar qualidade específica

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **HLS segments podem ter URLs relativas ou absolutas**: Parse URLs corretamente. Se o segment URL é relativo, resolva contra a URL da media playlist, não da master playlist.

> [!WARNING]
> **Segments criptografados**: Alguns HLS streams usam AES-128 encryption (`#EXT-X-KEY`). Para uso pessoal com seu próprio servidor, geralmente não é criptografado. Implemente suporte básico a AES-128 por precaução.

> [!IMPORTANT]
> **FFmpeg HLS é a melhor opção para o MVP**: FFmpeg já resolve adaptive selection, segment download, decryption, e seek. Não reimplemente se não necessário. Use `avformat_open_input("https://server/stream.m3u8")` diretamente.

> [!NOTE]
> **HLS latência**: HLS típico tem 10-30s de latência (para live). Como estamos focando em VOD (não live na v0.2), a latência não é preocupação.

---

## 9. Legendas (SRT, VTT)

### O que fazer

Renderizar legendas SRT e VTT sobrepostas ao vídeo na tela virtual.

### Tarefas

- [ ] **T9.1** — Implementar parser **SRT** no Rust:
  ```rust
  struct SubtitleEntry {
      index: u32,
      start_ms: u64,
      end_ms: u64,
      text: String, // Pode conter tags HTML básicas (<b>, <i>, <u>)
  }
  
  fn parse_srt(content: &str) -> Vec<SubtitleEntry> {
      // Parse formato:
      // 1
      // 00:01:23,456 --> 00:01:26,789
      // Texto da legenda
      // (linha vazia)
  }
  ```
- [ ] **T9.2** — Implementar parser **WebVTT** no Rust:
  - Similar a SRT com header `WEBVTT`
  - Suporta posicionamento (`position:`, `align:`)
  - Cue settings opcionais
- [ ] **T9.3** — Implementar **renderização de texto em VR** (C++):
  - Gerar atlas SDF (Signed Distance Field) para fonte (Roboto/Inter)
  - Renderizar texto como quad texturizado
  - Posicionar na parte inferior da tela virtual
  - Outline preto para legibilidade sobre fundo claro
  ```glsl
  // Fragment shader SDF para texto nítido em VR
  float distance = texture(sdfAtlas, vTexCoord).a;
  float smoothWidth = fwidth(distance);
  float alpha = smoothstep(0.5 - smoothWidth, 0.5 + smoothWidth, distance);
  
  // Outline
  float outlineAlpha = smoothstep(0.3 - smoothWidth, 0.3 + smoothWidth, distance);
  vec3 color = mix(vec3(0.0), textColor, alpha); // Outline preto → texto branco
  fragColor = vec4(color, outlineAlpha);
  ```
- [ ] **T9.4** — Sincronizar legendas com reprodução:
  - Buscar entrada ativa baseado no PTS atual do vídeo
  - Ajuste de offset (± segundos) se legenda está fora de sync
- [ ] **T9.5** — Detecção automática de encoding (UTF-8, Latin1, Windows-1252)
- [ ] **T9.6** — UI para legendas:
  - Seleção de arquivo de legenda (ou embedded track)
  - Ajuste de tamanho do texto
  - Ajuste de offset temporal (± sync)
  - Toggle on/off

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Encoding de legendas**: Muitos arquivos SRT são Latin1 ou Windows-1252, não UTF-8. Detecção errada de encoding resulta em caracteres corrompidos (acentos). Use `chardetng` crate para auto-detecção, com UTF-8 como padrão.

> [!WARNING]
> **Legendas em conteúdo 360°**: Onde posicionar a legenda em conteúdo 360°? Opções:
> 1. **Head-locked** (segue o olhar) — mais legível, mas causa desconforto em movimentos rápidos
> 2. **World-locked na frente** — fixa na posição para onde o usuário olhou ao abrir o vídeo
> 3. **Lazy follow** (segue o olhar com atraso suave) — melhor compromisso
> 
> Recomendação: lazy follow com delay de ~0.5s.

> [!IMPORTANT]
> **SDF text rendering**: Gere o atlas SDF em tempo de build, não em runtime. Use `msdfgen` para gerar atlas MSDF (multi-channel SDF) para bordas mais nítidas. Inclua o atlas como asset no APK.

---

## 10. Descoberta Automática de Servidores

### O que fazer

Unificar a descoberta de todos os protocolos de rede em uma tela "Servidores Encontrados".

### Tarefas

- [ ] **T10.1** — Implementar **discovery manager** (Kotlin):
  - Orquestrar scans paralelos: SSDP (DLNA), mDNS (Avahi/Bonjour), NetBIOS (SMB)
  - Unificar resultados em uma lista única
  - Refresh periódico (a cada 30s enquanto tela aberta)
- [ ] **T10.2** — **mDNS/DNS-SD** para serviços genéricos:
  - `_smb._tcp.local` → servidores SMB
  - `_nfs._tcp.local` → servidores NFS
  - `_ftp._tcp.local` → servidores FTP
  - `_sftp-ssh._tcp.local` → servidores SFTP
  - `_webdav._tcp.local` → servidores WebDAV
  - Usar `NsdManager` (Android) ou crate Rust
- [ ] **T10.3** — **NetBIOS** para descoberta SMB legacy:
  - Broadcast de nome NetBIOS na sub-rede
  - Fallback quando mDNS não funciona
- [ ] **T10.4** — UI unificada:
  - Lista de servidores com: ícone do protocolo, nome, IP, tipo
  - Pull-to-refresh / botão de rescan
  - "Adicionar manualmente" para servidores não descobertos
  - Indicador de scan em progresso (spinner)

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **mDNS no Android**: O `NsdManager` do Android é notoriamente buggy e inconsistente. Alternativa mais confiável: implementar mDNS diretamente via UDP socket em Rust (`mdns` ou `trust-dns-resolver` crate).

> [!IMPORTANT]
> **Wi-Fi multicast e battery**: Manter listeners de multicast drenagem bateria. Faça discovery apenas quando a tela de servidores está aberta, e libere locks ao sair.

---

## 11. Gerenciamento de Conexões

### O que fazer

Permitir ao usuário salvar, editar e remover conexões de servidores.

### Tarefas

- [ ] **T11.1** — Criar tabela Room `SavedServer`:
  ```kotlin
  @Entity
  data class SavedServer(
      @PrimaryKey(autoGenerate = true) val id: Long = 0,
      val name: String,                    // Nome amigável
      val protocol: ServerProtocol,        // SMB, NFS, FTP, SFTP, DLNA, WebDAV
      val host: String,                    // IP ou hostname
      val port: Int?,                      // Porta (null = padrão)
      val path: String?,                   // Share/export/path
      val username: String?,
      val encryptedPassword: String?,      // Criptografado via Android Keystore
      val lastConnectedAt: Instant?,
      val isAutoDiscovered: Boolean,
      val iconUrl: String?,                // Ícone DLNA
  )
  ```
- [ ] **T11.2** — CRUD completo na UI:
  - Adicionar, editar, remover, testar conexão
- [ ] **T11.3** — Auto-save de servidores descobertos que o usuário conectou
- [ ] **T11.4** — Indicador de status (online/offline) para cada servidor salvo

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Criptografia de senhas**: Use `EncryptedSharedPreferences` com `AndroidKeystore` ou criptografe manualmente com `AES/GCM/NoPadding` usando chave do Keystore. NUNCA armazene senhas em texto plano no Room.
> ```kotlin
> // Gerar chave no Android Keystore
> val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
> keyGenerator.init(KeyGenParameterSpec.Builder("server_passwords",
>     KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
>     .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
>     .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
>     .build())
> val secretKey = keyGenerator.generateKey()
> ```

---

## 12. Busca e Filtros na Biblioteca

### O que fazer

Pesquisar e filtrar conteúdo na biblioteca local e em servidores conectados.

### Tarefas

- [ ] **T12.1** — Implementar busca por texto (nome do arquivo):
  - Busca fuzzy (tolerar typos)
  - Highlight de termos encontrados
- [ ] **T12.2** — Filtros:
  - Por tipo: Vídeo, Áudio, Imagem
  - Por formato 3D: 2D, SBS, OU, 360°, 180°
  - Por fonte: Local, SMB, NFS, FTP/SFTP, DLNA
  - Por data: Recente, Última semana, Último mês
- [ ] **T12.3** — Ordenação: Nome, Data, Tamanho, Tipo, Último reproduzido
- [ ] **T12.4** — "Continuar assistindo" com filtro de não-completados

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **Busca em servidores remotos é lenta**: Não busque em todos os servidores simultaneamente quando o usuário digita. Use debounce (500ms) e busque primeiro localmente, depois em servidores sob demanda.

---

## 13. Metadados de Mídia

### O que fazer

Exibir informações detalhadas sobre cada arquivo de mídia.

### Tarefas

- [ ] **T13.1** — Extrair metadados via FFmpeg (Rust):
  - Resolução, codec vídeo, codec áudio, bitrate, duração
  - Número de tracks (áudio, vídeo, legendas)
  - Container format
  - Metadata tags (título, artista, etc.)
- [ ] **T13.2** — UI de detalhes do arquivo:
  - Card com thumbnail + informações
  - Lista de tracks com opção de seleção
- [ ] **T13.3** — Gerar thumbnails:
  - Capturar frame em ~10% da duração do vídeo
  - Cache em disco (`/data/data/com.vrplayer/cache/thumbs/`)
  - Lazy loading na lista

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **Thumbnails de arquivos remotos**: Gerar thumbnail de um arquivo SMB/NFS requer baixar pelo menos os primeiros MB do arquivo. Faça isso em background, sem bloquear a UI. Mostre placeholder até o thumbnail estar pronto.

---

## 14. Monitoramento Térmico

### O que fazer

Monitorar a temperatura do Quest 3 e adaptar a qualidade de reprodução para evitar throttling.

### Tarefas

- [ ] **T14.1** — Implementar `ThermalMonitor` (Kotlin):
  ```kotlin
  class ThermalMonitor(context: Context) {
      private val powerManager = context.getSystemService(PowerManager::class.java)
      
      data class ThermalState(
          val level: ThermalLevel,
          val actions: List<ThermalAction>
      )
      
      enum class ThermalLevel { NORMAL, LIGHT, MODERATE, SEVERE, CRITICAL, SHUTDOWN }
      
      enum class ThermalAction {
          REDUCE_RENDER_RESOLUTION,    // Baixar resolução do swapchain
          SIMPLIFY_ENVIRONMENT,        // Desligar efeitos do ambiente
          REDUCE_DECODE_RESOLUTION,    // Pedir variante de menor qualidade (HLS)
          LIMIT_FPS,                   // Reduzir de 90fps para 72fps
          PAUSE_PREFETCH,              // Parar prefetch de rede
          WARN_USER,                   // Notificar usuário
          PAUSE_PLAYBACK,              // Último recurso
      }
      
      fun startMonitoring(callback: (ThermalState) -> Unit) {
          powerManager.addThermalStatusListener { status ->
              val state = when (status) {
                  PowerManager.THERMAL_STATUS_NONE,
                  PowerManager.THERMAL_STATUS_LIGHT -> 
                      ThermalState(ThermalLevel.NORMAL, emptyList())
                  PowerManager.THERMAL_STATUS_MODERATE ->
                      ThermalState(ThermalLevel.MODERATE, listOf(
                          ThermalAction.SIMPLIFY_ENVIRONMENT,
                          ThermalAction.PAUSE_PREFETCH
                      ))
                  PowerManager.THERMAL_STATUS_SEVERE ->
                      ThermalState(ThermalLevel.SEVERE, listOf(
                          ThermalAction.REDUCE_RENDER_RESOLUTION,
                          ThermalAction.SIMPLIFY_ENVIRONMENT,
                          ThermalAction.LIMIT_FPS,
                          ThermalAction.WARN_USER
                      ))
                  PowerManager.THERMAL_STATUS_CRITICAL ->
                      ThermalState(ThermalLevel.CRITICAL, listOf(
                          ThermalAction.PAUSE_PLAYBACK,
                          ThermalAction.WARN_USER
                      ))
                  else -> ThermalState(ThermalLevel.NORMAL, emptyList())
              }
              callback(state)
          }
      }
  }
  ```
- [ ] **T14.2** — Implementar as ações térmicas no render pipeline (C++):
  - Reduzir resolução do swapchain (ex: 0.8x)
  - Simplificar ambiente (desligar shadows, efeitos)
- [ ] **T14.3** — Feedback visual ao usuário:
  - Ícone de temperatura no canto da tela
  - Notificação "Reduzindo qualidade para evitar superaquecimento"

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Quest 3 desliga em THERMAL_STATUS_SHUTDOWN**: Se o app não reagir ao nível `SEVERE`, o Quest 3 pode forçar o desligamento para proteção. Implemente reação AGRESSIVA em SEVERE: reduza tudo, avise o usuário.

> [!IMPORTANT]
> **Decodificação 8K + ambiente 3D elaborado = superaquecimento garantido em < 15 min**. Na fase 0.2, ao reproduzir conteúdo 360° de alta resolução, force ambiente void e reduza resolução do render se necessário.

---

## 15. Cuidados Transversais da Fase 0.2

### Migração do Banco de Dados

> [!IMPORTANT]
> A v0.2 adiciona novas tabelas e campos ao banco Room. Implemente **Room migrations** para que dados da v0.1 (histórico, configurações) sejam preservados. NUNCA use `fallbackToDestructiveMigration()` em produção.

### Testes

> [!IMPORTANT]
> **Teste cada formato 3D com vídeos de referência**: Crie um repositório de vídeos de teste:
> - SBS half/full (H.264 e H.265)
> - OU half/full
> - 360° mono equirectangular (4K e 8K)
> - 360° stereo SBS
> - 180° VR
> - Vídeos com legendas SRT e VTT
> - Vídeos com múltiplas faixas de áudio
> 
> Teste em todos os formatos de container (MP4, MKV, WebM).

### Performance

> [!WARNING]
> **360° stereo 8K requer atenção especial**: Um frame 8K stereo SBS (7680×3840) tem ~88MB descompactado. A textura GPU correspondente consome ~120MB. Monitore o uso de VRAM. Se necessário, downscale para 6K ou 4K antes de uploading para textura.

### Backward Compatibility

> [!NOTE]
> **Todos os requisitos da v0.1 devem continuar funcionando**: A v0.2 apenas ADICIONA funcionalidades. Rode a suite de testes da v0.1 antes de release da v0.2 para garantir nenhuma regressão.

---

## Definição de Pronto (Definition of Done) — v0.2

- [ ] Vídeo SBS (half e full) renderiza com profundidade 3D correta
- [ ] Vídeo OU (half e full) renderiza com profundidade 3D correta
- [ ] Vídeo 360° mono renderiza em esfera com head tracking funcional
- [ ] Vídeo 360° stereo SBS renderiza corretamente em esfera 3D
- [ ] Vídeo 180° VR renderiza em semi-esfera com head tracking
- [ ] Swap eyes funciona em todos os modos 3D
- [ ] Auto-detecção de formato acerta ≥ 80% dos arquivos de teste
- [ ] Override manual de formato funciona
- [ ] Conectar e navegar servidor NFS
- [ ] Conectar e navegar servidor FTP
- [ ] Conectar e navegar servidor SFTP
- [ ] DLNA descobre e lista conteúdo de pelo menos 2 servers diferentes
- [ ] HLS stream reproduz com adaptive bitrate
- [ ] Legendas SRT e VTT renderizam corretamente sobre vídeo 2D
- [ ] Legendas em conteúdo 360° com lazy follow
- [ ] Descoberta automática encontra servidores SMB, DLNA na rede
- [ ] Servidores salvos persistem entre sessões
- [ ] Busca e filtros funcionam na biblioteca
- [ ] Monitor térmico reage a pelo menos 2 níveis
- [ ] Nenhuma regressão nos testes da v0.1
- [ ] Latência de head tracking em 360° < 20ms
- [ ] Nenhum crash em sessão de 45 minutos com conteúdo 360°

---

*Fase 0.2 — Estimativa: 8-14 semanas para desenvolvedor solo experiente (após v0.1 estável)*  
*Dependência forte: v0.1 DEVE estar completa e estável antes de iniciar v0.2*
