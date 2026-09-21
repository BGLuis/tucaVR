# Fase 0.6 — Estabilização

> **Objetivo**: Fechar a lacuna entre o que o código já faz e o que a documentação de
> acompanhamento diz que ele faz, validar em hardware físico (Meta Quest 3) tudo que foi
> construído desde a Fase 0.3 sob feature flag "nunca validado em headset real", corrigir a
> regressão de MSAA introduzida pela migração Vulkan, e resolver os dois itens da Fase 0.5 que
> ainda não têm nenhuma linha de código (Múltiplas Telas, Eye Tracking) — por implementação
> mínima ou por descope formal.
> **Pré-requisito**: Fase 0.4 completa e estável. Fase 0.5 **parcialmente** implementada —
> Ambientes Virtuais Avançados (§3) e Ajuste de Iluminação (§4, via Modo Ambiente) já existem no
> código; Múltiplas Telas (§1) e Eye Tracking (§2) não. Esta fase assume essa lacuna como ponto
> de partida em vez de reabrir o que já foi feito.
> **Resultado esperado**: Todo item hoje marcado como "implementado, nunca validado em headset"
> passa a ter uma sessão de validação registrada — e uma decisão explícita de `defaultEnabled`
> em `FeatureFlags.kt` como consequência. O índice `docs/reports/README.md` reflete o estado real
> do repositório. A Fase 1.0 pode começar sobre uma fundação testada, não presumida.

---

## 📋 Índice

1. [Reconciliação da Documentação](#1-reconciliação-da-documentação)
2. [Roteiro e Campanha de Validação em Hardware](#2-roteiro-e-campanha-de-validação-em-hardware)
3. [Correção de MSAA no Vulkan](#3-correção-de-msaa-no-vulkan)
4. [Múltiplas Telas Virtuais — Escopo Mínimo](#4-múltiplas-telas-virtuais--escopo-mínimo)
5. [Eye Tracking — Decisão de Escopo](#5-eye-tracking--decisão-de-escopo)
6. [Cuidados Transversais da Fase 0.6](#6-cuidados-transversais-da-fase-06)
7. [Definição de Pronto (Definition of Done) — v0.6](#7-definição-de-pronto-definition-of-done--v06)

---

## 1. Reconciliação da Documentação

### Conceito

`docs/reports/README.md` afirma que `PHASE-0.3-01-AMBIENTES-VIRTUAIS.md` e `MODO-AMBIENTE.md`
estão em 0% ("❌ Não iniciado"). Os dois já foram implementados (commits `0b4c8ec`, `dc59824`,
`1be8ba7`, `d0ae4c5`, `96ff3ce`). O mesmo índice referencia `PHASE-0.4-07-TRANSVERSAIS-E-DOD.md`,
arquivo que não existe em `docs/reports/`. Um levantamento completo (`docs/reports/FASE-0.6-ESTABILIZACAO-VALIDACAO-HARDWARE.md`,
seção 1) já confirmou 6 outros achados de bugs avulsos corrigidos sem o índice ser atualizado.
Planejar em cima de um índice que mente sobre o estado do código é o risco nº 1 desta fase — foi
o que originou a necessidade dela.

### Tarefas

- [ ] **T1.1** — Mover `PHASE-0.3-01-AMBIENTES-VIRTUAIS.md` e `MODO-AMBIENTE.md` da tabela
  "Relatórios Ativos" para a lista de arquivados no topo de `docs/reports/README.md`, com uma
  frase de status igual às demais (ex: "✅ **Ambientes Virtuais (Cinema/Sala/Espaço)** — glTF via
  `cgltf`, skybox 360 Vulkan, ancoragem/ajuste livre da tela").

- [ ] **T1.2** — Corrigir a referência a `PHASE-0.4-07-TRANSVERSAIS-E-DOD.md`: reescrever o
  arquivo a partir do que já existe em `PHASE-0.3-11-TRANSVERSAIS-E-DOD.md` (boa parte do
  conteúdo desse relatório também está desatualizado — ver T1.3) ou remover a linha da tabela se
  o relatório nunca chegou a ser escrito de fato.

- [ ] **T1.3** — Revisar item a item os **9 relatórios avulsos ainda não amostrados** nesta
  investigação (`HARDWARE-UPSCALING-VIDEO.md`, `CICLO-DE-VIDA-CRASH-FECHAMENTO.md`,
  `PAUSAR-AO-SAIR-PARAR-AO-FECHAR.md`, `DEBUG-TELEMETRY-EXPORT.md`,
  `PAINEIS-2D-RESOLUCAO-E-STATS.md` e os demais listados em `docs/reports/README.md`), com o
  mesmo método usado na Seção 1 do relatório de origem desta fase: grep pelo sintoma citado,
  confirmar se ainda reproduz no código atual, atualizar o status.

- [ ] **T1.4** — Adicionar ao final de `docs/reports/README.md` uma nota de processo: todo
  relatório cujo "achado" vier acompanhado de correção no código deve ser arquivado ou marcado
  ✅ na mesma sessão em que a correção é revisada — não deixar a defasagem se acumular de novo.

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Esta seção precisa terminar antes das demais começarem de verdade.** As Seções 2–5 desta
> fase foram desenhadas a partir do estado real do código (verificado por grep, não pelo índice) —
> mas qualquer nova feature iniciada em paralelo a T1.1–T1.4, por outra pessoa que só leu o
> índice, corre o risco de redescobrir trabalho já feito. Foi exatamente esse erro que motivou a
> criação desta fase.

> [!NOTE]
> T1.3 não precisa ser feito em uma sessão só. Pode ser paralelizado com a Seção 2 (validação em
> hardware), já que um não bloqueia o outro tecnicamente — só é preciso terminar antes de usar o
> índice como fonte de verdade para planejamento futuro (ex: antes de escrever a Fase 1.0 de
> verdade).

---

## 2. Roteiro e Campanha de Validação em Hardware

### Conceito

`FeatureFlags.kt` mantém três flags com `defaultEnabled = false` e o comentário idêntico —
"nunca validado em headset real até agora": `FOVEATED_RENDERING`, `PASSTHROUGH`, `AMBIENT_MODE`.
Hand tracking não tem flag, mas está no mesmo barco: nenhuma feature construída desde a Fase 0.3
(ambientes, passthrough com chroma key/packed alpha, hand tracking, HDR, fisheye/cubemap/EAC,
MQSR/upscaling, halo de ambiente) tem uma sessão confirmada no Quest 3 físico — é a própria
convenção documentada em `docs/reports/README.md` ("nada neste conjunto de relatórios foi
executado no Quest 3"). Esta seção formaliza essa lacuna como trabalho, com critério de aceite
por item, em vez de deixá-la implícita indefinidamente.

```
Fluxo da Campanha de Validação:

  Feature/Flag                  Roteiro (T2.1)              Resultado
      │                             │                            │
      ├── Ligar flag no device ────►│                            │
      │                             ├── Sessão ≥ 30 min ────►    │
      │                             │   (ou critério funcional   │
      │                             │    objetivo, se não for    │
      │                             │    eixo de conforto)        │
      │                             │                            │
      │  ◄── Passou sem achado ─────┤                            │
      │      → defaultEnabled=true  │                            │
      │                             │                            │
      │  ◄── Achado reproduzível ───┤                            │
      │      → registra bug,        │                            │
      │        mantém flag off      │                            │
```

### Tarefas

- [ ] **T2.1** — Escrever `docs/HARDWARE-VALIDATION-CHECKLIST.md`, um item por feature/eixo, com
  critério de aceite objetivo (não "parece bom" — algo verificável e registrável):
  ```markdown
  ## Foveated Rendering (XR_FB_foveation)
  - [ ] Liga sem crash em todos os 5 níveis (Off/Low/Med/High/Auto)
  - [ ] Nenhuma degradação visual perceptível na região foveada em vídeo 4K
  - [ ] FPS não cai abaixo de 72 durante transição de nível

  ## Passthrough + Chroma Key + Packed Alpha
  - [ ] Tela permanece estável no espaço real por ≥ 5 min sem drift perceptível
  - [ ] Chroma key remove fundo verde/azul sem halo visível nas bordas do sujeito
  - [ ] Packed Alpha (padrão HereSphere/DeoVR) recorta corretamente em pelo menos 1 clipe real

  ## Ambient Mode (halo de luz)
  - [ ] Halo acompanha a cor dominante do frame com latência imperceptível
  - [ ] Nenhuma queda de FPS mensurável com o halo ligado (comparar draw_call_count antes/depois)

  ## Hand Tracking
  - [ ] Pinch select funciona em todos os botões alcançáveis da UI
  - [ ] Transição controller → mãos → controller não trava nem duplica cursor
  - [ ] Confortável por sessão de ≥ 10 min (critério subjetivo, registrar impressão)
  ```

- [ ] **T2.2** — Executar o roteiro de T2.1 no Quest 3 físico, um bloco por sessão. Para cada
  item: registrar passou/falhou, e se falhou, abrir um item de backlog com repro (usar
  `scripts/capture-screen.sh`, `docs/DEBUGGING.md` §8, como evidência visual).

- [ ] **T2.3** — Ao final de cada feature validada com sucesso, atualizar
  `FeatureFlags.kt`: `defaultEnabled = true` e remover o comentário "nunca validado" (ou
  substituí-lo por referência à sessão de validação, ex: "validado em Quest 3 físico, ver
  `docs/HARDWARE-VALIDATION-CHECKLIST.md`").

- [ ] **T2.4** — Fechar a ponta solta específica de conforto listada em
  `PHASE-0.3-11-TRANSVERSAIS-E-DOD.md` §3: sessão de ≥ 30 min testando rotação do campo sonoro
  Ambisonics + head tracking (única feature de conforto que já era testável antes desta fase).

### ⚠️ Cuidados e Armadilhas

> [!IMPORTANT]
> **Esta seção é o núcleo da fase, não um item entre outros.** É onde está o maior risco não
> mitigado do projeto hoje: código mergeado e nunca rodado no hardware alvo. Priorizar sobre as
> Seções 3–5 se o tempo for escasso.

> [!WARNING]
> **Sessão física no Quest 3 é o recurso mais escasso desta fase.** T2.2 não é paralelizável
> internamente (precisa do headset), mas T1 (documentação) e T3 (MSAA, trabalho de código puro)
> podem avançar em paralelo sem competir pelo mesmo recurso.

> [!NOTE]
> Um achado em T2.2 não bloqueia necessariamente o resto da campanha — features são
> independentes entre si (foveated rendering não depende de passthrough funcionar). Continue o
> roteiro e acumule os achados; não pare a fase inteira por um único bug.

---

## 3. Correção de MSAA no Vulkan

### Conceito

O relatório `PAINEIS-2D-RESOLUCAO-E-STATS.md` registrou que a migração Vulkan perdeu o 4×
MSAA que o caminho GLES/OVRFW tinha. Isso ainda é verdade: todo *pipeline* de UI/ambiente em
`vr_player_app_vulkan.cpp` usa `VK_SAMPLE_COUNT_1_BIT` (20 ocorrências), e não existe nenhuma
ocorrência de `VK_SAMPLE_COUNT_2/4/8_BIT` no arquivo. Isso afeta a nitidez de bordas em **todo**
conteúdo que não seja o vídeo em si (painéis 2D, geometria de ambiente) — e é o tipo de ruído
visual que atrapalha a Seção 2 (um avaliador não consegue separar "ambiente feio por falta de
MSAA" de "bug real do ambiente").

### Tarefas

- [ ] **T3.1** — Medir o custo de reativar `VK_SAMPLE_COUNT_4_BIT` nos *pipelines* de UI/ambiente
  (`vr_player_app_vulkan.cpp:2244,2258,2333,2516,2732,2795,2872,3444`) via HUD de debug
  (`draw_call_count`, `smoothed_gpu_time_ms`, já instrumentados) — antes/depois, mesma cena.

- [ ] **T3.2** — Se o custo de GPU for aceitável no XR2 Gen2 (não ultrapassar o budget que
  mantém ≥ 72 FPS): reativar 4× MSAA nesses *pipelines*. **Não tocar** nos *pipelines* de vídeo
  2D/estéreo — o vídeo é um *quad* texturizado via `samplerExternalOES`, já filtrado, e não usa
  MSAA propositalmente.

- [ ] **T3.3** — Se o custo for proibitivo: documentar a decisão explicitamente (em vez de deixar
  como regressão silenciosa) em `docs/VULKAN-MIGRATION-PLAN.md`, com o número medido em T3.1 como
  justificativa.

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **Reativar MSAA sem medir primeiro é a armadilha mais provável aqui.** O XR2 Gen2 tem budget de
> GPU apertado (ver `PHASE-0.3-11-TRANSVERSAIS-E-DOD.md` §1, "~57% GPU" é a estimativa da spec,
> nunca verificada até a instrumentação de draw call existir). T3.1 é obrigatório antes de T3.2.

---

## 4. Múltiplas Telas Virtuais — Escopo Mínimo

### Conceito

Atende parcialmente **RF-2D-009** (prioridade 🟢 Baixo). `PHASE-0.5-PREMIUM.md` §1 especifica
`MAX_SCREENS = 3` com configurador completo; hoje não existe nenhuma linha de código
(`m_screens`/`MAX_SCREENS` só existem como pseudocódigo na spec, zero ocorrências em
`vr_player_app_vulkan.cpp`). Implementar a spec inteira competiria pelo mesmo recurso escasso da
Seção 2 (sessão de hardware). Esta seção propõe um recorte que fecha o requisito sem o polimento
completo — deixando claro que o restante fica para a 1.0 ou depois.

### Tarefas

- [ ] **T4.1** — Antes de duplicar a mecânica de posicionamento para uma segunda tela, corrigir o
  defeito já registrado em `AMBIENTES-AVANCADOS-E-ANCORAGEM-DA-TELA.md`: a tela plana atual
  (`PHASE-0.1-MVP.md` T3.6) move sem trava de confirmação por thumbstick e não persiste
  tamanho/posição entre sessões. Corrigir aqui evita reproduzir o mesmo defeito duas vezes.

- [ ] **T4.2** — Implementar `MAX_SCREENS = 2` (não 3): a segunda tela reaproveita a mecânica de
  mover/redimensionar por thumbstick já corrigida em T4.1, sem o configurador com "fantasma"
  glTF descrito na spec completa da 0.5.
  ```cpp
  // Escopo mínimo: 2 telas, sem layout automático nem fantasma de posicionamento.
  struct VirtualScreen {
      glm::vec3 position;
      glm::vec3 scale;
      PlaybackSource source;   // cada tela tem sua própria fonte independente
  };
  static constexpr int MAX_SCREENS = 2;
  std::vector<VirtualScreen> m_screens;
  ```

- [ ] **T4.3** — UI mínima para adicionar/remover a segunda tela (reaproveitar o padrão de modal
  já usado por `ScreenFormatModal.kt`/`EnvironmentSelectorModal.kt`, sem uma aba nova dedicada).

- [ ] **T4.4** — Documentar explicitamente em `PHASE-0.5-PREMIUM.md` §1 que o escopo completo
  (layout automático, configurador com fantasma, `MAX_SCREENS = 3`) fica adiado — não implícito.

### ⚠️ Cuidados e Armadilhas

> [!IMPORTANT]
> **T4.1 antes de T4.2, sempre.** `AMBIENTES-AVANCADOS-E-ANCORAGEM-DA-TELA.md` já apontou que a
> Fase 0.5 T1.4 planejava reusar a mecânica de posicionamento "tal qual", o que triplicaria o
> defeito (tela única + ambientes avançados + multi-tela). Esta é a última chance de corrigir
> antes da triplicação virar quadruplicação.

---

## 5. Eye Tracking — Decisão de Escopo

### Conceito

Atende **RF-UI-006** (prioridade 🟢 Baixo). Zero linhas de código
(`XR_EXT_eye_gaze_interaction`/`EyeGaze` só existem como pseudocódigo em `PHASE-0.5-PREMIUM.md`
§2, zero ocorrências no código real). Diferente da Seção 4, aqui a recomendação não é
implementação mínima — é **descope explícito**, dado o custo de calibração + foveated UI por
olhar frente ao valor de uma feature de prioridade 🟢 Baixo, num momento em que o projeto ainda
não validou em hardware nem as features de prioridade mais alta já construídas (Seção 2).

### Tarefas

- [ ] **T5.1** — Decisão de produto: descopar Eye Tracking da Fase 0.5/1.0, ou aceitar o custo e
  planejá-lo como fase própria depois da 1.0. Se descopado: atualizar `REQUIREMENTS.md` (mover
  RF-UI-006 para a seção de itens excluídos, junto de DRM/Social/Cloud storage) e
  `PHASE-1.0-RELEASE.md:4` (o pré-requisito "Fase 0.5 completa" deixa de exigir Eye Tracking
  literalmente).

### ⚠️ Cuidados e Armadilhas

> [!IMPORTANT]
> **Esta decisão precisa ficar registrada, não silenciosa.** Sem o registro em T5.1, um leitor
> futuro do roadmap (humano ou IA) vai continuar achando que falta implementar Eye Tracking para
> a 1.0 — repetindo, na direção oposta, o mesmo tipo de erro de planejamento que criou esta fase.

---

## 6. Cuidados Transversais da Fase 0.6

> [!CAUTION]
> **Não iniciar nada de `PHASE-1.0-RELEASE.md` em paralelo a esta fase.** Onboarding, painel de
> configurações unificado, assets de loja e documentação de API pressupõem uma base já validada.
> Um bug de VR encontrado na Seção 2 pode invalidar telas de onboarding ou screenshots de loja
> já produzidos — trabalho duplicado por sequenciamento errado.

> [!WARNING]
> **CI não cobre nada desta fase automaticamente.** O build nativo/C++ está comentado no CI
> (exige o SDK licenciado da Meta) — nenhuma regressão de rendering, OpenXR ou MSAA é detectável
> sem execução manual no headset. Toda a Seção 2 e a Seção 3 (T3.1) são necessariamente manuais.

> [!NOTE]
> Esta fase não segue a numeração de versão semântica ainda (SemVer estrito começa na 1.0, ver
> `PHASE-1.0-RELEASE.md` §7). `version.properties` pode continuar em `0.4.x`/`0.5.x` durante esta
> fase sem problema — o número da fase é organizacional, não é obrigado a virar tag de release.

---

## 7. Definição de Pronto (Definition of Done) — v0.6

### Documentação
- [ ] `docs/reports/README.md` não contém nenhum item marcado "❌ Não iniciado" que já tenha
      código correspondente no branch `develop`
- [ ] Nenhuma referência quebrada a arquivo inexistente em `docs/reports/README.md`
- [ ] Todos os 15 relatórios avulsos revisados contra o código atual (T1.3)

### Validação em Hardware
- [ ] `docs/HARDWARE-VALIDATION-CHECKLIST.md` existe e todo item tem resultado registrado
      (passou / achado com repro)
- [ ] `FOVEATED_RENDERING`, `PASSTHROUGH`, `AMBIENT_MODE`: cada um com `defaultEnabled` decidido
      com base em sessão real, não no valor herdado por padrão
- [ ] Hand tracking validado por sessão ≥ 10 min sem travamento de cursor
- [ ] Ambisonics + head tracking validado por sessão ≥ 30 min (fecha `PHASE-0.3-11` §3)

### Rendering
- [ ] MSAA medido (T3.1) e decisão registrada (reativado ou justificado como inviável)
- [ ] Nenhuma queda de FPS abaixo de 72 atribuível à mudança de MSAA, se reativado

### Múltiplas Telas
- [ ] Defeito de trava/persistência da tela única corrigido (T4.1) antes da segunda tela existir
- [ ] 2 telas simultâneas com fontes independentes, sem degradação perceptível de framerate

### Eye Tracking
- [ ] Decisão de escopo registrada em `REQUIREMENTS.md` e `PHASE-1.0-RELEASE.md` (implementar
      depois ou descopar formalmente — qualquer uma das duas fecha este item, silêncio não)

### Geral
- [ ] Nenhuma regressão nos testes das fases 0.1–0.5
- [ ] `cargo test -p protocols -p media-logic` e `./gradlew testDebugUnitTest` verdes

---

*Fase 0.6 — Estimativa: 2,5–4 semanas para desenvolvedor solo experiente, das quais 6–10 dias-dev
dependem estritamente de sessão física no Quest 3.*
*Esta fase não adiciona funcionalidade nova de produto — o produto ao final é o mesmo em recursos,
mas com a base validada e a documentação confiável.*
*Dependência: nenhuma tecnicamente (pode começar já); bloqueia o início real da Fase 1.0.*
