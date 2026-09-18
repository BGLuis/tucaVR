#pragma once

// docs/reports/MODO-AMBIENTE.md — Modo Ambiente: halo de luz atras da tela
// de video derivado da cor de baixa frequencia do frame (bias lighting, o
// analogo VR do "modo ambiente" do YouTube). So caminho Vulkan (2.5 do
// relatorio: o GLES monta a cena empilhando ovrDrawSurface, arquitetura
// incompativel com vkCmdBindPipeline/vkCmdDraw — seria uma segunda
// implementacao inteira, nao um port). Constantes num header compartilhado
// pra nao divergirem se o GLES um dia ganhar a feature — mesmo motivo de
// vr_player_feedback_overlay.h.

#include <cstdint>

namespace vrplayer {

// Resolucao do alvo offscreen que guarda a media reduzida do frame de
// video (opcao C da secao 2.1 do relatorio: pequeno o bastante pra caber
// em cache, grande o bastante pra dar uma leve variacao espacial ao halo
// em vez de uma cor plana unica). Se mudar, atualizar tambem o
// `vec2(32.0, 18.0)` hardcoded em ambient_downsample.frag.
constexpr uint32_t kAmbientTargetWidth = 32;
constexpr uint32_t kAmbientTargetHeight = 18;

// Fator de escala do quad do halo sobre screenScaleX/Y (2.2 do relatorio) —
// o halo se estende 60% alem do retangulo do video em cada direcao.
constexpr float kAmbientHaloScale = 1.6f;

// Largura da rampa smoothstep entre a borda do retangulo do video (alpha
// maximo) e a borda externa do halo (alpha 0). Medida num espaco corrigido
// por aspect ratio (ver ambient.frag: p = (uv-0.5)*vec2(aspect,1.0)), nao
// em UV cru — por isso o valor bate com o eixo Y sem alteracao mesmo apos a
// correcao de "halo esticado" (a tela nao e quadrada, screenScaleX/Y).
constexpr float kAmbientEdgeWidth = 0.22f;

// Intensidade maxima do halo (multiplicador de alpha) — ponto de partida
// pra calibracao em headset (F5 do relatorio).
constexpr float kAmbientMaxIntensity = 0.85f;

// Suavizacao temporal (2.3 do relatorio, "obrigatoria, nao polimento") —
// tempo para o halo convergir pra uma nova cor apos uma mudanca abrupta
// (corte de cena) ou pra um novo estado de intensidade (gate ligou/desligou).
// Deliberadamente mais lento que kUiFadeDuration (0.35s, vr_player_input_vulkan.h)
// pra nao piscar na periferia.
constexpr float kAmbientColorSmoothSeconds = 0.6f;

// O passe de reducao (F2) so roda 1 a cada N frames — a cor de ambiente
// nao precisa de 90Hz pra parecer estavel, e cada execucao custa fill rate
// que o orcamento termico do Quest 3 nao sobra (risco 1 do relatorio).
constexpr uint32_t kAmbientDecimationFrames = 3;

// Fator de alargamento do box-filter de reducao (F2) sobre texelSize — cada
// texel de saida passa a amostrar uma area kAmbientDownsampleKernelScale
// vezes maior que o proprio texel, sobrepondo com os vizinhos. Sem isso, um
// detalhe fino do frame (ex. uma marca colorida pequena) pode cair inteiro
// num texel so e criar um salto de valor na borda dele — ampliado ~50-80x
// pelo halo, isso aparece como banda/"borda treta" visivel em vez de um
// brilho suave. Se mudar, atualizar tambem o `kKernelScale` hardcoded em
// ambient_downsample.frag.
constexpr float kAmbientDownsampleKernelScale = 2.0f;

}  // namespace vrplayer
