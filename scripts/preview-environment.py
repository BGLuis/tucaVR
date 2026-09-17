#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
preview-environment.py — Visualizador 3D Desktop/Web dos Ambientes do tucaVR.

Permite inspecionar, navegar e testar os ambientes virtuais 3D (Cinema, Sala de Estar,
Espaço Cósmico e Void), a tela virtual ancorada e o áudio ambiente diretamente no
navegador do PC, sem necessidade de óculos VR nem do headset conectado.
"""

import http.server
import json
import os
import socket
import socketserver
import sys
import urllib.parse
import webbrowser

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.dirname(SCRIPT_DIR)
ASSETS_DIR = os.path.join(ROOT_DIR, "app", "src", "main", "assets", "environments")

DEFAULT_PORT = 8080


def parse_ini_file(filepath):
    """Lê um arquivo config.ini de ambiente de forma simples e direta."""
    config = {}
    if not os.path.exists(filepath):
        return config
    with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                k, v = line.split("=", 1)
                config[k.strip()] = v.strip()
    return config


def get_all_environments():
    """Descobre e retorna a lista de ambientes e suas configurações."""
    envs = []
    if not os.path.exists(ASSETS_DIR):
        return envs

    for entry in sorted(os.listdir(ASSETS_DIR)):
        env_path = os.path.join(ASSETS_DIR, entry)
        if os.path.isdir(env_path):
            ini_file = os.path.join(env_path, "config.ini")
            cfg = parse_ini_file(ini_file)
            env_id = cfg.get("id", entry)
            env_name = cfg.get("name", entry.capitalize())

            # Posição e escala da tela
            pos_str = cfg.get("screen_pos", "0.0,1.5,-2.4")
            scale_str = cfg.get("screen_scale", "2.8,1.575")
            try:
                screen_pos = [float(x.strip()) for x in pos_str.split(",")]
            except Exception:
                screen_pos = [0.0, 1.5, -2.4]

            try:
                screen_scale = [float(x.strip()) for x in scale_str.split(",")]
            except Exception:
                screen_scale = [2.8, 1.575]

            model_rel = cfg.get("model_file", f"{entry}/model.glb")
            model_file = os.path.join(ASSETS_DIR, model_rel)
            has_model = os.path.exists(model_file)

            skybox_rel = cfg.get("skybox_file", "")
            skybox_file = os.path.join(ASSETS_DIR, skybox_rel) if skybox_rel else ""
            has_skybox = bool(skybox_file and os.path.exists(skybox_file))

            audio_rel = cfg.get("ambient_audio", "")
            audio_file = os.path.join(ASSETS_DIR, audio_rel) if audio_rel else ""
            has_audio = bool(audio_file and os.path.exists(audio_file))

            volume = 0.0
            try:
                volume = float(cfg.get("ambient_volume", "0.0"))
            except Exception:
                pass

            envs.append({
                "id": env_id,
                "name": env_name,
                "screen_pos": screen_pos,
                "screen_scale": screen_scale,
                "has_model": has_model,
                "model_url": f"/assets/{model_rel}" if has_model else None,
                "has_skybox": has_skybox,
                "skybox_url": f"/assets/{skybox_rel}" if has_skybox else None,
                "has_audio": has_audio,
                "audio_url": f"/assets/{audio_rel}" if has_audio else None,
                "volume": volume
            })
    return envs


HTML_PAGE = """<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>tucaVR — Visualizador 3D de Ambientes (Desktop Preview)</title>
  <style>
    :root {
      --bg-dark: #0a0c10;
      --panel-bg: rgba(16, 20, 28, 0.85);
      --panel-border: rgba(56, 189, 248, 0.25);
      --accent: #38bdf8;
      --accent-hover: #0ea5e9;
      --text: #f1f5f9;
      --text-muted: #94a3b8;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body, html {
      width: 100%; height: 100%; overflow: hidden;
      background: var(--bg-dark); color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    }
    #canvas-container { width: 100%; height: 100%; position: absolute; top: 0; left: 0; }

    /* HUD Superior */
    .top-bar {
      position: absolute; top: 16px; left: 50%; transform: translateX(-50%);
      background: var(--panel-bg); backdrop-filter: blur(12px);
      border: 1px solid var(--panel-border); border-radius: 12px;
      padding: 8px 16px; display: flex; align-items: center; gap: 12px;
      z-index: 10; box-shadow: 0 8px 32px rgba(0,0,0,0.5);
    }
    .logo-badge {
      font-weight: 700; color: var(--accent); font-size: 14px;
      letter-spacing: 1px; display: flex; align-items: center; gap: 6px;
    }
    .env-button {
      background: rgba(255, 255, 255, 0.05); border: 1px solid rgba(255, 255, 255, 0.1);
      color: var(--text-muted); padding: 6px 14px; border-radius: 8px;
      cursor: pointer; font-size: 13px; font-weight: 500; transition: all 0.2s ease;
    }
    .env-button:hover { background: rgba(56, 189, 248, 0.15); color: #fff; }
    .env-button.active {
      background: var(--accent); color: #000; font-weight: 600;
      border-color: var(--accent); box-shadow: 0 0 16px rgba(56, 189, 248, 0.4);
    }

    /* Painel de Informações Lateral */
    .info-panel {
      position: absolute; bottom: 20px; left: 20px;
      background: var(--panel-bg); backdrop-filter: blur(12px);
      border: 1px solid var(--panel-border); border-radius: 12px;
      padding: 16px; width: 310px; z-index: 10; font-size: 13px;
      box-shadow: 0 8px 32px rgba(0,0,0,0.5);
    }
    .info-panel h3 { font-size: 14px; color: var(--accent); margin-bottom: 8px; display: flex; align-items: center; gap: 6px; }
    .info-row { display: flex; justify-content: space-between; margin: 4px 0; color: var(--text-muted); }
    .info-value { color: #fff; font-family: monospace; }

    /* Painel de Controles e Câmera */
    .controls-panel {
      position: absolute; bottom: 20px; right: 20px;
      background: var(--panel-bg); backdrop-filter: blur(12px);
      border: 1px solid var(--panel-border); border-radius: 12px;
      padding: 16px; width: 310px; z-index: 10; font-size: 13px;
      box-shadow: 0 8px 32px rgba(0,0,0,0.5);
    }
    .controls-panel h3 { font-size: 14px; color: var(--accent); margin-bottom: 8px; }
    .btn-group { display: flex; gap: 8px; margin-top: 8px; }
    .btn-action {
      flex: 1; padding: 6px 10px; border-radius: 8px;
      background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.15);
      color: #fff; cursor: pointer; font-size: 12px; transition: 0.2s;
    }
    .btn-action:hover { background: rgba(56,189,248,0.25); border-color: var(--accent); }
    .btn-action.active { background: var(--accent); color: #000; font-weight: 600; }

    /* Retículo / Crosshair para primeira pessoa */
    #crosshair {
      position: absolute; top: 50%; left: 50%; width: 6px; height: 6px;
      background: rgba(56, 189, 248, 0.7); border-radius: 50%;
      transform: translate(-50%, -50%); pointer-events: none; display: none; z-index: 5;
    }

    /* Instruções de FPS */
    #fps-prompt {
      position: absolute; top: 80px; left: 50%; transform: translateX(-50%);
      background: rgba(0,0,0,0.7); border: 1px solid rgba(255,255,255,0.2);
      padding: 8px 16px; border-radius: 20px; font-size: 12px;
      display: none; z-index: 9; color: #cbd5e1;
    }

    #loading-indicator {
      position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);
      font-size: 15px; color: var(--accent); display: none; z-index: 20;
      background: rgba(10,12,16,0.9); padding: 12px 24px; border-radius: 24px;
      border: 1px solid var(--accent); box-shadow: 0 0 24px rgba(56,189,248,0.3);
    }
  </style>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/loaders/GLTFLoader.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/controls/OrbitControls.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/controls/PointerLockControls.js"></script>
</head>
<body>
  <div id="canvas-container"></div>
  <div id="crosshair"></div>
  <div id="fps-prompt">Clique na tela para controlar a visão com o mouse. Pressione <b>ESC</b> para liberar. Use <b>W, A, S, D</b> para mover.</div>
  <div id="loading-indicator">Carregando ambiente 3D...</div>

  <!-- Barra Superior de Seleção de Ambientes -->
  <div class="top-bar">
    <div class="logo-badge">🥽 tucaVR</div>
    <div id="env-selector" style="display: flex; gap: 6px;"></div>
  </div>

  <!-- Painel de Informações -->
  <div class="info-panel">
    <h3 id="env-title">Ambiente: Cinema IMAX</h3>
    <div class="info-row"><span>Tela Posição:</span> <span id="val-pos" class="info-value">0.0, 2.2, -7.5</span></div>
    <div class="info-row"><span>Tela Escala:</span> <span id="val-scale" class="info-value">6.0 × 3.38m</span></div>
    <div class="info-row"><span>Distância Óptica:</span> <span id="val-dist" class="info-value">7.5m</span></div>
    <div class="info-row"><span>Polígonos da Cena:</span> <span id="val-poly" class="info-value">--</span></div>
    <div class="info-row"><span>Áudio Ambiência:</span> <span id="val-audio" class="info-value">--</span></div>
  </div>

  <!-- Painel de Controles -->
  <div class="controls-panel">
    <h3>Câmera e Visualização</h3>
    <div class="btn-group">
      <button id="btn-pov" class="btn-action active">👁️ Primeira Pessoa</button>
      <button id="btn-orbit" class="btn-action">🌐 Órbita Livre</button>
    </div>
    <div class="btn-group" style="margin-top: 8px;">
      <button id="btn-recenter" class="btn-action">🎯 Recentralizar (R)</button>
      <button id="btn-audio-toggle" class="btn-action">🔊 Ambiência</button>
    </div>
    <div style="margin-top: 10px; font-size: 11px; color: var(--text-muted); line-height: 1.4;">
      <b>Atalhos:</b> WASD para andar, Espaço/Shift para subir/descer, R para recentralizar visão.
    </div>
  </div>

  <script>
    let environments = [];
    let currentEnv = null;
    let scene, camera, renderer, orbitControls, fpControls;
    let currentModel = null;
    let skyboxMesh = null;
    let screenMesh = null;
    let screenFrame = null;
    let ambientAudio = null;
    let isFpMode = true;
    let moveForward = false, moveBackward = false, moveLeft = false, moveRight = false, moveUp = false, moveDown = false;
    let prevTime = performance.now();
    const velocity = new THREE.Vector3();
    const direction = new THREE.Vector3();

    // Textura animada para a tela de cinema virtual
    let testPatternCanvas, testPatternCtx, testPatternTexture;

    function createTestPatternTexture() {
      testPatternCanvas = document.createElement('canvas');
      testPatternCanvas.width = 1024;
      testPatternCanvas.height = 576;
      testPatternCtx = testPatternCanvas.getContext('2d');
      testPatternTexture = new THREE.CanvasTexture(testPatternCanvas);
      updateTestPattern(0);
      return testPatternTexture;
    }

    function updateTestPattern(time) {
      if (!testPatternCtx) return;
      const w = testPatternCanvas.width;
      const h = testPatternCanvas.height;

      // Fundo gradiente cinematográfico suave
      const grad = testPatternCtx.createLinearGradient(0, 0, w, h);
      grad.addColorStop(0, '#0f172a');
      grad.addColorStop(0.5, '#1e1b4b');
      grad.addColorStop(1, '#020617');
      testPatternCtx.fillStyle = grad;
      testPatternCtx.fillRect(0, 0, w, h);

      // Linhas de barras de cor SMPTE sutis
      const colors = ['#ffffff', '#facc15', '#38bdf8', '#4ade80', '#c084fc', '#f87171', '#3b82f6'];
      const barW = w / colors.length;
      for (let i = 0; i < colors.length; i++) {
        testPatternCtx.fillStyle = colors[i];
        testPatternCtx.fillRect(i * barW, h - 80, barW, 80);
      }

      // Moldura e informações centrais
      testPatternCtx.strokeStyle = 'rgba(56, 189, 248, 0.4)';
      testPatternCtx.lineWidth = 4;
      testPatternCtx.strokeRect(20, 20, w - 40, h - 40);

      testPatternCtx.fillStyle = '#f8fafc';
      testPatternCtx.font = 'bold 36px sans-serif';
      testPatternCtx.textAlign = 'center';
      testPatternCtx.fillText('tucaVR Virtual Cinema', w / 2, h / 2 - 20);

      testPatternCtx.fillStyle = '#94a3b8';
      testPatternCtx.font = '22px monospace';
      const sec = (time * 0.001).toFixed(1);
      testPatternCtx.fillText('Tela Virtual 16:9 • ' + (currentEnv ? currentEnv.name : '') + ' • ' + sec + 's', w / 2, h / 2 + 25);

      if (testPatternTexture) testPatternTexture.needsUpdate = true;
    }

    function initScene() {
      const container = document.getElementById('canvas-container');
      scene = new THREE.Scene();
      scene.background = new THREE.Color(0x0a0c10);

      camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.05, 1000);
      camera.position.set(0, 1.6, 0);

      renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
      renderer.setSize(window.innerWidth, window.innerHeight);
      renderer.setPixelRatio(window.devicePixelRatio);
      renderer.outputEncoding = THREE.sRGBEncoding;
      container.appendChild(renderer.domElement);

      // Iluminação geral suave
      const ambientLight = new THREE.AmbientLight(0xffffff, 0.85);
      scene.add(ambientLight);

      const dirLight = new THREE.DirectionalLight(0xffffff, 0.45);
      dirLight.position.set(0, 5, 2);
      scene.add(dirLight);

      // Controles de Órbita
      orbitControls = new THREE.OrbitControls(camera, renderer.domElement);
      orbitControls.enableDamping = true;
      orbitControls.dampingFactor = 0.05;
      orbitControls.target.set(0, 1.6, -3);
      orbitControls.enabled = false;

      // Controles de Primeira Pessoa
      fpControls = new THREE.PointerLockControls(camera, document.body);

      const fpsPrompt = document.getElementById('fps-prompt');
      const crosshair = document.getElementById('crosshair');

      fpControls.addEventListener('lock', () => {
        fpsPrompt.style.display = 'none';
        crosshair.style.display = 'block';
      });

      fpControls.addEventListener('unlock', () => {
        if (isFpMode) {
          fpsPrompt.style.display = 'block';
        }
        crosshair.style.display = 'none';
      });

      renderer.domElement.addEventListener('click', () => {
        if (isFpMode && !fpControls.isLocked) {
          fpControls.lock();
        }
      });

      // Teclas de Movimentação
      window.addEventListener('keydown', (e) => {
        switch (e.code) {
          case 'KeyW': case 'ArrowUp': moveForward = true; break;
          case 'KeyS': case 'ArrowDown': moveBackward = true; break;
          case 'KeyA': case 'ArrowLeft': moveLeft = true; break;
          case 'KeyD': case 'ArrowRight': moveRight = true; break;
          case 'Space': moveUp = true; break;
          case 'ShiftLeft': case 'ShiftRight': moveDown = true; break;
          case 'KeyR': recenterCamera(); break;
        }
      });

      window.addEventListener('keyup', (e) => {
        switch (e.code) {
          case 'KeyW': case 'ArrowUp': moveForward = false; break;
          case 'KeyS': case 'ArrowDown': moveBackward = false; break;
          case 'KeyA': case 'ArrowLeft': moveLeft = false; break;
          case 'KeyD': case 'ArrowRight': moveRight = false; break;
          case 'Space': moveUp = false; break;
          case 'ShiftLeft': case 'ShiftRight': moveDown = false; break;
        }
      });

      window.addEventListener('resize', onWindowResize);

      // Cria a tela virtual com o padrão de teste
      createVirtualScreen();

      // Configuração dos Botões da Interface
      document.getElementById('btn-pov').addEventListener('click', () => setCameraMode(true));
      document.getElementById('btn-orbit').addEventListener('click', () => setCameraMode(false));
      document.getElementById('btn-recenter').addEventListener('click', recenterCamera);
      document.getElementById('btn-audio-toggle').addEventListener('click', toggleAudio);
    }

    function createVirtualScreen() {
      const tex = createTestPatternTexture();
      const geom = new THREE.PlaneGeometry(1, 1);
      const mat = new THREE.MeshBasicMaterial({ map: tex, side: THREE.DoubleSide });
      screenMesh = new THREE.Mesh(geom, mat);
      scene.add(screenMesh);

      // Moldura elegante ao redor da tela
      const edges = new THREE.EdgesGeometry(geom);
      screenFrame = new THREE.LineSegments(edges, new THREE.LineBasicMaterial({ color: 0x38bdf8, linewidth: 2 }));
      scene.add(screenFrame);
    }

    function updateVirtualScreenTransform(pos, scale) {
      if (!screenMesh) return;
      screenMesh.position.set(pos[0], pos[1], pos[2]);
      screenMesh.scale.set(scale[0], scale[1], 1);

      if (screenFrame) {
        screenFrame.position.set(pos[0], pos[1], pos[2]);
        screenFrame.scale.set(scale[0], scale[1], 1);
      }

      document.getElementById('val-pos').textContent = `${pos[0].toFixed(2)}, ${pos[1].toFixed(2)}, ${pos[2].toFixed(2)}`;
      document.getElementById('val-scale').textContent = `${scale[0].toFixed(2)} × ${scale[1].toFixed(2)}m`;
      const dist = Math.sqrt(pos[0]*pos[0] + pos[1]*pos[1] + pos[2]*pos[2]).toFixed(2);
      document.getElementById('val-dist').textContent = `${dist}m`;
    }

    function setCameraMode(fp) {
      isFpMode = fp;
      document.getElementById('btn-pov').classList.toggle('active', fp);
      document.getElementById('btn-orbit').classList.toggle('active', !fp);

      const fpsPrompt = document.getElementById('fps-prompt');
      if (fp) {
        orbitControls.enabled = false;
        fpsPrompt.style.display = 'block';
        recenterCamera();
      } else {
        if (fpControls.isLocked) fpControls.unlock();
        fpsPrompt.style.display = 'none';
        orbitControls.enabled = true;
        camera.position.set(0, 3, 4);
        orbitControls.target.set(0, 1.6, currentEnv ? currentEnv.screen_pos[2] / 2 : -2);
      }
    }

    function recenterCamera() {
      camera.position.set(0, 1.6, 0);
      camera.rotation.set(0, 0, 0);
      if (orbitControls.enabled) {
        orbitControls.target.set(0, 1.6, currentEnv ? currentEnv.screen_pos[2] : -3);
      }
    }

    function toggleAudio() {
      if (!ambientAudio) return;
      const btn = document.getElementById('btn-audio-toggle');
      if (ambientAudio.paused) {
        ambientAudio.play();
        btn.classList.add('active');
      } else {
        ambientAudio.pause();
        btn.classList.remove('active');
      }
    }

    function loadEnvironment(env) {
      currentEnv = env;
      document.getElementById('env-title').textContent = 'Ambiente: ' + env.name;

      // Atualiza botões
      document.querySelectorAll('.env-button').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.id === env.id);
      });

      // Atualiza posição e escala da tela virtual
      updateVirtualScreenTransform(env.screen_pos, env.screen_scale);

      // Remove modelo anterior
      if (currentModel) {
        scene.remove(currentModel);
        currentModel = null;
      }

      // Remove skybox anterior
      if (skyboxMesh) {
        scene.remove(skyboxMesh);
        skyboxMesh = null;
      }

      // Áudio ambiente
      if (ambientAudio) {
        ambientAudio.pause();
        ambientAudio = null;
        document.getElementById('btn-audio-toggle').classList.remove('active');
      }

      if (env.has_audio && env.audio_url) {
        ambientAudio = new Audio(env.audio_url);
        ambientAudio.loop = true;
        ambientAudio.volume = env.volume > 0 ? env.volume : 0.2;
        document.getElementById('val-audio').textContent = `${(ambientAudio.volume * 100).toFixed(0)}% (Loop)`;
        document.getElementById('btn-audio-toggle').style.display = 'inline-block';
      } else {
        document.getElementById('val-audio').textContent = 'Nenhum';
        document.getElementById('btn-audio-toggle').style.display = 'none';
      }

      // Carrega Skybox se houver
      if (env.has_skybox && env.skybox_url) {
        const texLoader = new THREE.TextureLoader();
        texLoader.load(env.skybox_url, (tex) => {
          tex.mapping = THREE.EquirectangularReflectionMapping;
          const skyGeom = new THREE.SphereGeometry(500, 32, 16);
          const skyMat = new THREE.MeshBasicMaterial({ map: tex, side: THREE.BackSide });
          skyboxMesh = new THREE.Mesh(skyGeom, skyMat);
          scene.add(skyboxMesh);
        });
      }

      // Carrega Modelo 3D se houver
      const loading = document.getElementById('loading-indicator');
      if (env.has_model && env.model_url) {
        loading.style.display = 'block';
        const loader = new THREE.GLTFLoader();
        loader.load(env.model_url, (gltf) => {
          loading.style.display = 'none';
          currentModel = gltf.scene;

          let polyCount = 0;
          currentModel.traverse((child) => {
            if (child.isMesh) {
              // Garante que cores de vertice sejam respeitadas
              if (child.geometry && child.geometry.attributes.color) {
                child.material.vertexColors = true;
              }
              if (child.geometry && child.geometry.index) {
                polyCount += child.geometry.index.count / 3;
              } else if (child.geometry && child.geometry.attributes.position) {
                polyCount += child.geometry.attributes.position.count / 3;
              }
            }
          });

          document.getElementById('val-poly').textContent = Math.round(polyCount).toLocaleString('pt-BR');
          scene.add(currentModel);
        }, undefined, (err) => {
          loading.style.display = 'none';
          console.error('Erro ao carregar GLTF:', err);
          document.getElementById('val-poly').textContent = 'Erro ao carregar';
        });
      } else {
        loading.style.display = 'none';
        document.getElementById('val-poly').textContent = '0 (Void)';
      }

      recenterCamera();
    }

    function onWindowResize() {
      camera.aspect = window.innerWidth / window.innerHeight;
      camera.updateProjectionMatrix();
      renderer.setSize(window.innerWidth, window.innerHeight);
    }

    function animate() {
      requestAnimationFrame(animate);

      const time = performance.now();
      const delta = (time - prevTime) / 1000;

      updateTestPattern(time);

      if (isFpMode && fpControls.isLocked) {
        velocity.x -= velocity.x * 10.0 * delta;
        velocity.z -= velocity.z * 10.0 * delta;
        velocity.y -= velocity.y * 10.0 * delta;

        direction.z = Number(moveForward) - Number(moveBackward);
        direction.x = Number(moveRight) - Number(moveLeft);
        direction.y = Number(moveUp) - Number(moveDown);
        direction.normalize();

        const speed = 4.0;
        if (moveForward || moveBackward) velocity.z -= direction.z * speed * 10.0 * delta;
        if (moveLeft || moveRight) velocity.x -= direction.x * speed * 10.0 * delta;
        if (moveUp || moveDown) velocity.y += direction.y * speed * 10.0 * delta;

        fpControls.moveRight(-velocity.x * delta);
        fpControls.moveForward(-velocity.z * delta);
        camera.position.y += velocity.y * delta;
      } else if (orbitControls.enabled) {
        orbitControls.update();
      }

      prevTime = time;
      renderer.render(scene, camera);
    }

    // Carrega ambientes via API
    fetch('/api/environments')
      .then(res => res.json())
      .then(data => {
        environments = data;
        const selector = document.getElementById('env-selector');
        environments.forEach((env, idx) => {
          const btn = document.createElement('button');
          btn.className = 'env-button' + (idx === 0 ? ' active' : '');
          btn.dataset.id = env.id;
          btn.textContent = env.name;
          btn.onclick = () => loadEnvironment(env);
          selector.appendChild(btn);
        });

        initScene();
        if (environments.length > 0) {
          loadEnvironment(environments[0]);
        }
        setCameraMode(true);
        animate();
      })
      .catch(err => console.error('Erro ao buscar ambientes:', err));
  </script>
</body>
</html>
"""


class EnvironmentPreviewHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path in ("/", "/index.html"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(HTML_PAGE.encode("utf-8"))
            return

        if path == "/api/environments":
            envs = get_all_environments()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(envs, indent=2).encode("utf-8"))
            return

        if path.startswith("/assets/"):
            rel_path = path[len("/assets/"):]
            # Protege contra directory traversal
            clean_rel = os.path.normpath(rel_path).lstrip("/\\")
            file_path = os.path.join(ASSETS_DIR, clean_rel)

            if os.path.exists(file_path) and os.path.isfile(file_path):
                self.send_response(200)
                if file_path.endswith(".glb"):
                    self.send_header("Content-Type", "model/gltf-binary")
                elif file_path.endswith(".png"):
                    self.send_header("Content-Type", "image/png")
                elif file_path.endswith(".jpg") or file_path.endswith(".jpeg"):
                    self.send_header("Content-Type", "image/jpeg")
                elif file_path.endswith(".ogg"):
                    self.send_header("Content-Type", "audio/ogg")
                elif file_path.endswith(".ini"):
                    self.send_header("Content-Type", "text/plain")
                else:
                    self.send_header("Content-Type", "application/octet-stream")

                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Content-Length", str(os.path.getsize(file_path)))
                self.end_headers()

                with open(file_path, "rb") as f:
                    while True:
                        chunk = f.read(65536)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                return
            else:
                self.send_error(404, f"Arquivo não encontrado: {rel_path}")
                return

        super().do_GET()

    def log_message(self, format, *args):
        # Silencia logs de requisições individuais para manter o terminal limpo
        pass


def find_free_port(start_port=DEFAULT_PORT):
    port = start_port
    while port < start_port + 50:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            if s.connect_ex(("127.0.0.1", port)) != 0:
                return port
        port += 1
    return DEFAULT_PORT


def main():
    port = find_free_port(DEFAULT_PORT)
    server_address = ("127.0.0.1", port)

    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(server_address, EnvironmentPreviewHandler) as httpd:
        url = f"http://127.0.0.1:{port}"
        print("==================================================================")
        print("🥽 tucaVR — Visualizador 3D de Ambientes no PC (Desktop Preview)")
        print("==================================================================")
        print(f"Servidor iniciado com sucesso!")
        print(f"URL: {url}")
        print("Pressione Ctrl+C para encerrar o servidor.")
        print("==================================================================")

        try:
            webbrowser.open(url)
        except Exception:
            pass

        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nEncerrando servidor...")


if __name__ == "__main__":
    main()
