#pragma once
#include <string>
#include <sstream>
#include <vector>
#include <cstdlib>
#include <algorithm>
#include <cctype>

/**
 * Configuração de metadados e ancoragem de um ambiente virtual 3D.
 * Parseia arquivos config.ini em formato simples chave=valor.
 */
struct EnvironmentConfig {
    std::string id = "void";
    std::string name = "Void";
    float screenPosX = 0.0f;
    float screenPosY = 1.5f;
    float screenPosZ = -2.4f;
    float screenScaleX = 2.8f;
    float screenScaleY = 1.575f;
    bool screenLocked = false;
    std::string modelFile = "";
    std::string skyboxFile = "";
    std::string ambientAudio = "";
    float ambientVolume = 0.3f;
    bool particlesEnabled = false;

    static inline std::string Trim(const std::string& str) {
        size_t first = str.find_first_not_of(" \t\r\n");
        if (first == std::string::npos) return "";
        size_t last = str.find_last_not_of(" \t\r\n");
        return str.substr(first, (last - first + 1));
    }

    static inline EnvironmentConfig Parse(const std::string& iniContent) {
        EnvironmentConfig config;
        std::istringstream stream(iniContent);
        std::string line;

        while (std::getline(stream, line)) {
            line = Trim(line);
            if (line.empty() || line[0] == '#' || line[0] == ';') continue;

            size_t eqPos = line.find('=');
            if (eqPos == std::string::npos) continue;

            std::string key = Trim(line.substr(0, eqPos));
            std::string val = Trim(line.substr(eqPos + 1));

            if (key == "id") {
                config.id = val;
            } else if (key == "name") {
                config.name = val;
            } else if (key == "screen_pos") {
                std::istringstream valStream(val);
                std::string item;
                if (std::getline(valStream, item, ',')) config.screenPosX = std::strtof(item.c_str(), nullptr);
                if (std::getline(valStream, item, ',')) config.screenPosY = std::strtof(item.c_str(), nullptr);
                if (std::getline(valStream, item, ',')) config.screenPosZ = std::strtof(item.c_str(), nullptr);
            } else if (key == "screen_scale") {
                std::istringstream valStream(val);
                std::string item;
                if (std::getline(valStream, item, ',')) config.screenScaleX = std::strtof(item.c_str(), nullptr);
                if (std::getline(valStream, item, ',')) config.screenScaleY = std::strtof(item.c_str(), nullptr);
            } else if (key == "screen_locked") {
                config.screenLocked = (val == "true" || val == "1" || val == "yes");
            } else if (key == "model_file") {
                config.modelFile = val;
            } else if (key == "skybox_file") {
                config.skyboxFile = val;
            } else if (key == "ambient_audio") {
                config.ambientAudio = val;
            } else if (key == "ambient_volume") {
                config.ambientVolume = std::strtof(val.c_str(), nullptr);
            } else if (key == "particles_enabled") {
                config.particlesEnabled = (val == "true" || val == "1" || val == "yes");
            }
        }

        return config;
    }
};
