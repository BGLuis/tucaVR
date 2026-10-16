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
    assert(cfg.spawnPosX == 0.0f);
    assert(cfg.spawnPosY == 0.0f);
    assert(cfg.spawnPosZ == 0.0f);
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
spawn_pos = 0.5, 1.2, -0.8
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
    assert(std::fabs(cfg.spawnPosX - 0.5f) < 0.001f);
    assert(std::fabs(cfg.spawnPosY - 1.2f) < 0.001f);
    assert(std::fabs(cfg.spawnPosZ - -0.8f) < 0.001f);

    std::cout << "[PASS] TestParseConfigIni\n";
}

static void TestSpawnPosParsing() {
    // 1. Spacing and negative values
    std::string iniWithSpaces = "spawn_pos = -1.5 ,  -2.25 ,   3.75 \n";
    EnvironmentConfig cfg1 = EnvironmentConfig::Parse(iniWithSpaces);
    assert(std::fabs(cfg1.spawnPosX - -1.5f) < 0.001f);
    assert(std::fabs(cfg1.spawnPosY - -2.25f) < 0.001f);
    assert(std::fabs(cfg1.spawnPosZ - 3.75f) < 0.001f);

    // 2. Missing spawn_pos keeps 0.0 defaults
    std::string iniWithoutSpawn = "id = test\nscreen_pos = 0.0, 1.5, -2.4\n";
    EnvironmentConfig cfg2 = EnvironmentConfig::Parse(iniWithoutSpawn);
    assert(cfg2.spawnPosX == 0.0f);
    assert(cfg2.spawnPosY == 0.0f);
    assert(cfg2.spawnPosZ == 0.0f);

    // 3. Partial components
    std::string iniPartial = "spawn_pos = 1.0, 2.0\n";
    EnvironmentConfig cfg3 = EnvironmentConfig::Parse(iniPartial);
    assert(std::fabs(cfg3.spawnPosX - 1.0f) < 0.001f);
    assert(std::fabs(cfg3.spawnPosY - 2.0f) < 0.001f);
    assert(cfg3.spawnPosZ == 0.0f);

    std::cout << "[PASS] TestSpawnPosParsing\n";
}

int main() {
    std::cout << "--- Executando testes unitarios de environment_config.h ---\n";
    TestDefaultConfig();
    TestParseConfigIni();
    TestSpawnPosParsing();
    std::cout << "--- Todos os testes de environment_config.h passaram com sucesso! ---\n";
    return 0;
}
