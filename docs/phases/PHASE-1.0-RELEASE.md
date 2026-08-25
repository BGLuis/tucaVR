# Fase 1.0 — Release

> **Objetivo**: Produção e polimento final. Ambientes customizáveis importados pelo usuário, sistema de contribuição de traduções da comunidade, UX polida com onboarding e settings consolidados, documentação completa de API e contribuidor, auditoria de segurança e qualidade, e prontidão para submissão à Meta Quest Store.  
> **Pré-requisito**: Fase 0.5 (Premium) completa e estável.  
> **Resultado esperado**: O reprodutor é um produto sólido, pronto para distribuição pública — qualquer pessoa pode instalar o APK, assistir conteúdo de qualquer fonte suportada, e a experiência é polida, segura e bem documentada. Outro desenvolvedor consegue clonar o repositório, compilar e contribuir sem ajuda externa.

---

## 📋 Índice

1. [Ambientes Customizáveis](#1-ambientes-customizáveis)
2. [Community Translations (i18n)](#2-community-translations-i18n)
3. [Polimento Final de UX](#3-polimento-final-de-ux)
4. [Documentação Completa](#4-documentação-completa)
5. [Submissão à Meta Quest Store](#5-submissão-à-meta-quest-store)
6. [Auditoria de Segurança e Qualidade](#6-auditoria-de-segurança-e-qualidade)
7. [Cuidados Transversais da Fase 1.0](#7-cuidados-transversais-da-fase-10)
8. [Definição de Pronto (Definition of Done) — v1.0](#8-definição-de-pronto-definition-of-done--v10)

---

## 1. Ambientes Customizáveis

### Conceito

Atende ao requisito **RF-ENV-005** (Ambientes customizáveis pelo usuário — importar modelos 3D). O pipeline de glTF loading já existe desde a v0.3 (ambientes Cinema e Sala). A v1.0 expõe esse pipeline ao usuário final, permitindo importar modelos `.glb`/`.gltf` como novos ambientes.

```
Pipeline de Importação de Ambiente Customizado:

  Usuário                        App                           Storage
    │                             │                              │
    ├─── Seleciona .glb ─────────►│                              │
    │    (file picker ou USB)     │                              │
    │                             ├── Validação (T1.1) ──────►   │
    │                             │   ✓ polígonos ≤ 100K         │
    │                             │   ✓ texturas ≤ 4096²         │
    │                             │   ✓ file size ≤ 100MB        │
    │                             │   ✓ sem extensions proibidas │
    │                             │                              │
    │  ◄── "Posicione a tela" ────┤                              │
    │                             │                              │
    ├─── Move/resize tela ───────►│                              │
    ├─── Confirma ───────────────►│                              │
    │                             ├── Gera config.json ──────►   │
    │                             ├── Copia .glb ────────────►   │
    │                             │   environments/custom_xxx/   │
    │                             ├── Registra no Room ──────►   │
    │                             │                              │
    │  ◄── "Ambiente disponível!" ┤                              │
```

### Tarefas

- [ ] **T1.1** — Implementar **validador de ambiente** (C++):
  - Carregar modelo via `cgltf`/`tinygltf` (mesmo loader da v0.3)
  - Verificar limites de hardware antes de aceitar:
  ```cpp
  struct EnvironmentLimits {
      static constexpr size_t MAX_TRIANGLES = 100000;    // Budget para ambiente (v0.3 define <100K)
      static constexpr size_t MAX_TEXTURE_DIM = 4096;    // ASTC 4096² = ~16MB compactado
      static constexpr size_t MAX_MATERIALS = 20;         // Menos draw calls
      static constexpr size_t MAX_FILE_SIZE_MB = 100;     // Evitar OOM durante load
      static constexpr size_t MAX_TEXTURES = 30;          // VRAM total do ambiente
  };

  struct ValidationResult {
      bool passed = false;
      std::string error;
      // Métricas para UI de feedback
      size_t totalTriangles = 0;
      size_t totalTextures = 0;
      size_t largestTextureDim = 0;
      size_t estimatedVRAM_MB = 0;
  };

  ValidationResult validateEnvironmentModel(const std::string& glbPath) {
      ValidationResult result;
      cgltf_data* data = nullptr;
      cgltf_options options = {};
      
      if (cgltf_parse_file(&options, glbPath.c_str(), &data) != cgltf_result_success) {
          result.error = "Formato inválido — não é um arquivo glTF/GLB válido.";
          return result;
      }
      
      // Contar triângulos totais
      for (size_t m = 0; m < data->meshes_count; m++) {
          for (size_t p = 0; p < data->meshes[m].primitives_count; p++) {
              result.totalTriangles += data->meshes[m].primitives[p].indices->count / 3;
          }
      }
      
      if (result.totalTriangles > EnvironmentLimits::MAX_TRIANGLES) {
          result.error = "Modelo excede o limite de triângulos (" 
              + std::to_string(result.totalTriangles) + " > "
              + std::to_string(EnvironmentLimits::MAX_TRIANGLES) + ").";
          return result;
      }
      
      // Verificar texturas
      for (size_t i = 0; i < data->images_count; i++) {
          // Para GLB embedded, decodificar header para dimensões
          // Para referências externas, verificar arquivo
          size_t w = getImageWidth(data->images[i]);
          size_t h = getImageHeight(data->images[i]);
          result.largestTextureDim = std::max(result.largestTextureDim, std::max(w, h));
          // Estimativa VRAM: ASTC 6x6 ≈ 3.56 bpp
          result.estimatedVRAM_MB += (w * h * 4) / (1024 * 1024); // RGBA descompactado
      }
      result.totalTextures = data->images_count;
      
      if (result.largestTextureDim > EnvironmentLimits::MAX_TEXTURE_DIM) {
          result.error = "Textura excede " 
              + std::to_string(EnvironmentLimits::MAX_TEXTURE_DIM) + "px.";
          return result;
      }
      
      cgltf_free(data);
      result.passed = true;
      return result;
  }
  ```

- [ ] **T1.2** — Implementar **instalador de ambiente** (Kotlin):
  - File picker via `Intent(Intent.ACTION_OPEN_DOCUMENT)` com MIME `application/octet-stream` / filtro `.glb`
  - Copiar para diretório protegido do app
  - Registrar no Room
  ```kotlin
  @Entity
  data class CustomEnvironment(
      @PrimaryKey(autoGenerate = true) val id: Long = 0,
      val name: String,                    // Nome dado pelo usuário
      val glbFileName: String,             // Nome do arquivo no diretório local
      val configJson: String,              // config.json serializado
      val triangleCount: Int,              // Métricas da validação
      val estimatedVramMb: Int,
      val installedAt: Long,               // epoch millis
      val thumbnailPath: String?,          // Preview gerado automaticamente
  )

  @Dao
  interface CustomEnvironmentDao {
      @Query("SELECT * FROM CustomEnvironment ORDER BY installedAt DESC")
      suspend fun getAll(): List<CustomEnvironment>

      @Insert
      suspend fun insert(env: CustomEnvironment): Long

      @Delete
      suspend fun delete(env: CustomEnvironment)
  }
  ```

- [ ] **T1.3** — Implementar **configurador de tela interativo** no VR (C++):
  - Após validação, carregar o modelo no ambiente VR
  - Mostrar um "fantasma" da tela virtual (semi-transparente) que o usuário posiciona com os controllers
  - Thumbstick para mover, Grip+Thumbstick para redimensionar (mesmos controles da tela normal, T3.6 da v0.1)
  - Trigger para confirmar posição
  - Gerar `config.json`:
  ```json
  {
      "format_version": 1,
      "name": "Minha Sala",
      "screen": {
          "position": [0.0, 1.5, -3.0],
          "rotation": [0.0, 0.0, 0.0, 1.0],
          "width": 3.0
      },
      "spawn": {
          "position": [0.0, 0.0, 0.0],
          "yaw_degrees": 0.0
      },
      "lighting": {
          "ambient_intensity": 0.3,
          "screen_glow": true
      }
  }
  ```

- [ ] **T1.4** — Implementar **conversão de texturas para ASTC** em tempo de importação:
  - Texturas PNG/JPEG no glTF devem ser convertidas para ASTC (formato nativo do Quest 3)
  - Usar `astc-encoder` (biblioteca C, compilável para ARM64) ou pré-processar em CPU
  - Cachear texturas convertidas para não reconverter a cada load
  ```cpp
  // Converter textura para ASTC 6x6 durante importação
  bool convertToASTC(const uint8_t* rgba, int w, int h, 
                     std::vector<uint8_t>& astcOutput) {
      astcenc_config config;
      astcenc_config_init(ASTCENC_PRF_LDR, 6, 6, 1,
                          ASTCENC_PRE_MEDIUM, 0, &config);
      
      astcenc_context* context;
      astcenc_context_alloc(&config, 1, &context);
      
      astcenc_image image;
      image.dim_x = w;
      image.dim_y = h;
      image.dim_z = 1;
      image.data = (void**)&rgba;
      
      size_t outSize = /* calcular tamanho ASTC */;
      astcOutput.resize(outSize);
      
      astcenc_compress_image(context, &image, nullptr,
                            astcOutput.data(), outSize, 0);
      
      astcenc_context_free(context);
      return true;
  }
  ```

- [ ] **T1.5** — UI de **gerenciamento de ambientes** (Kotlin):
  - Aba "Meus Ambientes" no seletor de ambientes (adicionado na v0.3, T1.5)
  - Lista com thumbnail, nome, métricas (triângulos, VRAM estimada)
  - Botões: "Recalibrar Tela" (re-entra no configurador T1.3), "Renomear", "Excluir"
  - Feedback de validação durante importação: barra de progresso + resultado

- [ ] **T1.6** — Implementar **geração automática de thumbnail** do ambiente:
  - Após importação, renderizar o ambiente uma vez de um ângulo fixo (spawn point)
  - Capturar framebuffer para uma imagem 256×256
  - Salvar como thumbnail para a lista de ambientes

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **OOM por modelos não otimizados**: Um modelo 3D de Blender exportado sem otimização pode ter milhões de triângulos e texturas 8K. A validação do T1.1 DEVE ser rígida e rejeitar antes de tentar carregar na GPU. O limite de 100K triângulos e 4096px de textura é conservador de propósito — é o mesmo budget dos ambientes oficiais da v0.3.

> [!WARNING]
> **Extensions glTF não suportadas**: O pipeline PBR da v0.3 suporta metallic-roughness básico. Extensions como `KHR_materials_clearcoat`, `KHR_materials_transmission` (vidro), ou `KHR_materials_sheen` (tecido) NÃO são renderizadas. O validador deve listar extensions usadas e avisar: "Este modelo usa efeitos não suportados — pode parecer diferente do esperado."

> [!WARNING]
> **Textura ASTC em runtime**: A conversão PNG→ASTC é computacionalmente cara (pode levar 10-30s por textura grande no ARM64). Faça em background thread, mostre progresso, e cache o resultado. NUNCA converta durante o render loop.

> [!IMPORTANT]
> **Interação com Adaptive Quality**: Se o modelo customizado é mais pesado que os ambientes oficiais e causa drops de FPS, o `AdaptiveQualityManager` (v0.4) deve fazer downgrade para void, assim como faz com os ambientes oficiais. O ambiente customizado DEVE respeitar o mesmo contrato de performance.

> [!NOTE]
> **Modelos open-source**: Documentar no README uma lista de fontes de modelos 3D compatíveis: Sketchfab (filtro CC0), Poly Haven, Kenney. Incluir um guia "Como preparar seu modelo no Blender" com checklist (merge meshes, bake lightmaps, exportar GLB com Draco compression).

---

## 2. Community Translations (i18n)

### Conceito

Atende ao requisito **RF-I18N-005** (Sistema de contribuição de traduções — community). Construído sobre a fundação i18n da v0.1 (`docs/i18n.md`), que já documenta o processo de adicionar idiomas, convenção de naming, e particularidades de plurais. A v1.0 formaliza o fluxo para contribuidores externos.

```
Fluxo de Contribuição de Tradução:

  Contribuidor                    GitHub                         CI/CD
      │                             │                              │
      ├── Fork do repositório ──►   │                              │
      ├── Cria values-XX/           │                              │
      │   strings.xml              │                              │
      ├── Pull Request ────────────►│                              │
      │                             ├── CI: validate_i18n.sh ──►   │
      │                             │   ✓ Todas as chaves?         │
      │                             │   ✓ Formatadores (%s/%d)?    │
      │                             │   ✓ Plurais corretos?        │
      │                             │   ✓ XML válido?              │
      │                             │                              │
      │  ◄── Review + merge ────────┤                              │
      │                             │                              │
      │                             ├── Próximo release APK ──►    │
      │                             │   inclui novo idioma         │
```

### Tarefas

- [ ] **T2.1** — Implementar **script de validação de traduções** para CI (`scripts/validate_i18n.sh`):
  ```bash
  #!/bin/bash
  # Validates a community translation strings.xml against the English base
  set -euo pipefail
  
  BASE="app/src/main/res/values/strings.xml"
  TARGET="$1"
  
  if [ ! -f "$TARGET" ]; then
      echo "ERROR: File not found: $TARGET"
      exit 1
  fi
  
  # 1. XML bem-formado?
  xmllint --noout "$TARGET" 2>&1 || { echo "ERROR: XML malformado"; exit 1; }
  
  # 2. Chaves faltando
  BASE_KEYS=$(grep -oP '(?<=name=")[^"]*' "$BASE" | sort)
  TARGET_KEYS=$(grep -oP '(?<=name=")[^"]*' "$TARGET" | sort)
  MISSING=$(comm -23 <(echo "$BASE_KEYS") <(echo "$TARGET_KEYS"))
  
  if [ -n "$MISSING" ]; then
      echo "WARNING: Chaves não traduzidas (fallback para inglês):"
      echo "$MISSING" | sed 's/^/  - /'
  fi
  
  # 3. Chaves extras (não existem no base — provavelmente erro de digitação)
  EXTRA=$(comm -13 <(echo "$BASE_KEYS") <(echo "$TARGET_KEYS"))
  if [ -n "$EXTRA" ]; then
      echo "ERROR: Chaves desconhecidas (não existem no base):"
      echo "$EXTRA" | sed 's/^/  - /'
      exit 1
  fi
  
  # 4. Verificar formatadores (%1$s, %2$d, etc.)
  for key in $(echo "$BASE_KEYS"); do
      BASE_FMTS=$(grep "name=\"$key\"" "$BASE" | grep -oP '%\d+\$[sdf]' | sort)
      TARGET_FMTS=$(grep "name=\"$key\"" "$TARGET" | grep -oP '%\d+\$[sdf]' | sort 2>/dev/null)
      if [ -n "$BASE_FMTS" ] && [ "$BASE_FMTS" != "$TARGET_FMTS" ]; then
          echo "ERROR: Formatadores divergem na chave '$key':"
          echo "  Base:   $BASE_FMTS"
          echo "  Target: $TARGET_FMTS"
          exit 1
      fi
  done
  
  echo "✓ Validação OK"
  ```

- [ ] **T2.2** — Adicionar **step de CI** para validação automática de traduções:
  ```yaml
  # .github/workflows/i18n-validate.yml
  name: Validate Translations
  on:
    pull_request:
      paths:
        - 'app/src/main/res/values-*/strings.xml'
  
  jobs:
    validate:
      runs-on: ubuntu-latest
      steps:
        - uses: actions/checkout@v4
        - name: Validate all translation files
          run: |
            for file in app/src/main/res/values-*/strings.xml; do
              echo "=== Validating $file ==="
              ./scripts/validate_i18n.sh "$file"
            done
  ```

- [ ] **T2.3** — Escrever **guia de contribuição** (`docs/CONTRIBUTING_I18N.md`):
  - Passo a passo: fork → criar pasta `values-XX` → copiar do inglês → traduzir → PR
  - Referência cruzada com `docs/i18n.md` (convenções de naming, plurais)
  - Template de PR com checklist
  - Lista de idiomas mais solicitados (priorizados por comunidade)

- [ ] **T2.4** — Implementar **tela "Sobre" com créditos de tradutores** (Kotlin):
  - Arquivo `TRANSLATORS.md` na raiz do repositório, parseado em runtime
  - Exibido na UI de settings como lista scrollável
  ```kotlin
  // Carregar créditos de tradutores do asset
  fun loadTranslatorCredits(context: Context): List<TranslatorCredit> {
      val raw = context.assets.open("TRANSLATORS.md").bufferedReader().readText()
      // Parse markdown simples: "- **Idioma**: Nome (GitHub @handle)"
      return raw.lines()
          .filter { it.startsWith("- ") }
          .map { line ->
              val lang = line.substringAfter("**").substringBefore("**")
              val name = line.substringAfter("**: ").substringBefore(" (")
              TranslatorCredit(language = lang, name = name)
          }
  }
  ```

- [ ] **T2.5** — Auditoria completa de **plurais e formatação** em todas as strings existentes:
  - Verificar que toda string com contagem usa `<plurals>` (não concatenação manual)
  - Verificar que idiomas com regras de plural complexas (russo, árabe, polonês) têm todas as categorias CLDR necessárias (`zero`, `one`, `two`, `few`, `many`, `other`)
  - Documentar em `docs/i18n.md` as categorias necessárias por idioma

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Crash por formatador inválido**: O Android lança `java.util.MissingFormatArgumentException` em runtime se uma string traduzida tem `%1$s` mas o código passa menos argumentos. O script de CI (T2.1) DEVE detectar isso — é a causa mais comum de crash por tradução.

> [!WARNING]
> **Idiomas Right-to-Left (RTL)**: Árabe, Hebraico e Persa usam layout RTL. A UI em Android Views (`VirtualDisplay`) suporta RTL nativamente via `android:supportsRtl="true"`, MAS a orientação do quad OES no C++ pode precisar de espelhamento horizontal do UV. Testar com locale RTL antes de aceitar contribuições desses idiomas.

> [!IMPORTANT]
> **Não aceitar traduções via OTA/runtime**: A opção de pacotes de linguagem dinâmicos (download de strings em runtime) foi considerada e REJEITADA para v1.0 por complexidade e segurança. Novas traduções entram apenas via release APK. Isso simplifica drasticamente a validação e evita injeção de strings maliciosas.

> [!NOTE]
> **Plataformas de crowdsourcing**: Se o volume de PRs de tradução se tornar difícil de gerenciar manualmente, considerar integração com Weblate (open-source, self-hostable) que faz commit automático no repositório. Mas isso é otimização futura, não requisito da v1.0.

---

## 3. Polimento Final de UX

### Conceito

A primeira impressão determina a retenção. Esta seção consolida todas as configurações espalhadas pelas fases 0.1–0.5 em uma interface coerente, adiciona onboarding para novos usuários, e polir transições, loading states e tratamento de erros.

```
Fluxo de Primeira Execução (Onboarding):

  ┌─────────────────────────────────────────────────────┐
  │  "Bem-vindo ao tucaVR!"                             │
  │                                                     │
  │  ┌────────────────────────────────────────────┐     │
  │  │ Step 1: Olhe ao redor — você está num      │     │
  │  │ ambiente escuro. A tela de vídeo está       │     │
  │  │ flutuando à sua frente.                     │     │
  │  │                          [Próximo →]        │     │
  │  └────────────────────────────────────────────┘     │
  │                                                     │
  │  ┌────────────────────────────────────────────┐     │
  │  │ Step 2: Use o thumbstick direito para      │     │
  │  │ mover a tela. Segure Grip + thumbstick     │     │
  │  │ para redimensionar.                        │     │
  │  │              [← Voltar]  [Próximo →]       │     │
  │  └────────────────────────────────────────────┘     │
  │                                                     │
  │  ┌────────────────────────────────────────────┐     │
  │  │ Step 3: Aperte B/Y para abrir o            │     │
  │  │ navegador de arquivos. Aponte e aperte     │     │
  │  │ o trigger para selecionar.                 │     │
  │  │              [← Voltar]  [Começar! →]      │     │
  │  └────────────────────────────────────────────┘     │
  └─────────────────────────────────────────────────────┘
```

### Tarefas

- [ ] **T3.1** — Implementar **tutorial de primeira execução** (Kotlin + C++):
  - Sequência de 4-5 cards flutuantes explicando controles básicos
  - Detecta se é primeira execução via DataStore (`has_completed_onboarding`)
  - Cada step destaca visualmente o controle correspondente (ex: pulsar o thumbstick)
  - Botão "Pular tutorial" sempre visível
  - Reativável via Settings → "Repetir Tutorial"
  ```kotlin
  class OnboardingManager(private val dataStore: DataStore<Preferences>) {
      private val ONBOARDING_KEY = booleanPreferencesKey("has_completed_onboarding")
      
      suspend fun shouldShowOnboarding(): Boolean {
          return dataStore.data.first()[ONBOARDING_KEY] != true
      }
      
      suspend fun completeOnboarding() {
          dataStore.edit { it[ONBOARDING_KEY] = true }
      }
      
      suspend fun resetOnboarding() {
          dataStore.edit { it.remove(ONBOARDING_KEY) }
      }
  }
  ```

- [ ] **T3.2** — Implementar **painel de configurações unificado** (Kotlin):
  - Consolidar TODAS as configurações das fases 0.1–0.5 em um único painel com abas:
  ```
  ┌─── Configurações ──────────────────────────────┐
  │ [Geral] [Vídeo] [Rede] [Ambiente] [Controles]  │
  ├──────────────────────────────────────────────────┤
  │                                                  │
  │ Geral:                                           │
  │   ○ Idioma: [Automático ▾]                       │
  │   ○ Tema: [Escuro ▾]                             │
  │   ○ Repetir tutorial                             │
  │                                                  │
  │ Vídeo:                                           │
  │   ○ Decodificação preferida: [Hardware ▾]        │
  │   ○ Formato 3D padrão: [Auto-detectar ▾]        │
  │   ○ Qualidade adaptativa: [Automático ▾]         │
  │   ○ Foveated rendering: [Automático ▾]           │
  │                                                  │
  │ Rede:                                            │
  │   ○ Servidores salvos: [Gerenciar →]             │
  │   ○ Buffer de rede: [4MB ▾]                      │
  │   ○ Download durante playback: [Pausar ▾]        │
  │                                                  │
  │ Ambiente:                                        │
  │   ○ Ambiente padrão: [Void ▾]                    │
  │   ○ Iluminação: [slider]                         │
  │   ○ Temperatura de cor: [slider]                 │
  │   ○ Meus ambientes: [Gerenciar →]                │
  │                                                  │
  │ Controles:                                       │
  │   ○ Modo de input: [Controller ▾]                │
  │   ○ Sensibilidade seek: [slider]                 │
  │   ○ Auto-hide controles: [5s ▾]                  │
  │   ○ Haptics: [Ligado ▾]                          │
  └──────────────────────────────────────────────────┘
  ```
  - Usar DataStore (Preferences) unificado — migrar de múltiplos `SharedPreferences`/`EncryptedSharedPreferences` (credenciais ficam separadas por segurança)
  - Expor configurações relevantes ao C++ via JNI na inicialização

- [ ] **T3.3** — Implementar **loading states** visuais (C++):
  - Spinner 3D flutuante durante operações longas (conexão SMB, prefetch, mudança de ambiente)
  - Barra de progresso para operações com progresso conhecido (download, importação de ambiente)
  - Fade suave em vez de corte abrupto entre estados
  ```cpp
  struct LoadingOverlay {
      bool active = false;
      float progress = -1.0f;  // -1 = indeterminado (spinner), 0-1 = barra
      std::string message;      // "Conectando ao NAS..." / "Importando ambiente..."
      float spinnerAngle = 0.0f;
      
      void update(float dt) {
          if (!active) return;
          spinnerAngle += dt * 120.0f;  // 120°/s
          if (spinnerAngle >= 360.0f) spinnerAngle -= 360.0f;
      }
      
      void render(const glm::mat4& viewProj, const glm::vec3& headPos) {
          if (!active) return;
          // Posicionar 2m à frente do usuário, sempre encarando
          glm::vec3 pos = headPos + glm::vec3(0, 0, -2.0f);
          // Renderizar quad rotativo com textura de spinner
          // + texto da mensagem abaixo
      }
  };
  ```

- [ ] **T3.4** — Implementar **tratamento de erros amigável** (Kotlin + Rust):
  - Mapear códigos de erro internos para mensagens legíveis:
  ```kotlin
  object ErrorMessages {
      fun fromRustError(errorCode: String, ctx: Context): String = when {
          errorCode.contains("smb_auth_failed") -> 
              ctx.getString(R.string.error_smb_auth_failed)
          errorCode.contains("smb_connection_refused") -> 
              ctx.getString(R.string.error_smb_connection_refused)
          errorCode.contains("codec_not_supported") -> 
              ctx.getString(R.string.error_codec_unsupported)
          errorCode.contains("https_no_range") -> 
              ctx.getString(R.string.error_https_no_seek)
          errorCode.contains("file_not_found") -> 
              ctx.getString(R.string.error_file_not_found)
          errorCode.contains("thermal_shutdown") -> 
              ctx.getString(R.string.error_thermal_shutdown)
          else -> ctx.getString(R.string.error_generic, errorCode)
      }
  }
  ```
  - Exibir erros como toast/banner flutuante no espaço 3D (não popup modal)

- [ ] **T3.5** — **Acessibilidade e conforto** (Kotlin + C++):
  - Opção "Texto Maior" (escala 1.0x → 1.5x no VirtualDisplay)
  - Garantia de alto contraste (todos os textos sobre fundo com contrast ratio ≥ 4.5:1)
  - "Controller-only mode" garantido — NENHUMA funcionalidade requer hand tracking ou eye tracking
  - Verificar que todas as ações são alcançáveis apenas com controllers

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Tutorial não deve bloquear processos background**: Enquanto o tutorial roda, scans de rede e inicializações pesadas devem esperar ou rodar em background sem interferir. O tutorial NUNCA deve travar por uma operação de I/O.

> [!WARNING]
> **Migração de SharedPreferences para DataStore**: Se o app já está instalado com dados em `SharedPreferences` (fases anteriores), a migração para DataStore deve preservar todos os valores. Use `SharedPreferencesMigration` do Jetpack DataStore.

> [!IMPORTANT]
> **Settings que afetam o C++**: Configurações como foveation level, qualidade adaptativa e buffer de rede precisam ser comunicadas ao C++/Rust via JNI. Defina um protocolo claro: o Kotlin envia as configs na inicialização e em cada mudança, o C++ NUNCA lê de SharedPreferences/DataStore diretamente.

---

## 4. Documentação Completa

### Conceito

Atende ao **RNF-QUAL-005** (Documentação de API pública). O projeto tri-layer (Kotlin + Rust + C++) é complexo — sem documentação adequada, nenhum contribuidor externo consegue contribuir. A v1.0 produz documentação para três audiências: usuários finais, desenvolvedores contribuidores, e a equipe de review da Quest Store.

### Tarefas

- [ ] **T4.1** — Gerar **documentação Rust** via `rustdoc`:
  - Adicionar `#![deny(missing_docs)]` em todos os crates públicos (`core`, `protocols`)
  - Documentar todas as structs/funções/enums públicas
  - Exemplos de uso em doc comments para funcionalidades-chave
  ```rust
  // Exemplo de documentação adequada:
  
  /// Gerencia o pipeline de reprodução de vídeo.
  ///
  /// O `PlaybackController` é o ponto central de controle para toda
  /// a reprodução de mídia. Ele coordena o demuxer, decoders de vídeo
  /// e áudio, e o sync manager.
  ///
  /// # Lifecycle
  ///
  /// ```text
  /// load_at(path, time) → [threads spawn] → playing
  ///     ↕ toggle_play_pause()
  /// paused ←→ playing
  ///     ↓ stop()
  /// stopped → [threads join] → idle
  /// ```
  ///
  /// # Thread Safety
  ///
  /// O `PlaybackController` é acessado exclusivamente pela thread que
  /// o possui (geralmente a thread JNI/bridge). As threads internas
  /// de decode comunicam via channels.
  pub struct PlaybackController { /* ... */ }
  ```
  - Gerar com `cargo doc --no-deps --workspace`

- [ ] **T4.2** — Gerar **documentação Kotlin** via Dokka:
  - Configurar plugin Dokka no `app/build.gradle.kts`
  ```kotlin
  plugins {
      id("org.jetbrains.dokka") version "1.9.20"
  }
  
  tasks.dokkaHtml {
      outputDirectory.set(layout.buildDirectory.dir("dokka"))
      dokkaSourceSets.configureEach {
          includeNonPublic.set(false)
          skipEmptyPackages.set(true)
      }
  }
  ```
  - KDoc em todas as classes e funções públicas

- [ ] **T4.3** — Escrever **BUILD.md** detalhado:
  - Pré-requisitos (Android SDK, NDK r26+, Rust toolchain, `cargo-ndk`, Meta OpenXR SDK)
  - Passo a passo de build completo (dos zero até APK no headset)
  - Troubleshooting de problemas comuns (linker placeholder em `.cargo/config.toml`, FFmpeg cross-compile)
  - Diagrama de como `scripts/build.sh` orquestra Rust → C++ → Kotlin

- [ ] **T4.4** — Escrever **manual de usuário** (`docs/USER_GUIDE.md`):
  - Guia de configuração de NAS (SMB, NFS, FTP, SFTP, DLNA, WebDAV)
  - Mapa de controles (controllers + hand tracking + eye tracking)
  - Troubleshooting: "vídeo sem som", "conexão SMB falha", "formato 3D errado"
  - Guia de importação de ambientes customizados

- [ ] **T4.5** — Consolidar **README.md**:
  - Visão geral do projeto com screenshot/GIF
  - Badges de CI (build, clippy, ktlint)
  - Quick start (instalar via `adb`)
  - Links para toda documentação (BUILD.md, USER_GUIDE.md, i18n.md, API docs)
  - Tabela de features por fase
  - Seção de contribuição com link para CONTRIBUTING.md e CONTRIBUTING_I18N.md

- [ ] **T4.6** — Escrever **ARCHITECTURE.md**:
  - Diagrama das 3 camadas e seus protocolos de comunicação
  - Fluxo de dados de um frame de vídeo (arquivo → demuxer → decoder → textura → quad)
  - Fluxo de um comando de UI (controller input → C++ → JNI → Kotlin → JNI → C++ → Rust)
  - Decisões arquiteturais (ADRs) com histórico e rationale

### ⚠️ Cuidados e Armadilhas

> [!IMPORTANT]
> **ADR-002 e a ausência do UniFFI**: A documentação DEVE explicar claramente que UniFFI NÃO é usado e por que (ver ADR-002 do REQUIREMENTS.md). Histórico obsoleto no README confunde contribuidores. Remover qualquer referência a UniFFI como tecnologia ativa.

> [!WARNING]
> **Docs desatualizados são piores que nenhum doc**: Toda documentação gerada deve ter um processo de verificação no CI. `cargo doc --no-deps` no CI (já existe parcialmente no clippy workflow). `dokkaHtml` como task do CI.

> [!NOTE]
> **Documentação C++ (Doxygen)**: Opcional para v1.0. O código C++ é relativamente contido (~5 arquivos) e documentado por comentários inline. Se necessário, configurar Doxygen com `CMakeLists.txt` como tarefa futura.

---

## 5. Submissão à Meta Quest Store

### Conceito

Tornar o app disponível publicamente via Meta Quest Store (ou App Lab como primeiro passo). A Meta tem requisitos técnicos rigorosos (VRC — Virtual Reality Check) que validam performance, segurança e UX.

### Tarefas

- [ ] **T5.1** — Executar **VRC Validator Tool** localmente e corrigir todas as falhas:
  - VRC.Performance.1: FPS nunca abaixo de 72 por mais de 5 frames consecutivos
  - VRC.Performance.2: App inicia em menos de 4 segundos
  - VRC.Security.1: Sem permissões desnecessárias no Manifest
  - VRC.Submission.1: Ícones e metadados completos
  ```bash
  # Rodar VRC validator (Meta Quest Developer Hub)
  # Instalar app no headset, executar validator via MQDH
  adb install -r app/build/outputs/apk/release/app-release.apk
  # No MQDH: Performance HUD → Record → Exportar relatório
  ```

- [ ] **T5.2** — Configurar **AndroidManifest.xml** para distribuição:
  ```xml
  <!-- Metadados obrigatórios para Quest Store -->
  <meta-data android:name="com.oculus.supportedDevices" 
             android:value="quest2|questpro|quest3|quest3s" />
  <meta-data android:name="com.oculus.handtracking.frequency" 
             android:value="HIGH" />
  <meta-data android:name="com.oculus.handtracking.version" 
             android:value="V2.0" />
  
  <!-- Categoria do app -->
  <meta-data android:name="com.oculus.ossplash" android:value="true" />
  
  <!-- Features opcionais (não required para não excluir Quest 2) -->
  <uses-feature android:name="com.oculus.feature.PASSTHROUGH" 
                android:required="false" />
  <uses-feature android:name="com.oculus.experimental.enabled" 
                android:required="false" />
  ```

- [ ] **T5.3** — Otimizar **tamanho do APK**:
  - Strip symbols dos `.so` no release build (`-s` no linker)
  - `resConfigs` para limitar idiomas empacotados
  - ProGuard/R8 para minificação do Kotlin
  - Verificar tamanho do FFmpeg `.so` — remover codecs/protocolos não usados
  ```kotlin
  // app/build.gradle.kts
  android {
      buildTypes {
          release {
              isMinifyEnabled = true
              isShrinkResources = true
              proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro")
          }
      }
      defaultConfig {
          // Só empacotar idiomas usados
          resConfigs("en", "pt-rBR", "es")
          // Só ARM64 (Quest é exclusivamente aarch64)
          ndk { abiFilters += listOf("arm64-v8a") }
      }
  }
  ```

- [ ] **T5.4** — Criar **assets de store** (ícones, banners, screenshots):
  - App icon: 256×256 (Quest Store requisito)
  - Hero banner: 2560×1440
  - Screenshots: mínimo 3, demonstrando reprodução 2D, 3D e 360°
  - Vídeo trailer (opcional): 30-60s mostrando UX

- [ ] **T5.5** — Escrever **Política de Privacidade**:
  - Hospedar em GitHub Pages (URL estável)
  - Declarar: sem telemetria (**RNF-SEC-003**), sem coleta de dados, processamento 100% local
  - Credenciais de servidor armazenadas localmente com criptografia

- [ ] **T5.6** — Preencher **IARC Age Rating**:
  - O app é um reprodutor — não contém conteúdo próprio
  - Rating esperado: "Everyone" / "Livre"

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Licenciamento FFmpeg (GPL vs LGPL)**: A build do FFmpeg DEVE usar `--enable-lgpl` (linking dinâmico). Se qualquer codec GPL-only estiver habilitado (`--enable-gpl`), o app inteiro herda GPL e NÃO pode ser distribuído sob MIT na Quest Store sem disponibilizar o source de tudo. Auditar `ffbuild/config.mak` antes de submeter — verificar que `CONFIG_GPL` está `0`.

> [!WARNING]
> **Permissões justificadas**: A Meta exige justificativa para `INTERNET`, `READ_MEDIA_*`, `MANAGE_EXTERNAL_STORAGE`. Preparar texto claro: "O app precisa de rede para acessar servidores de mídia local (NAS/DLNA)" e "Precisa de storage para acessar vídeos no armazenamento do headset."

> [!IMPORTANT]
> **App Lab vs Quest Store**: Considerar começar pelo App Lab (requisitos menos rígidos, processo de aprovação mais rápido) antes de solicitar listagem na Quest Store principal. App Lab permite distribuição pública com link direto.

> [!NOTE]
> **Tempo de review**: A Meta tipicamente leva 5-15 dias úteis para review inicial. Planeje pelo menos 2-3 ciclos de submissão/correção.

---

## 6. Auditoria de Segurança e Qualidade

### Conceito

Validação final de que todos os requisitos não-funcionais de segurança (**RNF-SEC-001** a **004**) e qualidade (**RNF-QUAL-001** a **006**) estão atendidos antes do release de produção.

### Tarefas

- [ ] **T6.1** — Auditoria de **armazenamento seguro de credenciais** (RNF-SEC-001):
  - Verificar que `SmbCredentialStore` usa `EncryptedSharedPreferences` com AES-256-GCM
  - Grep por senhas em texto plano em todo o código (logs, SharedPreferences comuns, Room)
  - Verificar que URIs com credenciais NUNCA são logadas inteiras (`protocols::smb::redact()`)
  ```bash
  # Auditoria automatizada: buscar potenciais vazamentos
  grep -rn "password" app/src/main/java/ --include="*.kt" | grep -v "encrypted\|Encrypted\|keystore\|Keystore\|test\|Test"
  grep -rn "senha" app/src/main/java/ --include="*.kt"
  
  # Verificar que logs de Rust não expõem credenciais
  grep -rn 'log::' rust/ --include="*.rs" | grep -i "pass\|cred\|auth\|secret"
  ```

- [ ] **T6.2** — Auditoria de **TLS/SSL** (RNF-SEC-002):
  - Verificar que `reqwest` usa `rustls-tls` (TLS puro Rust, sem dependência nativa)
  - Verificar que conexões HTTPS rejeitam certificados inválidos por padrão
  - Self-signed certificates aceitos APENAS com confirmação explícita do usuário

- [ ] **T6.3** — Verificar **ausência de telemetria** (RNF-SEC-003):
  - Grep por SDKs de analytics em `build.gradle.kts` (Firebase, Crashlytics, etc.)
  - Verificar que nenhum endpoint externo é contactado sem ação do usuário
  ```bash
  # Nenhuma dependência de analytics deve existir
  grep -rn "firebase\|crashlytics\|analytics\|sentry\|bugsnag" \
       app/build.gradle.kts build.gradle.kts
  # Resultado esperado: nenhum match
  ```

- [ ] **T6.4** — Atingir **cobertura de testes** (RNF-QUAL-001/002):
  - Rust core: ≥ 80% cobertura com `cargo tarpaulin`
  - Testes de integração Kotlin ↔ C++ ↔ Rust: validar todos os caminhos JNI
  - Adicionar `cargo tarpaulin` ao CI
  ```bash
  # Gerar relatório de cobertura Rust
  cargo install cargo-tarpaulin
  cargo tarpaulin --workspace --out Html --output-dir target/coverage
  ```

- [ ] **T6.5** — CI/CD **completo e verde** (RNF-QUAL-003):
  - Habilitar `ktlint` de verdade (não mais placeholder com `|| echo`)
  - Adicionar `clang-tidy` para código C++
  - Build completo (Rust + C++ + Kotlin) no CI (requer Meta OpenXR SDK no runner)
  - Bloquear merges na `main` sem CI verde
  ```yaml
  # .github/workflows/main.yml - completar os steps faltantes
  - name: Kotlin Lint
    run: ./gradlew ktlintCheck
    # SEM fallback || echo - deve falhar de verdade
  
  - name: C++ Lint  
    run: |
      find native/src -name '*.cpp' -o -name '*.h' | \
        xargs clang-tidy --checks='-*,readability-*,performance-*,bugprone-*'
  ```

- [ ] **T6.6** — **Logging estruturado** completo (RNF-QUAL-006):
  - Verificar que todas as camadas usam níveis de log corretos
  - Rust: `log` crate com `android_logger` para logcat
  - C++: `__android_log_print` com tags consistentes
  - Kotlin: `Log.d`/`Log.i`/`Log.w`/`Log.e` com tag uniforme
  - Nenhum `println!` ou `System.out.println` em código de produção

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **AddressSanitizer (ASan) no Quest 3**: Rodar ASan em hardware real requer `wrap.sh` no APK e tem overhead de ~2-3x em CPU e memória. Use ASan apenas em builds de teste dedicados, NUNCA no release. Para memory leak detection, use `dumpsys meminfo` + testes de duração longa em vez de ASan.

> [!WARNING]
> **Dependências GPL transitivas**: Além do FFmpeg, verificar TODAS as dependências Rust (`cargo license`) e Kotlin (Gradle dependency tree) para contaminação GPL. Crates Rust comuns que são GPL: `readline` (não usado aqui, mas verificar).
> ```bash
> # Verificar licenças de todas as dependências Rust
> cargo install cargo-license
> cargo license --workspace --avoid-dev-only | grep -i "GPL"
> ```

> [!IMPORTANT]
> **Cobertura de 80% é um alvo, não um requisito absoluto**: Foque cobertura nos caminhos críticos (demuxer, decoder, sync, protocolos de rede) em vez de perseguir percentual. Código de UI/bridge pode ter cobertura menor sem risco.

---

## 7. Cuidados Transversais da Fase 1.0

### Estratégia de Versionamento

> [!IMPORTANT]
> **SemVer estrito a partir de v1.0**: Formato `v1.0.0`. Patches (v1.0.1) para bugfixes sem mudança de API/DB. Minor (v1.1.0) para features novas backward-compatible. Major (v2.0.0) para breaking changes.

### Cadeia de Migração do Room

> [!CAUTION]
> **A cadeia de migration DEVE ser testada de ponta a ponta**: Um usuário pode estar em qualquer versão anterior. Teste: fresh install v1.0 E upgrade de cada versão anterior (v0.1 → v1.0, v0.2 → v1.0, etc.). NUNCA use `fallbackToDestructiveMigration()` — o usuário perde histórico, playlists e configurações.
> ```kotlin
> // Cadeia completa de migrations
> Room.databaseBuilder(context, AppDatabase::class.java, "vrplayer.db")
>     .addMigrations(
>         MIGRATION_1_2,  // v0.1 → v0.2: tabelas de rede
>         MIGRATION_2_3,  // v0.2 → v0.3: playlists
>         MIGRATION_3_4,  // v0.3 → v0.4: downloads
>         MIGRATION_4_5,  // v0.4 → v0.5: múltiplas telas config
>         MIGRATION_5_6,  // v0.5 → v1.0: ambientes customizados
>     )
>     .build()
> ```

### Checklist de Release

> [!IMPORTANT]
> **Antes de tagar v1.0.0**, verificar TODOS os itens:
> 1. CI verde (clippy + ktlint + clang-tidy + testes)
> 2. Cobertura Rust ≥ 80% nos crates `core` e `protocols`
> 3. `cargo audit` sem vulnerabilidades críticas
> 4. APK assinado com keystore de release (não debug)
> 5. ProGuard/R8 habilitado, app testado com minificação
> 6. Teste de duração longa (60 min) sem leak/crash/thermal no Quest 3
> 7. Todas as strings externalizadas (zero hardcoded strings na UI)
> 8. Política de privacidade publicada em URL estável
> 9. VRC validator local: 0 erros críticos
> 10. Cadeia de migration Room testada (fresh install + upgrade de cada versão)

### Limitações Conhecidas para Release Notes

> [!NOTE]
> Documentar honestamente nas Release Notes:
> - 8K decode limitado a 30fps (hardware do Quest 3)
> - AV1 HW decode depende do firmware (fallback SW com aviso)
> - FTP seek é lento (reconexão a cada seek)
> - Ambientes customizados com >100K triângulos não são aceitos
> - Eye tracking requer calibração prévia nas configurações do Quest

---

## 8. Definição de Pronto (Definition of Done) — v1.0

### Ambientes Customizáveis
- [ ] Importação de `.glb` via file picker funciona no Quest 3
- [ ] Validação rejeita modelos com >100K triângulos ou texturas >4096px
- [ ] Configurador interativo de posição de tela funciona
- [ ] `config.json` é gerado e persistido corretamente
- [ ] Ambiente customizado aparece no seletor e carrega sem crash
- [ ] `AdaptiveQualityManager` degrada para void se ambiente customizado causa FPS <72
- [ ] Thumbnail gerado automaticamente aparece na lista

### Community Translations
- [ ] Script `validate_i18n.sh` detecta chaves faltando, extras e formatadores inválidos
- [ ] CI bloqueia PRs de tradução com erros de validação
- [ ] `CONTRIBUTING_I18N.md` publicado e testado por pelo menos um contribuidor externo
- [ ] Tela "Sobre" mostra créditos de tradutores
- [ ] Auditoria de plurais completa — todos os idiomas existentes (PT-BR, EN, ES) verificados

### Polimento UX
- [ ] Tutorial de primeira execução dispara corretamente (e pode ser resetado)
- [ ] Painel de configurações unificado acessível e funcional com todas as opções
- [ ] Loading spinner/barra de progresso exibidos em todas as operações longas (>1s)
- [ ] Erros exibidos como mensagens legíveis (nenhum código de erro cru na UI)
- [ ] "Texto Maior" funciona e não quebra layout
- [ ] Todas as funcionalidades acessíveis com controllers apenas (sem hand/eye tracking obrigatório)

### Documentação
- [ ] `cargo doc --no-deps --workspace` compila sem warnings com `#![deny(missing_docs)]`
- [ ] `./gradlew dokkaHtml` gera docs Kotlin sem erro
- [ ] `BUILD.md` testado por alguém que nunca compilou o projeto — compilou com sucesso
- [ ] `USER_GUIDE.md` cobre todos os protocolos e formatos suportados
- [ ] `ARCHITECTURE.md` diagrama as 3 camadas e fluxos de dados
- [ ] `README.md` tem badges de CI, screenshots e quick start

### Quest Store
- [ ] VRC validator: 0 erros críticos
- [ ] APK release assinado e minificado
- [ ] Tamanho do APK < 500MB
- [ ] Política de privacidade publicada em URL acessível
- [ ] Ícones e banners nos tamanhos corretos
- [ ] App Lab submission (pelo menos) aceita pela Meta

### Segurança e Qualidade
- [ ] Zero credenciais em texto plano encontradas por auditoria
- [ ] TLS/SSL ativo em todas as conexões HTTPS, SFTP
- [ ] Zero dependências GPL no release build (FFmpeg com `--enable-lgpl`)
- [ ] `cargo clippy -- -D warnings`: zero warnings
- [ ] `./gradlew ktlintCheck`: passa sem fallback
- [ ] Cobertura Rust ≥ 80% nos crates `core` e `protocols`
- [ ] CI verde e bloqueia merges em falha
- [ ] Teste de imersão 60 min (conteúdo misto, troca de ambientes e fontes) sem leak, crash ou thermal shutdown

### Geral
- [ ] Nenhuma regressão nos testes das fases 0.1–0.5
- [ ] Cadeia de migration Room testada (fresh install + upgrade de v0.1, v0.3, v0.5)
- [ ] `cargo audit` sem vulnerabilidades de severidade alta/crítica
- [ ] Release notes publicadas com features, limitações conhecidas e créditos

---

*Fase 1.0 — Estimativa: 6-10 semanas para desenvolvedor solo experiente*  
*Foco intensivo em polimento, testes e documentação — pouco código novo, muito refinamento.*  
*Dependência: Fase 0.5 DEVE estar completa e estável antes de iniciar v1.0*
