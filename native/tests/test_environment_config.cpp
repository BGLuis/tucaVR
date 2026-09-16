#include "environment_config.h"
#include <cassert>
#include <cmath>
#include <iostream>

static void TestDefaultConfig() {
    EnvironmentConfig cfg;
    assert(cfg.id == "void");
    assert(cfg.name == "Void");
    assert(cfg.screenPosX == 0.0f);
    assert(cfg.screenPosY == 1.5f);
    assert(cfg.screenPosZ == -2.4f);
    assert(cfg.screenScaleX == 2.8f);
    assert(cfg.screenScaleY == 1.575f);
    assert(!cfg.screenLocked);
    assert(!cfg.particlesEnabled);
    std::cout << "[PASS] TestDefaultConfig\n";
}

static void TestParseConfigIni() {
    std::string sampleIni = R"(
# Configuração do ambiente Espaço Cósmico
id = space
name = Espaço Cósmico
screen_pos = 0.0, 1.8, -3.2
screen_scale = 3.8, 2.1375
screen_locked = true
model_file = space/model.glb
skybox_file = space/skybox.png
ambient_audio = space/ambient.ogg
ambient_volume = 0.25
particles_enabled = true
)";

    EnvironmentConfig cfg = EnvironmentConfig::Parse(sampleIni);
    assert(cfg.id == "space");
    assert(cfg.name == "Espaço Cósmico");
    assert(std::fabs(cfg.screenPosX - 0.0f) < 0.001f);
    assert(std::fabs(cfg.screenPosY - 1.8f) < 0.001f);
    assert(std::fabs(cfg.screenPosZ - -3.2f) < 0.001f);
    assert(std::fabs(cfg.screenScaleX - 3.8f) < 0.001f);
    assert(std::fabs(cfg.screenScaleY - 2.1375f) < 0.001f);
    assert(cfg.screenLocked == true);
    assert(cfg.modelFile == "space/model.glb");
    assert(cfg.skyboxFile == "space/skybox.png");
    assert(cfg.ambientAudio == "space/ambient.ogg");
    assert(std::fabs(cfg.ambientVolume - 0.25f) < 0.001f);
    assert(cfg.particlesEnabled == true);

    std::cout << "[PASS] TestParseConfigIni\n";
}

int main() {
    std::cout << "--- Executando testes unitarios de environment_config.h ---\n";
    TestDefaultConfig();
    TestParseConfigIni();
    std::cout << "--- Todos os testes de environment_config.h passaram com sucesso! ---\n";
    return 0;
}
