// Estagios 1-3 do plano de migracao Vulkan (docs/VULKAN-MIGRATION-PLAN.md):
// Estagio 1 = sessao OpenXR+Vulkan com clear color, sem geometria.
// Estagio 2 = pipeline grafico minimo desenhando um quad estatico com cor
// solida (shaders GLSL -> SPIR-V embutido em build-time, ver
// native/CMakeLists.txt e native/shaders/vulkan/).
// Estagio 3 = textura de video: importa AHardwareBuffer do Rust/MediaCodec
// como VkImage via VK_ANDROID_external_memory_android_hardware_buffer,
// VkSamplerYcbcrConversion para formato YUV do MediaCodec, cache de
// VkImage equivalente ao m_eglImageCache do caminho GLES.
//
// Caminho paralelo ao GLES (vr_player_app.cpp), compilado apenas quando
// VRPLAYER_GRAPHICS_API=VULKAN (ver native/CMakeLists.txt). Nao deriva de
// OVRFW::XrApp porque o SampleXrFramework vendorizado em sdk/meta-openxr-sdk
// e GLES-only por construcao (XR_USE_GRAPHICS_API_OPENGL_ES hardcoded no seu
// proprio CMakeLists.txt) — ver secao 1-2 do plano para o levantamento
// completo. Este arquivo reimplementa do zero a fatia minima de
// sessao/swapchain/frame loop que o OVRFW normalmente esconde.

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_VULKAN

#include <jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <android_native_app_glue.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window_jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <math.h>

#include <array>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

#include "vk_math.h"
#include "quad.vert.h"
#include "quad.frag.h"
#include "video.vert.h"
#include "video.frag.h"
#include "ui.vert.h"
#include "ui.frag.h"
#include "stereo.vert.h"
#include "stereo.frag.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VRPlayerAppVK", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VRPlayerAppVK", __VA_ARGS__)

// Bridge Rust: fornece o AHardwareBuffer do frame de video atual e controle de 3D mode.
extern "C" {
    extern AHardwareBuffer* get_current_video_frame();
    extern void get_video_progress(float* current, float* total);
    extern uint32_t get_3d_mode();
    extern uint32_t get_swap_eyes();
    extern void start_video_playback(const char* path);
}

namespace {

constexpr int kEyeCount = 2;
// Limite de entradas no cache de VkImage por AHardwareBuffer.
constexpr size_t kVideoImageCacheLimit = 6;
// Raio da esfera 360 (20m, mesmo do caminho GLES: kSphereRadius em vr_player_app.cpp:1604)
constexpr float kSphereRadius = 20.0f;

// ScreenMode espelha exatamente o enum do caminho GLES (vr_player_app.cpp:370-381)
enum class ScreenMode : uint32_t {
    Flat2D      = 0,
    SBS         = 1,
    SBSHalf     = 2,
    OU          = 3,
    OUHalf      = 4,
    Sphere360   = 5,
    Sphere180   = 6,
    Sphere360SBS = 7,
    Sphere360OU = 8,
    Vr180SBS    = 9,
};

static bool IsSphereMode(ScreenMode mode) {
    switch (mode) {
        case ScreenMode::Sphere360:
        case ScreenMode::Sphere180:
        case ScreenMode::Sphere360SBS:
        case ScreenMode::Sphere360OU:
        case ScreenMode::Vr180SBS:
            return true;
        default:
            return false;
    }
}

static bool IsFlatStereoMode(ScreenMode mode) {
    switch (mode) {
        case ScreenMode::SBS:
        case ScreenMode::SBSHalf:
        case ScreenMode::OU:
        case ScreenMode::OUHalf:
            return true;
        default:
            return false;
    }
}

// Parâmetros de estereo para o push constant do stereo.vert/frag
struct StereoParams {
    int eyeIndex     = 0;
    int swapEyes     = 0;
    int stereoLayout = 0; // 0=mono, 1=SBS, 2=OU
    int polar180     = 0;
};

StereoParams GetStereoParams(ScreenMode mode, int eye) {
    StereoParams p;
    p.eyeIndex   = eye;
    p.swapEyes   = (int)(get_swap_eyes() != 0);
    p.polar180   = (mode == ScreenMode::Sphere180 || mode == ScreenMode::Vr180SBS) ? 1 : 0;
    switch (mode) {
        case ScreenMode::SBS:
        case ScreenMode::SBSHalf:
        case ScreenMode::Sphere360SBS:
        case ScreenMode::Vr180SBS:
            p.stereoLayout = 1; // SBS
            break;
        case ScreenMode::OU:
        case ScreenMode::OUHalf:
        case ScreenMode::Sphere360OU:
            p.stereoLayout = 2; // OU
            break;
        default:
            p.stereoLayout = 0;
            break;
    }
    return p;
}

void CheckXrResult(XrResult result, const char* what) {
    if (XR_FAILED(result)) {
        LOGE("OpenXR falhou em %s: %d", what, result);
        std::abort();
    }
}
#define OXR(func) CheckXrResult((func), #func)

void CheckVkResult(VkResult result, const char* what) {
    if (result != VK_SUCCESS) {
        LOGE("Vulkan falhou em %s: %d", what, result);
        std::abort();
    }
}
#define VKR(func) CheckVkResult((func), #func)

template <typename PFN>
PFN LoadXrFunction(XrInstance instance, const char* name) {
    PFN fn = nullptr;
    OXR(xrGetInstanceProcAddr(instance, name, reinterpret_cast<PFN_xrVoidFunction*>(&fn)));
    return fn;
}

// xrGetVulkanInstanceExtensionsKHR/DeviceExtensionsKHR devolvem uma unica
// string com nomes separados por espaco (convencao do KHR_vulkan_enable).
std::vector<std::string> SplitBySpace(const std::string& s) {
    std::vector<std::string> result;
    size_t start = 0;
    while (start < s.size()) {
        size_t end = s.find(' ', start);
        if (end == std::string::npos) end = s.size();
        if (end > start) result.push_back(s.substr(start, end - start));
        start = end + 1;
    }
    return result;
}

struct EyeSwapchain {
    XrSwapchain handle = XR_NULL_HANDLE;
    int32_t width = 0;
    int32_t height = 0;
    std::vector<XrSwapchainImageVulkanKHR> images;
    std::vector<VkImageView> imageViews;
    std::vector<VkFramebuffer> framebuffers;
};

// Representa um frame de video importado como VkImage a partir de um
// AHardwareBuffer. O ciclo de vida e: criar uma vez por buffer unico,
// manter em cache (VideoImageCache), destruir ao limpar ou ao remover
// a entrada mais antiga do cache.
struct VideoFrame {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView imageView = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE; // alocado do pool
};

struct AppState {
    android_app* app = nullptr;

    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSpace localSpace = XR_NULL_HANDLE;
    std::array<EyeSwapchain, kEyeCount> eyes;

    VkInstance vkInstance = VK_NULL_HANDLE;
    VkPhysicalDevice vkPhysicalDevice = VK_NULL_HANDLE;
    VkDevice vkDevice = VK_NULL_HANDLE;
    uint32_t vkQueueFamilyIndex = UINT32_MAX;
    VkQueue vkQueue = VK_NULL_HANDLE;
    VkCommandPool vkCommandPool = VK_NULL_HANDLE;
    VkCommandBuffer vkCommandBuffer = VK_NULL_HANDLE;
    VkFormat swapchainFormat = VK_FORMAT_UNDEFINED;

    // Estagio 2 — pipeline do quad estatico (fallback sem frame de video).
    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkBuffer quadVertexBuffer = VK_NULL_HANDLE;
    VkDeviceMemory quadVertexMemory = VK_NULL_HANDLE;

    // Estagio 3 — pipeline de video com textura YCbCr.
    VkSamplerYcbcrConversion ycbcrConversion = VK_NULL_HANDLE;
    VkSampler videoSampler = VK_NULL_HANDLE;
    VkDescriptorSetLayout videoDescriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool videoDescriptorPool = VK_NULL_HANDLE;
    VkPipelineLayout videoPipelineLayout = VK_NULL_HANDLE;
    VkPipeline videoPipeline = VK_NULL_HANDLE;
    VkBuffer videoVertexBuffer = VK_NULL_HANDLE;
    VkDeviceMemory videoVertexMemory = VK_NULL_HANDLE;

    std::unordered_map<AHardwareBuffer*, VideoFrame> videoImageCache;
    AHardwareBuffer* lastVideoBuffer = nullptr;
    VideoFrame* activeVideoFrame = nullptr;

    // Estagio 4 — pipeline UI/controles (RGBA8888 normal, com alpha blending).
    // Usa AImageReader + ANativeWindow_toSurface igual ao caminho GLES.
    VkSampler uiSampler = VK_NULL_HANDLE;
    VkDescriptorSetLayout uiDescriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool uiDescriptorPool = VK_NULL_HANDLE;
    VkPipelineLayout uiPipelineLayout = VK_NULL_HANDLE;
    VkPipeline uiPipeline = VK_NULL_HANDLE;

    // Painel de file browser (1024x1024)
    AImageReader* uiImageReader = nullptr;
    VkImage uiImage = VK_NULL_HANDLE;
    VkDeviceMemory uiImageMemory = VK_NULL_HANDLE;
    VkImageView uiImageView = VK_NULL_HANDLE;
    VkDescriptorSet uiDescriptorSet = VK_NULL_HANDLE;
    float uiAlpha = 1.0f;
    bool uiHasFrame = false;

    // Painel de controles (1024x384)
    AImageReader* controlsImageReader = nullptr;
    VkImage controlsImage = VK_NULL_HANDLE;
    VkDeviceMemory controlsImageMemory = VK_NULL_HANDLE;
    VkImageView controlsImageView = VK_NULL_HANDLE;
    VkDescriptorSet controlsDescriptorSet = VK_NULL_HANDLE;
    float controlsAlpha = 1.0f;
    bool controlsHasFrame = false;

    // OpenXR Actions
    XrActionSet actionSet = XR_NULL_HANDLE;
    XrAction aimAction = XR_NULL_HANDLE;
    XrAction triggerAction = XR_NULL_HANDLE;
    std::array<XrSpace, 2> aimSpaces = {XR_NULL_HANDLE, XR_NULL_HANDLE};
    
    // UI Interaction state
    bool isTouchDown = false;
    int activePanel = 0; // 0=none, 1=UI, 2=controls
    XrVector3f lastRayOrigin = {0,0,0};
    XrVector3f lastRayDir = {0,0,-1};
    bool isTriggerPressed = false;
    float uiIdleTime = 0.0f;
    float controlsIdleTime = 0.0f;
    float lastUvX = 0.0f;
    float lastUvY = 0.0f;
    float lastHitDist = -1.0f;

    // Estagio 5 — pipeline estereo/esfera (SBS/OU/360/180 com CAS sharpening).
    // Reusa o videoDescriptorSetLayout (mesmo sampler YCbCr) mas pipeline separado.
    VkPipelineLayout stereoPipelineLayout = VK_NULL_HANDLE;
    VkPipeline stereoPipeline = VK_NULL_HANDLE;
    // Geometria da esfera (BuildGlobe equivalente)
    VkBuffer sphereVertexBuffer = VK_NULL_HANDLE;
    VkDeviceMemory sphereVertexMemory = VK_NULL_HANDLE;
    VkBuffer sphereIndexBuffer = VK_NULL_HANDLE;
    VkDeviceMemory sphereIndexMemory = VK_NULL_HANDLE;
    uint32_t sphereIndexCount = 0;

    // Estagio 5 — BeamRenderer: linha simples de laser do controller.
    VkPipelineLayout beamPipelineLayout = VK_NULL_HANDLE;
    VkPipeline beamPipeline = VK_NULL_HANDLE;
    VkBuffer beamVertexBuffer = VK_NULL_HANDLE;
    VkDeviceMemory beamVertexMemory = VK_NULL_HANDLE;
    bool beamVisible = false;
    // Endpoint do beam: origem no controller, destino no alvo/quad
    float beamStart[3] = {0, 0, 0};
    float beamEnd[3]   = {0, 0, -2};

    // Estado de ScreenMode (lido do bridge Rust a cada frame)
    ScreenMode screenMode = ScreenMode::Flat2D;

    bool resumed = false;
    bool sessionRunning = false;
    bool requestExit = false;
};

#include "vr_player_input_vulkan.h"

struct QuadPushConstants {
    Mat4 mvp;
    float color[4];
};

struct VideoPushConstants {
    Mat4 mvp;
};

// Estagio 4: push constant para UI (MVP + alpha)
struct UiPushConstants {
    Mat4  mvp;
    float alpha;
    float _pad[3];
};

// Estagio 5: push constant para estereo/esfera (MVP + parametros de olho)
struct StereoPushConstants {
    Mat4 mvp;
    int  eyeIndex;
    int  swapEyes;
    int  stereoLayout;
    int  polar180;
};

struct BeamPushConstants {
    Mat4  mvp;
    float color[4];
};

// XR_KHR_android_create_instance nao e usada aqui de proposito: o caminho
// GLES existente (vr_player_app.cpp / OVRFW::XrApp::GetExtensions) tambem
// nao a habilita e funciona normalmente no runtime da Meta — o loader ja
// recebe VM/Activity via xrInitializeLoaderKHR abaixo. Mantido consistente
// com o que ja esta validado neste repositorio (ver CLAUDE.md, Rule 11).
void InitializeOpenXrLoader(AppState& state) {
    auto xrInitializeLoaderKHR =
        LoadXrFunction<PFN_xrInitializeLoaderKHR>(XR_NULL_HANDLE, "xrInitializeLoaderKHR");
    if (xrInitializeLoaderKHR == nullptr) {
        LOGE("xrInitializeLoaderKHR indisponivel — runtime OpenXR ausente?");
        std::abort();
    }

    XrLoaderInitInfoAndroidKHR loaderInitInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    loaderInitInfo.applicationVM = state.app->activity->vm;
    loaderInitInfo.applicationContext = state.app->activity->clazz;
    OXR(xrInitializeLoaderKHR(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR*>(&loaderInitInfo)));
}

void CreateXrInstance(AppState& state) {
    const char* extensions[] = {XR_KHR_VULKAN_ENABLE_EXTENSION_NAME};

    XrApplicationInfo appInfo{};
    std::strncpy(appInfo.applicationName, "VRPlayerVulkanStage1", XR_MAX_APPLICATION_NAME_SIZE - 1);
    appInfo.applicationVersion = 1;
    std::strncpy(appInfo.engineName, "VRPlayer", XR_MAX_ENGINE_NAME_SIZE - 1);
    appInfo.engineVersion = 1;
    appInfo.apiVersion = XR_CURRENT_API_VERSION;

    XrInstanceCreateInfo createInfo{XR_TYPE_INSTANCE_CREATE_INFO};
    createInfo.applicationInfo = appInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(std::size(extensions));
    createInfo.enabledExtensionNames = extensions;

    OXR(xrCreateInstance(&createInfo, &state.instance));
}

// Cria VkInstance/VkDevice seguindo exatamente o que o runtime OpenXR exige
// via xrGetVulkan*KHR — nao um device "generico" escolhido pelo app, porque
// o compositor da Meta so aceita apresentar imagens de um VkPhysicalDevice
// especifico (o mesmo que ele usa internamente).
//
// Estagio 3: adiciona VK_KHR_external_memory_capabilities na instancia e
// VK_ANDROID_external_memory_android_hardware_buffer + VK_KHR_sampler_ycbcr_conversion
// no device, alem dos que o runtime exige via xrGetVulkanDeviceExtensionsKHR.
void CreateVulkanInstanceAndDevice(AppState& state) {
    auto pfnGetGraphicsRequirements = LoadXrFunction<PFN_xrGetVulkanGraphicsRequirementsKHR>(
        state.instance, "xrGetVulkanGraphicsRequirementsKHR");
    auto pfnGetInstanceExtensions = LoadXrFunction<PFN_xrGetVulkanInstanceExtensionsKHR>(
        state.instance, "xrGetVulkanInstanceExtensionsKHR");
    auto pfnGetDeviceExtensions = LoadXrFunction<PFN_xrGetVulkanDeviceExtensionsKHR>(
        state.instance, "xrGetVulkanDeviceExtensionsKHR");
    auto pfnGetGraphicsDevice = LoadXrFunction<PFN_xrGetVulkanGraphicsDeviceKHR>(
        state.instance, "xrGetVulkanGraphicsDeviceKHR");

    // Exigencia do spec: precisa ser chamada antes de criar a VkInstance,
    // mesmo que o runtime nao valide os valores no caminho legado
    // (XR_KHR_vulkan_enable, em vez de enable2).
    XrGraphicsRequirementsVulkanKHR graphicsRequirements{XR_TYPE_GRAPHICS_REQUIREMENTS_VULKAN_KHR};
    OXR(pfnGetGraphicsRequirements(state.instance, state.systemId, &graphicsRequirements));
    LOGI(
        "Vulkan exigido pelo runtime: min=%u.%u max=%u.%u",
        XR_VERSION_MAJOR(graphicsRequirements.minApiVersionSupported),
        XR_VERSION_MINOR(graphicsRequirements.minApiVersionSupported),
        XR_VERSION_MAJOR(graphicsRequirements.maxApiVersionSupported),
        XR_VERSION_MINOR(graphicsRequirements.maxApiVersionSupported));

    uint32_t instanceExtSize = 0;
    OXR(pfnGetInstanceExtensions(state.instance, state.systemId, 0, &instanceExtSize, nullptr));
    std::string instanceExtNames(instanceExtSize, '\0');
    OXR(pfnGetInstanceExtensions(
        state.instance, state.systemId, instanceExtSize, &instanceExtSize, instanceExtNames.data()));
    if (!instanceExtNames.empty() && instanceExtNames.back() == '\0') instanceExtNames.pop_back();

    std::vector<std::string> instanceExtStrings = SplitBySpace(instanceExtNames);
    // Estagio 3: VK_KHR_external_memory_capabilities e obrigatoria para
    // consultar os requisitos de memoria externa (AHardwareBuffer).
    auto addIfMissing = [](std::vector<std::string>& vec, const char* name) {
        for (const auto& s : vec) if (s == name) return;
        vec.emplace_back(name);
    };
    addIfMissing(instanceExtStrings, VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME);
    addIfMissing(instanceExtStrings, VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);

    std::vector<const char*> instanceExtPtrs;
    for (const auto& s : instanceExtStrings) instanceExtPtrs.push_back(s.c_str());

    VkApplicationInfo vkAppInfo{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    vkAppInfo.pApplicationName = "VRPlayerVulkanStage3";
    vkAppInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    vkAppInfo.pEngineName = "VRPlayer";
    vkAppInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    vkAppInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo vkInstanceCreateInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    vkInstanceCreateInfo.pApplicationInfo = &vkAppInfo;
    vkInstanceCreateInfo.enabledExtensionCount = static_cast<uint32_t>(instanceExtPtrs.size());
    vkInstanceCreateInfo.ppEnabledExtensionNames = instanceExtPtrs.data();
    VKR(vkCreateInstance(&vkInstanceCreateInfo, nullptr, &state.vkInstance));

    OXR(pfnGetGraphicsDevice(state.instance, state.systemId, state.vkInstance, &state.vkPhysicalDevice));

    uint32_t deviceExtSize = 0;
    OXR(pfnGetDeviceExtensions(state.instance, state.systemId, 0, &deviceExtSize, nullptr));
    std::string deviceExtNames(deviceExtSize, '\0');
    OXR(pfnGetDeviceExtensions(
        state.instance, state.systemId, deviceExtSize, &deviceExtSize, deviceExtNames.data()));
    if (!deviceExtNames.empty() && deviceExtNames.back() == '\0') deviceExtNames.pop_back();

    std::vector<std::string> deviceExtStrings = SplitBySpace(deviceExtNames);
    // Estagio 3: extensoes necessarias para importar AHardwareBuffer e
    // realizar conversao YCbCr. Adicionadas alem das que o runtime exige.
    addIfMissing(deviceExtStrings, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
    addIfMissing(deviceExtStrings, VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME);
    addIfMissing(deviceExtStrings, VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME);
    addIfMissing(deviceExtStrings, VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME);
    addIfMissing(deviceExtStrings, VK_KHR_BIND_MEMORY_2_EXTENSION_NAME);
    addIfMissing(deviceExtStrings, VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME);

    std::vector<const char*> deviceExtPtrs;
    for (const auto& s : deviceExtStrings) deviceExtPtrs.push_back(s.c_str());

    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(state.vkPhysicalDevice, &queueFamilyCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(
        state.vkPhysicalDevice, &queueFamilyCount, queueFamilies.data());
    for (uint32_t i = 0; i < queueFamilyCount; i++) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
            state.vkQueueFamilyIndex = i;
            break;
        }
    }
    if (state.vkQueueFamilyIndex == UINT32_MAX) {
        LOGE("Nenhuma queue family com suporte a grafico encontrada");
        std::abort();
    }

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueCreateInfo.queueFamilyIndex = state.vkQueueFamilyIndex;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &queuePriority;

    // Estagio 3: habilitar samplerYcbcrConversion via feature struct.
    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeatures{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES};
    ycbcrFeatures.samplerYcbcrConversion = VK_TRUE;

    VkDeviceCreateInfo deviceCreateInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceCreateInfo.pNext = &ycbcrFeatures;
    deviceCreateInfo.queueCreateInfoCount = 1;
    deviceCreateInfo.pQueueCreateInfos = &queueCreateInfo;
    deviceCreateInfo.enabledExtensionCount = static_cast<uint32_t>(deviceExtPtrs.size());
    deviceCreateInfo.ppEnabledExtensionNames = deviceExtPtrs.data();
    VKR(vkCreateDevice(state.vkPhysicalDevice, &deviceCreateInfo, nullptr, &state.vkDevice));

    vkGetDeviceQueue(state.vkDevice, state.vkQueueFamilyIndex, 0, &state.vkQueue);
}

// Mesma preferencia STAGE-com-fallback-LOCAL que o caminho GLES usa
// (XrApp.cpp:1055-1091) — mantido para nao mudar o comportamento de
// tracking que ja foi validado no headset.
void CreateReferenceSpace(AppState& state) {
    uint32_t spaceCount = 0;
    OXR(xrEnumerateReferenceSpaces(state.session, 0, &spaceCount, nullptr));
    std::vector<XrReferenceSpaceType> spaces(spaceCount);
    OXR(xrEnumerateReferenceSpaces(state.session, spaceCount, &spaceCount, spaces.data()));

    XrReferenceSpaceType chosen = XR_REFERENCE_SPACE_TYPE_LOCAL;
    for (auto type : spaces) {
        if (type == XR_REFERENCE_SPACE_TYPE_STAGE) {
            chosen = XR_REFERENCE_SPACE_TYPE_STAGE;
            break;
        }
    }

    XrReferenceSpaceCreateInfo spaceCreateInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    spaceCreateInfo.referenceSpaceType = chosen;
    spaceCreateInfo.poseInReferenceSpace.orientation.w = 1.0f;
    OXR(xrCreateReferenceSpace(state.session, &spaceCreateInfo, &state.localSpace));
}

// Um swapchain Vulkan por olho (faceCount=1, arraySize=1), espelhando a
// mesma topologia que Framebuffer.cpp usa no caminho GLES — nao um unico
// swapchain com array de 2 camadas.
void CreateSwapchains(AppState& state) {
    uint32_t viewCount = 0;
    OXR(xrEnumerateViewConfigurationViews(
        state.instance, state.systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &viewCount, nullptr));
    if (viewCount != kEyeCount) {
        LOGE("Configuracao de view inesperada: %u views (esperado %d)", viewCount, kEyeCount);
        std::abort();
    }
    std::array<XrViewConfigurationView, kEyeCount> views{};
    for (auto& v : views) v.type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
    OXR(xrEnumerateViewConfigurationViews(
        state.instance, state.systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, viewCount, &viewCount,
        views.data()));

    uint32_t formatCount = 0;
    OXR(xrEnumerateSwapchainFormats(state.session, 0, &formatCount, nullptr));
    std::vector<int64_t> formats(formatCount);
    OXR(xrEnumerateSwapchainFormats(state.session, formatCount, &formatCount, formats.data()));

    int64_t chosenFormat = 0;
    for (int64_t format : formats) {
        if (format == VK_FORMAT_R8G8B8A8_UNORM || format == VK_FORMAT_B8G8R8A8_UNORM) {
            chosenFormat = format;
            break;
        }
    }
    if (chosenFormat == 0) chosenFormat = formats.front();
    LOGI("Formato de swapchain escolhido: %lld", static_cast<long long>(chosenFormat));
    state.swapchainFormat = static_cast<VkFormat>(chosenFormat);

    for (int eye = 0; eye < kEyeCount; eye++) {
        EyeSwapchain& eyeChain = state.eyes[eye];
        eyeChain.width = static_cast<int32_t>(views[eye].recommendedImageRectWidth);
        eyeChain.height = static_cast<int32_t>(views[eye].recommendedImageRectHeight);

        XrSwapchainCreateInfo createInfo{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        createInfo.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        createInfo.format = chosenFormat;
        createInfo.sampleCount = 1;
        createInfo.width = static_cast<uint32_t>(eyeChain.width);
        createInfo.height = static_cast<uint32_t>(eyeChain.height);
        createInfo.faceCount = 1;
        createInfo.arraySize = 1;
        createInfo.mipCount = 1;
        OXR(xrCreateSwapchain(state.session, &createInfo, &eyeChain.handle));

        uint32_t imageCount = 0;
        OXR(xrEnumerateSwapchainImages(eyeChain.handle, 0, &imageCount, nullptr));
        eyeChain.images.assign(imageCount, XrSwapchainImageVulkanKHR{XR_TYPE_SWAPCHAIN_IMAGE_VULKAN_KHR});
        OXR(xrEnumerateSwapchainImages(
            eyeChain.handle, imageCount, &imageCount,
            reinterpret_cast<XrSwapchainImageBaseHeader*>(eyeChain.images.data())));
    }
}

// Um subpass, um color attachment (o proprio swapchain image), sem depth —
// o Estagio 2 desenha um unico quad que nunca se auto-oculta, entao nao ha
// motivo para pagar o custo de um depth buffer ainda.
void CreateRenderPass(AppState& state) {
    VkAttachmentDescription colorAttachment{};
    colorAttachment.format = state.swapchainFormat;
    colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
    colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    // UNDEFINED e seguro porque loadOp=CLEAR descarta o conteudo anterior de
    // qualquer forma — mesmo raciocinio do Estagio 1.
    colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    colorAttachment.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkAttachmentReference colorRef{};
    colorRef.attachment = 0;
    colorRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorRef;

    VkRenderPassCreateInfo renderPassInfo{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    renderPassInfo.attachmentCount = 1;
    renderPassInfo.pAttachments = &colorAttachment;
    renderPassInfo.subpassCount = 1;
    renderPassInfo.pSubpasses = &subpass;
    VKR(vkCreateRenderPass(state.vkDevice, &renderPassInfo, nullptr, &state.renderPass));
}

// Uma VkImageView + VkFramebuffer por imagem de cada swapchain de olho —
// precisa do render pass (para o framebuffer) e das imagens ja enumeradas
// (CreateSwapchains), entao roda depois dos dois.
void CreateFramebuffers(AppState& state) {
    for (auto& eyeChain : state.eyes) {
        eyeChain.imageViews.resize(eyeChain.images.size());
        eyeChain.framebuffers.resize(eyeChain.images.size());

        for (size_t i = 0; i < eyeChain.images.size(); i++) {
            VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            viewInfo.image = eyeChain.images[i].image;
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = state.swapchainFormat;
            viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            viewInfo.subresourceRange.levelCount = 1;
            viewInfo.subresourceRange.layerCount = 1;
            VKR(vkCreateImageView(state.vkDevice, &viewInfo, nullptr, &eyeChain.imageViews[i]));

            VkFramebufferCreateInfo fbInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            fbInfo.renderPass = state.renderPass;
            fbInfo.attachmentCount = 1;
            fbInfo.pAttachments = &eyeChain.imageViews[i];
            fbInfo.width = static_cast<uint32_t>(eyeChain.width);
            fbInfo.height = static_cast<uint32_t>(eyeChain.height);
            fbInfo.layers = 1;
            VKR(vkCreateFramebuffer(state.vkDevice, &fbInfo, nullptr, &eyeChain.framebuffers[i]));
        }
    }
}

uint32_t FindMemoryType(AppState& state, uint32_t typeBits, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(state.vkPhysicalDevice, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        const bool typeSupported = (typeBits & (1u << i)) != 0;
        const bool hasProperties =
            (memProps.memoryTypes[i].propertyFlags & properties) == properties;
        if (typeSupported && hasProperties) return i;
    }
    LOGE("Nenhum tipo de memoria Vulkan compativel encontrado (typeBits=0x%x)", typeBits);
    std::abort();
}

// Quad unitario em espaco local (-0.5..0.5 em X/Y, Z=0), escalado/posicionado
// via a matriz MVP no push constant — ver RenderFrame. Memoria host-visible
// simples (sem staging buffer): 4 vertices e um buffer estatico, nao vale a
// complexidade de um upload via memoria device-local ainda.
void CreateQuadVertexBuffer(AppState& state) {
    const float vertices[] = {
        -0.5f, -0.5f, 0.0f,
        0.5f,  -0.5f, 0.0f,
        -0.5f, 0.5f,  0.0f,
        0.5f,  0.5f,  0.0f,
    };

    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = sizeof(vertices);
    bufferInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VKR(vkCreateBuffer(state.vkDevice, &bufferInfo, nullptr, &state.quadVertexBuffer));

    VkMemoryRequirements memReq{};
    vkGetBufferMemoryRequirements(state.vkDevice, state.quadVertexBuffer, &memReq);

    VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = FindMemoryType(
        state, memReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VKR(vkAllocateMemory(state.vkDevice, &allocInfo, nullptr, &state.quadVertexMemory));
    VKR(vkBindBufferMemory(state.vkDevice, state.quadVertexBuffer, state.quadVertexMemory, 0));

    void* mapped = nullptr;
    VKR(vkMapMemory(state.vkDevice, state.quadVertexMemory, 0, sizeof(vertices), 0, &mapped));
    std::memcpy(mapped, vertices, sizeof(vertices));
    vkUnmapMemory(state.vkDevice, state.quadVertexMemory);
}

// Estagio 3: vertex buffer do quad de video com posicao (XYZ) e UV (ST).
// Os UVs sao 0..1 com origem no canto superior esquerdo — mesma convencao
// do caminho GLES (GL_TEXTURE_EXTERNAL_OES usa coordenadas normais).
void CreateVideoVertexBuffer(AppState& state) {
    // Interleaved: XYZ + UV, 5 floats por vertice, triangle strip.
    const float vertices[] = {
        -0.5f, -0.5f, 0.0f,  0.0f, 1.0f,  // bottom-left
         0.5f, -0.5f, 0.0f,  1.0f, 1.0f,  // bottom-right
        -0.5f,  0.5f, 0.0f,  0.0f, 0.0f,  // top-left
         0.5f,  0.5f, 0.0f,  1.0f, 0.0f,  // top-right
    };

    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = sizeof(vertices);
    bufferInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VKR(vkCreateBuffer(state.vkDevice, &bufferInfo, nullptr, &state.videoVertexBuffer));

    VkMemoryRequirements memReq{};
    vkGetBufferMemoryRequirements(state.vkDevice, state.videoVertexBuffer, &memReq);

    VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = FindMemoryType(
        state, memReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VKR(vkAllocateMemory(state.vkDevice, &allocInfo, nullptr, &state.videoVertexMemory));
    VKR(vkBindBufferMemory(state.vkDevice, state.videoVertexBuffer, state.videoVertexMemory, 0));

    void* mapped = nullptr;
    VKR(vkMapMemory(state.vkDevice, state.videoVertexMemory, 0, sizeof(vertices), 0, &mapped));
    std::memcpy(mapped, vertices, sizeof(vertices));
    vkUnmapMemory(state.vkDevice, state.videoVertexMemory);
}

VkShaderModule CreateShaderModule(AppState& state, const unsigned char* spirv, size_t size) {
    VkShaderModuleCreateInfo createInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    createInfo.codeSize = size;
    createInfo.pCode = reinterpret_cast<const uint32_t*>(spirv);
    VkShaderModule module = VK_NULL_HANDLE;
    VKR(vkCreateShaderModule(state.vkDevice, &createInfo, nullptr, &module));
    return module;
}

// Pipeline minimo: sem descriptor sets (nao ha textura ate o Estagio 3), MVP
// + cor via push constant, viewport/scissor dinamicos (o tamanho e o mesmo
// todo frame, mas dynamic state e o padrao idiomatico e evita recriar o
// pipeline se isso mudar).
void CreateGraphicsPipeline(AppState& state) {
    VkShaderModule vertModule = CreateShaderModule(state, kQuadVertSpirv, kQuadVertSpirv_size);
    VkShaderModule fragModule = CreateShaderModule(state, kQuadFragSpirv, kQuadFragSpirv_size);

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertModule;
    stages[0].pName = "main";
    stages[1] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragModule;
    stages[1].pName = "main";

    VkVertexInputBindingDescription binding{};
    binding.binding = 0;
    binding.stride = sizeof(float) * 3;
    binding.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

    VkVertexInputAttributeDescription attribute{};
    attribute.location = 0;
    attribute.binding = 0;
    attribute.format = VK_FORMAT_R32G32B32_SFLOAT;
    attribute.offset = 0;

    VkPipelineVertexInputStateCreateInfo vertexInput{
        VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    vertexInput.vertexBindingDescriptionCount = 1;
    vertexInput.pVertexBindingDescriptions = &binding;
    vertexInput.vertexAttributeDescriptionCount = 1;
    vertexInput.pVertexAttributeDescriptions = &attribute;

    VkPipelineInputAssemblyStateCreateInfo inputAssembly{
        VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;

    VkPipelineViewportStateCreateInfo viewportState{
        VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewportState.viewportCount = 1;
    viewportState.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo rasterizer{
        VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
    rasterizer.cullMode = VK_CULL_MODE_NONE;
    rasterizer.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rasterizer.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo multisample{
        VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState blendAttachment{};
    blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                      VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    blendAttachment.blendEnable = VK_FALSE;

    VkPipelineColorBlendStateCreateInfo colorBlend{
        VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    colorBlend.attachmentCount = 1;
    colorBlend.pAttachments = &blendAttachment;

    VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamicState{
        VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamicState.dynamicStateCount = static_cast<uint32_t>(std::size(dynamicStates));
    dynamicState.pDynamicStates = dynamicStates;

    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
    pushConstantRange.offset = 0;
    pushConstantRange.size = sizeof(QuadPushConstants);

    VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.pushConstantRangeCount = 1;
    layoutInfo.pPushConstantRanges = &pushConstantRange;
    VKR(vkCreatePipelineLayout(state.vkDevice, &layoutInfo, nullptr, &state.pipelineLayout));

    VkGraphicsPipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    pipelineInfo.stageCount = 2;
    pipelineInfo.pStages = stages;
    pipelineInfo.pVertexInputState = &vertexInput;
    pipelineInfo.pInputAssemblyState = &inputAssembly;
    pipelineInfo.pViewportState = &viewportState;
    pipelineInfo.pRasterizationState = &rasterizer;
    pipelineInfo.pMultisampleState = &multisample;
    pipelineInfo.pColorBlendState = &colorBlend;
    pipelineInfo.pDynamicState = &dynamicState;
    pipelineInfo.layout = state.pipelineLayout;
    pipelineInfo.renderPass = state.renderPass;
    pipelineInfo.subpass = 0;
    VKR(vkCreateGraphicsPipelines(
        state.vkDevice, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &state.pipeline));

    vkDestroyShaderModule(state.vkDevice, vertModule, nullptr);
    vkDestroyShaderModule(state.vkDevice, fragModule, nullptr);
}

// Estagio 3: cria VkSamplerYcbcrConversion + sampler imutavel + descriptor
// set layout + descriptor pool + pipeline de video com textura YCbCr.
//
// O formato VK_FORMAT_UNDEFINED aqui serve como marcador para "usar o
// formato nativo reportado pelo AHardwareBuffer via
// vkGetAndroidHardwareBufferPropertiesANDROID". A conversao YCbCr e criada
// uma vez (format=UNDEFINED, que o driver do Quest interpreta como YCbCr
// nativo do AHardwareBuffer), e o sampler embutido nela e passado como
// sampler imutavel no descriptor set layout — requisito do spec para
// VK_ANDROID_external_memory_android_hardware_buffer com YCbCr.
//
// Risco documentado (VULKAN-MIGRATION-PLAN.md, Estagio 3): se o driver do
// Quest 3 nao suportar o formato exato que o MediaCodec produz via
// VkSamplerYcbcrConversion, esta funcao pode falhar ou produzir artefatos
// de cor. A validacao em hardware e o ponto critico deste estagio.
void CreateYcbcrAndVideoPipeline(AppState& state) {
    // --- VkSamplerYcbcrConversion ---
    // VK_FORMAT_UNDEFINED instrui o driver a usar o formato nativo do
    // AHardwareBuffer (necessario para buffers externos Android).
    VkExternalFormatANDROID externalFormat{VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID};
    externalFormat.externalFormat = 0; // sera preenchido em GetOrImportVideoFrame

    VkSamplerYcbcrConversionCreateInfo ycbcrInfo{
        VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO};
    ycbcrInfo.pNext = &externalFormat;
    ycbcrInfo.format = VK_FORMAT_UNDEFINED; // formato externo via AHardwareBuffer
    ycbcrInfo.ycbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601;
    ycbcrInfo.ycbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
    ycbcrInfo.components = {
        VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
        VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};
    ycbcrInfo.xChromaOffset = VK_CHROMA_LOCATION_MIDPOINT;
    ycbcrInfo.yChromaOffset = VK_CHROMA_LOCATION_MIDPOINT;
    ycbcrInfo.chromaFilter = VK_FILTER_LINEAR;
    ycbcrInfo.forceExplicitReconstruction = VK_FALSE;
    // vkCreateSamplerYcbcrConversion foi promovida para core no Vulkan 1.1,
    // mas a libvulkan.so do NDK Android 26 pode nao a exportar diretamente.
    // Carregar via vkGetDeviceProcAddr e o caminho seguro (mesmo padrao usado
    // para vkGetAndroidHardwareBufferPropertiesANDROID abaixo).
    auto pfnCreateYcbcrConversion =
        reinterpret_cast<PFN_vkCreateSamplerYcbcrConversion>(
            vkGetDeviceProcAddr(state.vkDevice, "vkCreateSamplerYcbcrConversion"));
    if (!pfnCreateYcbcrConversion) {
        LOGE("vkCreateSamplerYcbcrConversion nao disponivel no device");
        std::abort();
    }
    VKR(pfnCreateYcbcrConversion(state.vkDevice, &ycbcrInfo, nullptr, &state.ycbcrConversion));

    // --- Sampler imutavel com YcbcrConversion embutida ---
    VkSamplerYcbcrConversionInfo conversionInfo{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
    conversionInfo.conversion = state.ycbcrConversion;

    VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    samplerInfo.pNext = &conversionInfo;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    VKR(vkCreateSampler(state.vkDevice, &samplerInfo, nullptr, &state.videoSampler));

    // --- Descriptor Set Layout (sampler imutavel obrigatorio para YCbCr) ---
    VkDescriptorSetLayoutBinding binding{};
    binding.binding = 0;
    binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    binding.descriptorCount = 1;
    binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    binding.pImmutableSamplers = &state.videoSampler; // obrigatorio para YCbCr

    VkDescriptorSetLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    layoutInfo.bindingCount = 1;
    layoutInfo.pBindings = &binding;
    VKR(vkCreateDescriptorSetLayout(
        state.vkDevice, &layoutInfo, nullptr, &state.videoDescriptorSetLayout));

    // --- Descriptor Pool (kVideoImageCacheLimit sets para o cache de frames) ---
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSize.descriptorCount = static_cast<uint32_t>(kVideoImageCacheLimit);

    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    poolInfo.maxSets = static_cast<uint32_t>(kVideoImageCacheLimit);
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    VKR(vkCreateDescriptorPool(state.vkDevice, &poolInfo, nullptr, &state.videoDescriptorPool));

    // --- Pipeline Layout (apenas push constant MVP, sem color) ---
    VkPushConstantRange videoPushRange{};
    videoPushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
    videoPushRange.offset = 0;
    videoPushRange.size = sizeof(VideoPushConstants);

    VkPipelineLayoutCreateInfo videoLayoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    videoLayoutInfo.setLayoutCount = 1;
    videoLayoutInfo.pSetLayouts = &state.videoDescriptorSetLayout;
    videoLayoutInfo.pushConstantRangeCount = 1;
    videoLayoutInfo.pPushConstantRanges = &videoPushRange;
    VKR(vkCreatePipelineLayout(
        state.vkDevice, &videoLayoutInfo, nullptr, &state.videoPipelineLayout));

    // --- Pipeline Grafico de Video ---
    VkShaderModule vertModule =
        CreateShaderModule(state, kVideoVertSpirv, kVideoVertSpirv_size);
    VkShaderModule fragModule =
        CreateShaderModule(state, kVideoFragSpirv, kVideoFragSpirv_size);

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertModule;
    stages[0].pName = "main";
    stages[1] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragModule;
    stages[1].pName = "main";

    // Vertex buffer interleaved: XYZ (location=0) + UV (location=1)
    VkVertexInputBindingDescription vertBinding{};
    vertBinding.binding = 0;
    vertBinding.stride = sizeof(float) * 5;
    vertBinding.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

    VkVertexInputAttributeDescription vertAttribs[2]{};
    vertAttribs[0].location = 0;
    vertAttribs[0].binding = 0;
    vertAttribs[0].format = VK_FORMAT_R32G32B32_SFLOAT;
    vertAttribs[0].offset = 0;
    vertAttribs[1].location = 1;
    vertAttribs[1].binding = 0;
    vertAttribs[1].format = VK_FORMAT_R32G32_SFLOAT;
    vertAttribs[1].offset = sizeof(float) * 3;

    VkPipelineVertexInputStateCreateInfo vertexInput{
        VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    vertexInput.vertexBindingDescriptionCount = 1;
    vertexInput.pVertexBindingDescriptions = &vertBinding;
    vertexInput.vertexAttributeDescriptionCount = 2;
    vertexInput.pVertexAttributeDescriptions = vertAttribs;

    VkPipelineInputAssemblyStateCreateInfo inputAssembly{
        VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;

    VkPipelineViewportStateCreateInfo viewportState{
        VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewportState.viewportCount = 1;
    viewportState.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo rasterizer{
        VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
    rasterizer.cullMode = VK_CULL_MODE_NONE;
    rasterizer.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rasterizer.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo multisample{
        VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState blendAttachment{};
    blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                      VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    blendAttachment.blendEnable = VK_FALSE;

    VkPipelineColorBlendStateCreateInfo colorBlend{
        VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    colorBlend.attachmentCount = 1;
    colorBlend.pAttachments = &blendAttachment;

    VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamicState{
        VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamicState.dynamicStateCount = static_cast<uint32_t>(std::size(dynamicStates));
    dynamicState.pDynamicStates = dynamicStates;

    VkGraphicsPipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    pipelineInfo.stageCount = 2;
    pipelineInfo.pStages = stages;
    pipelineInfo.pVertexInputState = &vertexInput;
    pipelineInfo.pInputAssemblyState = &inputAssembly;
    pipelineInfo.pViewportState = &viewportState;
    pipelineInfo.pRasterizationState = &rasterizer;
    pipelineInfo.pMultisampleState = &multisample;
    pipelineInfo.pColorBlendState = &colorBlend;
    pipelineInfo.pDynamicState = &dynamicState;
    pipelineInfo.layout = state.videoPipelineLayout;
    pipelineInfo.renderPass = state.renderPass;
    pipelineInfo.subpass = 0;
    VKR(vkCreateGraphicsPipelines(
        state.vkDevice, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &state.videoPipeline));

    vkDestroyShaderModule(state.vkDevice, vertModule, nullptr);
    vkDestroyShaderModule(state.vkDevice, fragModule, nullptr);
}

// ===========================================================================
// ESTÁGIO 4 — Pipeline de UI/controles (RGBA8888 com alpha blending)
// Equivalente ao bloco GLES (vr_player_app.cpp:858-934): AImageReader cria
// uma ANativeWindow que o Kotlin usa como Surface de um VirtualDisplay/
// Presentation. A cada frame, AImageReader_acquireLatestImage retorna o
// AHardwareBuffer mais recente, que e importado como VkImage RGBA8888
// (nao YCbCr — muito mais simples que o Estagio 3).
// ===========================================================================

// Cria um VkImage RGBA8888 vazio de dadas dimensoes, com layout UNDEFINED.
// Usado para alocar as texturas de UI/controles antes do primeiro frame.
static void CreateUiImage(AppState& state, uint32_t width, uint32_t height,
                          VkImage& outImage, VkDeviceMemory& outMemory, VkImageView& outView) {
    VkImageCreateInfo imgInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imgInfo.imageType   = VK_IMAGE_TYPE_2D;
    imgInfo.format      = VK_FORMAT_R8G8B8A8_UNORM;
    imgInfo.extent      = {width, height, 1};
    imgInfo.mipLevels   = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples     = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling      = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage       = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    VKR(vkCreateImage(state.vkDevice, &imgInfo, nullptr, &outImage));

    VkMemoryRequirements memReqs;
    vkGetImageMemoryRequirements(state.vkDevice, outImage, &memReqs);

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(state.vkPhysicalDevice, &memProps);
    uint32_t memTypeIdx = UINT32_MAX;
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((memReqs.memoryTypeBits & (1u << i)) &&
            (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
            memTypeIdx = i; break;
        }
    }

    VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocInfo.allocationSize  = memReqs.size;
    allocInfo.memoryTypeIndex = memTypeIdx;
    VKR(vkAllocateMemory(state.vkDevice, &allocInfo, nullptr, &outMemory));
    VKR(vkBindImageMemory(state.vkDevice, outImage, outMemory, 0));

    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image    = outImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format   = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    VKR(vkCreateImageView(state.vkDevice, &viewInfo, nullptr, &outView));
}

// Atualiza uma VkImage de UI a partir de um AHardwareBuffer (RGBA8888).
// Usa blit via staging buffer (a forma mais portavel — sem extensoes EGL).
// Chamado a cada frame que um novo AImage esta disponivel via AImageReader.
static void UpdateUiImageFromHwb(AppState& state, AHardwareBuffer* hwb, VkImage dstImage,
                                  uint32_t width, uint32_t height) {
    // Lockear o AHardwareBuffer para leitura da CPU
    void* src = nullptr;
    AHardwareBuffer_lock(hwb, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, nullptr, &src);
    if (!src) return;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hwb, &desc);
    const uint32_t rowBytes = desc.stride * 4; // RGBA8888

    // Criar staging buffer
    const VkDeviceSize bufSize = (VkDeviceSize)rowBytes * height;
    VkBuffer stagingBuf;
    VkDeviceMemory stagingMem;

    VkBufferCreateInfo bufInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufInfo.size  = bufSize;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    VKR(vkCreateBuffer(state.vkDevice, &bufInfo, nullptr, &stagingBuf));

    VkMemoryRequirements memReqs;
    vkGetBufferMemoryRequirements(state.vkDevice, stagingBuf, &memReqs);
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(state.vkPhysicalDevice, &memProps);
    uint32_t memTypeIdx = UINT32_MAX;
    const auto hostVisible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((memReqs.memoryTypeBits & (1u << i)) &&
            ((memProps.memoryTypes[i].propertyFlags & hostVisible) == hostVisible)) {
            memTypeIdx = i; break;
        }
    }
    VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocInfo.allocationSize  = memReqs.size;
    allocInfo.memoryTypeIndex = memTypeIdx;
    VKR(vkAllocateMemory(state.vkDevice, &allocInfo, nullptr, &stagingMem));
    VKR(vkBindBufferMemory(state.vkDevice, stagingBuf, stagingMem, 0));

    void* dst = nullptr;
    VKR(vkMapMemory(state.vkDevice, stagingMem, 0, bufSize, 0, &dst));
    memcpy(dst, src, (size_t)bufSize);
    vkUnmapMemory(state.vkDevice, stagingMem);
    AHardwareBuffer_unlock(hwb, nullptr);

    // Command buffer de upload (one-shot)
    VkCommandBufferAllocateInfo cbAlloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cbAlloc.commandPool        = state.vkCommandPool;
    cbAlloc.level              = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbAlloc.commandBufferCount = 1;
    VkCommandBuffer cmd;
    VKR(vkAllocateCommandBuffers(state.vkDevice, &cbAlloc, &cmd));

    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VKR(vkBeginCommandBuffer(cmd, &beginInfo));

    // UNDEFINED -> TRANSFER_DST
    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.srcAccessMask = 0;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.image = dstImage;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);

    VkBufferImageCopy region{};
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.layerCount = 1;
    region.imageExtent = {width, height, 1};
    region.bufferRowLength = desc.stride; // <--- VITAL: AHardwareBuffer can have padded stride!
    vkCmdCopyBufferToImage(cmd, stagingBuf, dstImage,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    // TRANSFER_DST -> SHADER_READ_ONLY
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);

    VKR(vkEndCommandBuffer(cmd));
    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmd;
    VKR(vkQueueSubmit(state.vkQueue, 1, &submitInfo, VK_NULL_HANDLE));
    VKR(vkQueueWaitIdle(state.vkQueue));

    vkFreeCommandBuffers(state.vkDevice, state.vkCommandPool, 1, &cmd);
    vkDestroyBuffer(state.vkDevice, stagingBuf, nullptr);
    vkFreeMemory(state.vkDevice, stagingMem, nullptr);
}

// Cria o pipeline de UI (alpha blending, sampler normal, ui.vert/ui.frag).
void CreateUiPipeline(AppState& state, android_app* app) {
    VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.anisotropyEnable = VK_TRUE;
    samplerInfo.maxAnisotropy = 4.0f;
    VKR(vkCreateSampler(state.vkDevice, &samplerInfo, nullptr, &state.uiSampler));

    // Descriptor set layout com sampler nao imutavel (RGBA normal)
    VkDescriptorSetLayoutBinding binding{};
    binding.binding         = 0;
    binding.descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    binding.descriptorCount = 1;
    binding.stageFlags      = VK_SHADER_STAGE_FRAGMENT_BIT;
    binding.pImmutableSamplers = &state.uiSampler;

    VkDescriptorSetLayoutCreateInfo dsLayout{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dsLayout.bindingCount = 1;
    dsLayout.pBindings    = &binding;
    VKR(vkCreateDescriptorSetLayout(state.vkDevice, &dsLayout, nullptr, &state.uiDescriptorSetLayout));

    // Pool para 2 sets (ui + controls)
    VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2};
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.flags         = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    poolInfo.maxSets       = 2;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes    = &poolSize;
    VKR(vkCreateDescriptorPool(state.vkDevice, &poolInfo, nullptr, &state.uiDescriptorPool));

    // Push constant: MVP (64 bytes) + alpha (4 bytes) + pad (12 bytes)
    VkPushConstantRange pcRange{VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(UiPushConstants)};
    VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.setLayoutCount        = 1;
    layoutInfo.pSetLayouts           = &state.uiDescriptorSetLayout;
    layoutInfo.pushConstantRangeCount = 1;
    layoutInfo.pPushConstantRanges   = &pcRange;
    VKR(vkCreatePipelineLayout(state.vkDevice, &layoutInfo, nullptr, &state.uiPipelineLayout));

    // Shaders
    VkShaderModule vertMod, fragMod;
    VkShaderModuleCreateInfo smInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smInfo.codeSize = sizeof(kUiVertSpirv); smInfo.pCode = reinterpret_cast<const uint32_t*>(kUiVertSpirv);
    VKR(vkCreateShaderModule(state.vkDevice, &smInfo, nullptr, &vertMod));
    smInfo.codeSize = sizeof(kUiFragSpirv); smInfo.pCode = reinterpret_cast<const uint32_t*>(kUiFragSpirv);
    VKR(vkCreateShaderModule(state.vkDevice, &smInfo, nullptr, &fragMod));

    VkPipelineShaderStageCreateInfo stages[2] = {};
    stages[0].sType  = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage  = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertMod;
    stages[0].pName  = "main";
    stages[1].sType  = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage  = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragMod;
    stages[1].pName  = "main";

    // Mesmo vertex layout do quad de video (posicao + UV)
    VkVertexInputBindingDescription bindingDesc{0, 5 * sizeof(float), VK_VERTEX_INPUT_RATE_VERTEX};
    VkVertexInputAttributeDescription attrs[2] = {
        {0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0},
        {1, 0, VK_FORMAT_R32G32_SFLOAT, 3 * sizeof(float)},
    };
    VkPipelineVertexInputStateCreateInfo vtxInput{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    vtxInput.vertexBindingDescriptionCount   = 1;
    vtxInput.pVertexBindingDescriptions      = &bindingDesc;
    vtxInput.vertexAttributeDescriptionCount = 2;
    vtxInput.pVertexAttributeDescriptions    = attrs;

    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;

    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = 1; vp.scissorCount = 1;
    VkDynamicState dynStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dyn{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dyn.dynamicStateCount = 2; dyn.pDynamicStates = dynStates;

    VkPipelineRasterizationStateCreateInfo rast{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rast.polygonMode = VK_POLYGON_MODE_FILL;
    rast.cullMode    = VK_CULL_MODE_NONE; // UI pode ser vista de qualquer angulo
    rast.frontFace   = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rast.lineWidth   = 1.0f;

    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    // Alpha blending (SRC_ALPHA / ONE_MINUS_SRC_ALPHA) — mesmo do GLES (vr_player_app.cpp:863-868)
    VkPipelineColorBlendAttachmentState blendAtt{};
    blendAtt.blendEnable         = VK_TRUE;
    blendAtt.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
    blendAtt.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    blendAtt.colorBlendOp        = VK_BLEND_OP_ADD;
    blendAtt.srcAlphaBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
    blendAtt.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    blendAtt.alphaBlendOp        = VK_BLEND_OP_ADD;
    blendAtt.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                              VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo blend{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    blend.attachmentCount = 1; blend.pAttachments = &blendAtt;

    VkPipelineDepthStencilStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
    ds.depthTestEnable  = VK_FALSE;
    ds.depthWriteEnable = VK_FALSE;

    VkGraphicsPipelineCreateInfo pipeInfo{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    pipeInfo.stageCount          = 2;
    pipeInfo.pStages             = stages;
    pipeInfo.pVertexInputState   = &vtxInput;
    pipeInfo.pInputAssemblyState = &ia;
    pipeInfo.pViewportState      = &vp;
    pipeInfo.pRasterizationState = &rast;
    pipeInfo.pMultisampleState   = &ms;
    pipeInfo.pColorBlendState    = &blend;
    pipeInfo.pDepthStencilState  = &ds;
    pipeInfo.pDynamicState       = &dyn;
    pipeInfo.layout              = state.uiPipelineLayout;
    pipeInfo.renderPass          = state.renderPass;
    VKR(vkCreateGraphicsPipelines(state.vkDevice, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &state.uiPipeline));

    vkDestroyShaderModule(state.vkDevice, vertMod, nullptr);
    vkDestroyShaderModule(state.vkDevice, fragMod, nullptr);

    // Alocar VkImages para UI e controles
    CreateUiImage(state, 1024, 1024, state.uiImage, state.uiImageMemory, state.uiImageView);
    CreateUiImage(state, 1024, 384, state.controlsImage, state.controlsImageMemory, state.controlsImageView);

    // Alocar descriptor sets para UI e controles
    VkDescriptorSetAllocateInfo dsAlloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dsAlloc.descriptorPool     = state.uiDescriptorPool;
    dsAlloc.descriptorSetCount = 1;
    dsAlloc.pSetLayouts        = &state.uiDescriptorSetLayout;
    VKR(vkAllocateDescriptorSets(state.vkDevice, &dsAlloc, &state.uiDescriptorSet));
    VKR(vkAllocateDescriptorSets(state.vkDevice, &dsAlloc, &state.controlsDescriptorSet));

    // Atualizar descriptor sets com as image views
    auto writeUiDs = [&](VkDescriptorSet ds, VkImageView view) {
        VkDescriptorImageInfo imgInfo{};
        imgInfo.sampler     = state.uiSampler;
        imgInfo.imageView   = view;
        imgInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        write.dstSet          = ds;
        write.dstBinding      = 0;
        write.descriptorCount = 1;
        write.descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo      = &imgInfo;
        vkUpdateDescriptorSets(state.vkDevice, 1, &write, 0, nullptr);
    };
    writeUiDs(state.uiDescriptorSet, state.uiImageView);
    writeUiDs(state.controlsDescriptorSet, state.controlsImageView);

    // Criar AImageReaders com flags GPU_COLOR_OUTPUT para que o VirtualDisplay
    // do Kotlin possa renderizar na Surface (equivale ao GLES: vr_player_app.cpp:870-913).
    // GPU_COLOR_OUTPUT: o Kotlin escreve na Surface via VirtualDisplay/Canvas.
    // GPU_SAMPLED_IMAGE: o shader Vulkan le a textura resultante.
    // CPU_READ_RARELY: necessario para o UpdateUiImageFromHwb (staging via CPU).
    media_status_t uiStatus = AImageReader_newWithUsage(
        1024, 1024, AIMAGE_FORMAT_RGBA_8888,
        AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
        AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
        AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, 2,
        &state.uiImageReader);

    media_status_t ctrlStatus = AImageReader_newWithUsage(
        1024, 384, AIMAGE_FORMAT_RGBA_8888,
        AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
        AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
        AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, 2,
        &state.controlsImageReader);

    // Wiring JNI: obter ANativeWindow de cada AImageReader, converter para
    // Surface Java e chamar setupVirtualDisplay / setupControlsVirtualDisplay.
    // Exatamente o mesmo padrao do caminho GLES (vr_player_app.cpp:878-934),
    // so que usando android_app->activity em vez de xrJava do OVRFW.
    if (app && app->activity && app->activity->vm) {
        JNIEnv* env = nullptr;
        bool attached = false;
        jint res = app->activity->vm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (res == JNI_EDETACHED) {
            app->activity->vm->AttachCurrentThread(&env, nullptr);
            attached = true;
        }

        if (env) {
            jobject activityObj = app->activity->clazz;
            jclass vrActivityClass = env->GetObjectClass(activityObj);

            // --- UI (file browser, 1024x1024) ---
            if (uiStatus == AMEDIA_OK && state.uiImageReader) {
                ANativeWindow* uiWindow = nullptr;
                AImageReader_getWindow(state.uiImageReader, &uiWindow);
                if (uiWindow) {
                    jobject uiSurface = ANativeWindow_toSurface(env, uiWindow);
                    jmethodID setupUI = env->GetStaticMethodID(
                        vrActivityClass, "setupVirtualDisplay",
                        "(Lcom/vrplayer/VRActivity;Landroid/view/Surface;II)V");
                    if (setupUI) {
                        env->CallStaticVoidMethod(vrActivityClass, setupUI,
                            activityObj, uiSurface, 1024, 1024);
                        LOGI("Estagio 4: setupVirtualDisplay (UI 1024x1024) chamado com sucesso");
                    } else {
                        LOGE("Estagio 4: setupVirtualDisplay NAO ENCONTRADO");
                    }
                    env->DeleteLocalRef(uiSurface);
                } else {
                    LOGE("Estagio 4: AImageReader_getWindow retornou null para UI");
                }
            } else {
                LOGE("Estagio 4: AImageReader_newWithUsage falhou para UI (status=%d)", uiStatus);
            }

            // --- Controles (painel inferior, 1024x384) ---
            if (ctrlStatus == AMEDIA_OK && state.controlsImageReader) {
                ANativeWindow* ctrlWindow = nullptr;
                AImageReader_getWindow(state.controlsImageReader, &ctrlWindow);
                if (ctrlWindow) {
                    jobject ctrlSurface = ANativeWindow_toSurface(env, ctrlWindow);
                    jmethodID setupCtrl = env->GetStaticMethodID(
                        vrActivityClass, "setupControlsVirtualDisplay",
                        "(Lcom/vrplayer/VRActivity;Landroid/view/Surface;II)V");
                    if (setupCtrl) {
                        env->CallStaticVoidMethod(vrActivityClass, setupCtrl,
                            activityObj, ctrlSurface, 1024, 384);
                        LOGI("Estagio 4: setupControlsVirtualDisplay (1024x384) chamado com sucesso");
                    } else {
                        LOGE("Estagio 4: setupControlsVirtualDisplay NAO ENCONTRADO");
                    }
                    env->DeleteLocalRef(ctrlSurface);
                } else {
                    LOGE("Estagio 4: AImageReader_getWindow retornou null para controles");
                }
            } else {
                LOGE("Estagio 4: AImageReader_newWithUsage falhou para controles (status=%d)", ctrlStatus);
            }

            env->DeleteLocalRef(vrActivityClass);
        }

        if (attached) {
            app->activity->vm->DetachCurrentThread();
        }
    } else {
        LOGE("Estagio 4: android_app nulo — VirtualDisplay nao configurado");
    }

    LOGI("Estagio 4: pipeline de UI/controles criado (1024x1024 + 1024x384)");
}

// Atualiza texturas de UI/controles a partir dos AImageReaders.
// Chamado a cada frame antes de RenderFrame.
void UpdateUiFrames(AppState& state) {
    auto acquireAndUpdate = [&](AImageReader* reader, VkImage dst, bool& hasFrame,
                                 uint32_t w, uint32_t h) {
        if (!reader) return;
        AImage* image = nullptr;
        if (AImageReader_acquireLatestImage(reader, &image) == AMEDIA_OK && image) {
            AHardwareBuffer* hwb = nullptr;
            AImage_getHardwareBuffer(image, &hwb);
            if (hwb) {
                UpdateUiImageFromHwb(state, hwb, dst, w, h);
                hasFrame = true;
            }
            AImage_delete(image);
        }
    };
    acquireAndUpdate(state.uiImageReader, state.uiImage, state.uiHasFrame, 1024, 1024);
    acquireAndUpdate(state.controlsImageReader, state.controlsImage, state.controlsHasFrame, 1024, 384);
}

// ===========================================================================
// ESTÁGIO 5 — Pipeline estereo/esfera e geometria de globo
// Porta os modos SBS/OU/Sphere360/Sphere180 do caminho GLES.
// ===========================================================================

// Gera a malha da esfera equiretangular (equivalente a OVRFW::BuildGlobe).
// Parametros: rings x slices (caminho GLES usa ~70x128, documentado em
// vr_player_app.cpp:843 "resolucao 128x~70"). UV mapeado linearmente sobre
// [0,1]x[0,1] — o fragment shader cuida do recorte polar/estereo.
void CreateSphereGeometry(AppState& state) {
    const int rings  = 70;
    const int slices = 128;
    const float radius = kSphereRadius;

    struct SphereVertex { float x, y, z, u, v; };
    std::vector<SphereVertex> verts;
    std::vector<uint32_t> indices;

    for (int r = 0; r <= rings; r++) {
        float phi = M_PI * r / rings; // 0..PI (polo norte ao sul)
        float vv  = (float)r / rings;
        for (int s = 0; s <= slices; s++) {
            float theta = 2.0f * M_PI * s / slices; // 0..2PI
            float uu    = (float)s / slices;
            float x = -radius * sinf(phi) * sinf(theta); // invertido: camera dentro da esfera
            float y =  radius * cosf(phi);
            float z = -radius * sinf(phi) * cosf(theta);
            verts.push_back({x, y, z, uu, vv});
        }
    }

    for (int r = 0; r < rings; r++) {
        for (int s = 0; s < slices; s++) {
            uint32_t a = (uint32_t)((r    ) * (slices + 1) + s    );
            uint32_t b = (uint32_t)((r    ) * (slices + 1) + s + 1);
            uint32_t c = (uint32_t)((r + 1) * (slices + 1) + s    );
            uint32_t d = (uint32_t)((r + 1) * (slices + 1) + s + 1);
            indices.push_back(a); indices.push_back(b); indices.push_back(c);
            indices.push_back(b); indices.push_back(d); indices.push_back(c);
        }
    }
    state.sphereIndexCount = (uint32_t)indices.size();

    // Helper lambda para criar buffer host-visible (staging simplificado)
    auto createDeviceBuffer = [&](VkBufferUsageFlags usage, const void* data, VkDeviceSize size,
                                   VkBuffer& outBuf, VkDeviceMemory& outMem) {
        VkBufferCreateInfo bInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bInfo.size  = size;
        bInfo.usage = usage;
        VKR(vkCreateBuffer(state.vkDevice, &bInfo, nullptr, &outBuf));

        VkMemoryRequirements mr;
        vkGetBufferMemoryRequirements(state.vkDevice, outBuf, &mr);
        VkPhysicalDeviceMemoryProperties mp;
        vkGetPhysicalDeviceMemoryProperties(state.vkPhysicalDevice, &mp);
        uint32_t mIdx = UINT32_MAX;
        const auto hv = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
            if ((mr.memoryTypeBits & (1u << i)) && ((mp.memoryTypes[i].propertyFlags & hv) == hv)) {
                mIdx = i; break;
            }
        }
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ai.allocationSize = mr.size; ai.memoryTypeIndex = mIdx;
        VKR(vkAllocateMemory(state.vkDevice, &ai, nullptr, &outMem));
        VKR(vkBindBufferMemory(state.vkDevice, outBuf, outMem, 0));
        void* dst; VKR(vkMapMemory(state.vkDevice, outMem, 0, size, 0, &dst));
        memcpy(dst, data, (size_t)size);
        vkUnmapMemory(state.vkDevice, outMem);
    };

    createDeviceBuffer(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
        verts.data(), verts.size() * sizeof(SphereVertex),
        state.sphereVertexBuffer, state.sphereVertexMemory);
    createDeviceBuffer(VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
        indices.data(), indices.size() * sizeof(uint32_t),
        state.sphereIndexBuffer, state.sphereIndexMemory);

    LOGI("Estagio 5: esfera gerada (%d vertices, %d indices)", (int)verts.size(), (int)indices.size());
}

// Cria o pipeline estereo (stereo.vert/frag) que serve SBS/OU/Sphere360/Sphere180.
// Reusa o videoDescriptorSetLayout (mesmo sampler YCbCr imutavel do Estagio 3)
// pois a textura de fonte e identica ao Estagio 3 — so a logica de UV muda.
void CreateStereoPipeline(AppState& state) {
    // Push constant: MVP (64) + 4 ints (16) = 80 bytes
    VkPushConstantRange pcRange{VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(StereoPushConstants)};
    VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.setLayoutCount         = 1;
    layoutInfo.pSetLayouts            = &state.videoDescriptorSetLayout; // reusa YCbCr layout
    layoutInfo.pushConstantRangeCount = 1;
    layoutInfo.pPushConstantRanges    = &pcRange;
    VKR(vkCreatePipelineLayout(state.vkDevice, &layoutInfo, nullptr, &state.stereoPipelineLayout));

    VkShaderModule vertMod, fragMod;
    VkShaderModuleCreateInfo smInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smInfo.codeSize = sizeof(kStereoVertSpirv); smInfo.pCode = reinterpret_cast<const uint32_t*>(kStereoVertSpirv);
    VKR(vkCreateShaderModule(state.vkDevice, &smInfo, nullptr, &vertMod));
    smInfo.codeSize = sizeof(kStereoFragSpirv); smInfo.pCode = reinterpret_cast<const uint32_t*>(kStereoFragSpirv);
    VKR(vkCreateShaderModule(state.vkDevice, &smInfo, nullptr, &fragMod));

    VkPipelineShaderStageCreateInfo stages[2] = {};
    stages[0].sType  = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage  = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertMod; stages[0].pName = "main";
    stages[1].sType  = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage  = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragMod; stages[1].pName = "main";

    // Vertex layout: posicao(3) + UV(2) = 5 floats
    VkVertexInputBindingDescription bindDesc{0, 5 * sizeof(float), VK_VERTEX_INPUT_RATE_VERTEX};
    VkVertexInputAttributeDescription attrs[2] = {
        {0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0},
        {1, 0, VK_FORMAT_R32G32_SFLOAT, 3 * sizeof(float)},
    };
    VkPipelineVertexInputStateCreateInfo vtxInput{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    vtxInput.vertexBindingDescriptionCount   = 1;
    vtxInput.pVertexBindingDescriptions      = &bindDesc;
    vtxInput.vertexAttributeDescriptionCount = 2;
    vtxInput.pVertexAttributeDescriptions    = attrs;

    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    // Triangles para a esfera (index draw); quad plano pode usar STRIP mas
    // a mesma topologia TRIANGLE_LIST funciona com ambos.
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = 1; vp.scissorCount = 1;
    VkDynamicState dynStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dyn{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dyn.dynamicStateCount = 2; dyn.pDynamicStates = dynStates;

    VkPipelineRasterizationStateCreateInfo rast{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rast.polygonMode = VK_POLYGON_MODE_FILL;
    rast.cullMode    = VK_CULL_MODE_NONE; // esfera: camera dentro (sem backface culling)
    rast.frontFace   = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rast.lineWidth   = 1.0f;

    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState blendAtt{};
    blendAtt.colorWriteMask = 0xF;
    VkPipelineColorBlendStateCreateInfo blend{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    blend.attachmentCount = 1; blend.pAttachments = &blendAtt;

    // Sem depth test (esfera "infinitamente longe" — equivalente a
    // m_sphereSurfaceDef.graphicsCommand.GpuState.depthEnable = false no GLES)
    VkPipelineDepthStencilStateCreateInfo dsState{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
    dsState.depthTestEnable  = VK_FALSE;
    dsState.depthWriteEnable = VK_FALSE;

    VkGraphicsPipelineCreateInfo pipeInfo{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    pipeInfo.stageCount          = 2;
    pipeInfo.pStages             = stages;
    pipeInfo.pVertexInputState   = &vtxInput;
    pipeInfo.pInputAssemblyState = &ia;
    pipeInfo.pViewportState      = &vp;
    pipeInfo.pRasterizationState = &rast;
    pipeInfo.pMultisampleState   = &ms;
    pipeInfo.pColorBlendState    = &blend;
    pipeInfo.pDepthStencilState  = &dsState;
    pipeInfo.pDynamicState       = &dyn;
    pipeInfo.layout              = state.stereoPipelineLayout;
    pipeInfo.renderPass          = state.renderPass;
    VKR(vkCreateGraphicsPipelines(state.vkDevice, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &state.stereoPipeline));

    vkDestroyShaderModule(state.vkDevice, vertMod, nullptr);
    vkDestroyShaderModule(state.vkDevice, fragMod, nullptr);
    LOGI("Estagio 5: pipeline estereo/esfera criado");
}

// Cria um vertex buffer simples para o beam (2 vertices: inicio + fim).
// O BeamRenderer original do OVRFW e muito dependente de GLES; esta versao
// usa o pipeline do quad (quad.vert/frag) com primitiva LINES para um laser
// simples, suficiente para apontar para paineis de UI.
void CreateBeamResources(AppState& state) {
    // 2 vertices: [x, y, z, u, v] para o inicio e fim do raio
    float beamVerts[10] = {
        0.0f, 0.0f, 0.0f,  0.0f, 0.0f, // inicio
        0.0f, 0.0f,-1.0f,  1.0f, 1.0f, // fim (sera atualizado por frame)
    };
    VkBufferCreateInfo bufInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufInfo.size  = sizeof(beamVerts);
    bufInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    VKR(vkCreateBuffer(state.vkDevice, &bufInfo, nullptr, &state.beamVertexBuffer));

    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(state.vkDevice, state.beamVertexBuffer, &mr);
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(state.vkPhysicalDevice, &mp);
    uint32_t mIdx = UINT32_MAX;
    const auto hv = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        if ((mr.memoryTypeBits & (1u << i)) && ((mp.memoryTypes[i].propertyFlags & hv) == hv)) {
            mIdx = i; break;
        }
    }
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size; ai.memoryTypeIndex = mIdx;
    VKR(vkAllocateMemory(state.vkDevice, &ai, nullptr, &state.beamVertexMemory));
    VKR(vkBindBufferMemory(state.vkDevice, state.beamVertexBuffer, state.beamVertexMemory, 0));
    void* dst; VKR(vkMapMemory(state.vkDevice, state.beamVertexMemory, 0, sizeof(beamVerts), 0, &dst));
    memcpy(dst, beamVerts, sizeof(beamVerts));
    vkUnmapMemory(state.vkDevice, state.beamVertexMemory);

    // Pipeline de linhas — reusa o pipeline layout do quad (push constant + cor)
    // O pipeline layout do quad ja existe (state.pipelineLayout). Criamos um
    // pipeline separado com VK_PRIMITIVE_TOPOLOGY_LINE_LIST.
    VkShaderModule vertMod, fragMod;
    VkShaderModuleCreateInfo smInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smInfo.codeSize = sizeof(kQuadVertSpirv); smInfo.pCode = reinterpret_cast<const uint32_t*>(kQuadVertSpirv);
    VKR(vkCreateShaderModule(state.vkDevice, &smInfo, nullptr, &vertMod));
    smInfo.codeSize = sizeof(kQuadFragSpirv); smInfo.pCode = reinterpret_cast<const uint32_t*>(kQuadFragSpirv);
    VKR(vkCreateShaderModule(state.vkDevice, &smInfo, nullptr, &fragMod));

    state.beamPipelineLayout = state.pipelineLayout; // reusa sem criar novo

    VkPipelineShaderStageCreateInfo stages[2] = {};
    stages[0] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT; stages[0].module = vertMod; stages[0].pName = "main";
    stages[1] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module = fragMod; stages[1].pName = "main";

    VkVertexInputBindingDescription bindDesc{0, 5 * sizeof(float), VK_VERTEX_INPUT_RATE_VERTEX};
    VkVertexInputAttributeDescription attrs[2] = {
        {0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0},
        {1, 0, VK_FORMAT_R32G32_SFLOAT, 3 * sizeof(float)},
    };
    VkPipelineVertexInputStateCreateInfo vtxInput{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    vtxInput.vertexBindingDescriptionCount = 1; vtxInput.pVertexBindingDescriptions = &bindDesc;
    vtxInput.vertexAttributeDescriptionCount = 2; vtxInput.pVertexAttributeDescriptions = attrs;

    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = VK_PRIMITIVE_TOPOLOGY_LINE_LIST;

    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = 1; vp.scissorCount = 1;
    VkDynamicState dynStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dyn{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dyn.dynamicStateCount = 2; dyn.pDynamicStates = dynStates;

    VkPipelineRasterizationStateCreateInfo rast{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rast.polygonMode = VK_POLYGON_MODE_FILL; rast.cullMode = VK_CULL_MODE_NONE;
    rast.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE; rast.lineWidth = 2.0f;

    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState blendAtt{};
    blendAtt.colorWriteMask = 0xF;
    VkPipelineColorBlendStateCreateInfo blend{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    blend.attachmentCount = 1; blend.pAttachments = &blendAtt;

    VkPipelineDepthStencilStateCreateInfo dsState{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
    dsState.depthTestEnable = VK_FALSE; dsState.depthWriteEnable = VK_FALSE;

    VkGraphicsPipelineCreateInfo pipeInfo{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    pipeInfo.stageCount = 2; pipeInfo.pStages = stages;
    pipeInfo.pVertexInputState = &vtxInput; pipeInfo.pInputAssemblyState = &ia;
    pipeInfo.pViewportState = &vp; pipeInfo.pRasterizationState = &rast;
    pipeInfo.pMultisampleState = &ms; pipeInfo.pColorBlendState = &blend;
    pipeInfo.pDepthStencilState = &dsState; pipeInfo.pDynamicState = &dyn;
    pipeInfo.layout = state.beamPipelineLayout; pipeInfo.renderPass = state.renderPass;
    VKR(vkCreateGraphicsPipelines(state.vkDevice, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &state.beamPipeline));

    vkDestroyShaderModule(state.vkDevice, vertMod, nullptr);
    vkDestroyShaderModule(state.vkDevice, fragMod, nullptr);
    LOGI("Estagio 5: beam pipeline criado");
}

void CreateCommandResources(AppState& state) {
    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = state.vkQueueFamilyIndex;
    VKR(vkCreateCommandPool(state.vkDevice, &poolInfo, nullptr, &state.vkCommandPool));

    VkCommandBufferAllocateInfo allocInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocInfo.commandPool = state.vkCommandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    VKR(vkAllocateCommandBuffers(state.vkDevice, &allocInfo, &state.vkCommandBuffer));
}

// Estagio 2: limpa o fundo (mesmo azul do Estagio 1, por continuidade
// visual entre estagios) e desenha o quad estatico por cima via o pipeline
// grafico. Usado como fallback quando nao ha frame de video disponivel.
// vkQueueWaitIdle por frame continua deliberadamente ingenuo —
// performance so importa a partir do Estagio 3+.
// helper para desenhar os paineis de UI/Controles (Estagio 4) por cima do video ou do fallback
static void DrawUiQuads(AppState& state, VkCommandBuffer cmd, const Mat4& proj, const Mat4& view, XrVector3f headPos) {
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, state.uiPipeline);

    VkDeviceSize offset = 0;
    // O UI Pipeline (Estagio 4) tem layout de vertices com UV (5 floats), portanto
    // DEVE usar o videoVertexBuffer (que tem XYZ+UV) e nao o quadVertexBuffer (so XYZ)
    vkCmdBindVertexBuffers(cmd, 0, 1, &state.videoVertexBuffer, &offset);

    // Calcular UI transform (identico ao GLES: vr_player_app.cpp:973)
    float uiPosX = -1.2f, uiPosY = 1.0f, uiPosZ = -1.0f;
    float uiScale = 1.5f;
    float toHeadX = headPos.x - uiPosX;
    float toHeadZ = headPos.z - uiPosZ;
    float uiYaw = atan2f(toHeadX, toHeadZ);
    Mat4 uiModel = Mat4Multiply(Mat4Multiply(Mat4Translation(uiPosX, uiPosY, uiPosZ), Mat4RotationY(uiYaw)), Mat4Scale(uiScale, uiScale, 1.0f));
    Mat4 uiMvp = Mat4Multiply(Mat4Multiply(proj, view), uiModel);

    // Controls transform (identico ao GLES: vr_player_app.cpp:983)
    Mat4 controlsModel = Mat4Multiply(
        Mat4Multiply(Mat4Translation(0.0f, 0.4f, -1.9f), Mat4RotationX(-0.3f)),
        Mat4Scale(0.8f, 0.3f, 1.0f));
    Mat4 controlsMvp = Mat4Multiply(Mat4Multiply(proj, view), controlsModel);

    UiPushConstants push{};
    
    // UI (File Browser)
    if (state.uiHasFrame) {
        push.mvp = uiMvp;
        push.alpha = 1.0f; // TODO: auto-hide alpha fading
        vkCmdPushConstants(cmd, state.uiPipelineLayout, 
            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 
            0, sizeof(push), &push);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
            state.uiPipelineLayout, 0, 1, &state.uiDescriptorSet, 0, nullptr);
        vkCmdDraw(cmd, 4, 1, 0, 0);
    }

    // Controls
    if (state.controlsHasFrame) {
        push.mvp = controlsMvp;
        push.alpha = 1.0f;
        vkCmdPushConstants(cmd, state.uiPipelineLayout, 
            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 
            0, sizeof(push), &push);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
            state.uiPipelineLayout, 0, 1, &state.controlsDescriptorSet, 0, nullptr);
        vkCmdDraw(cmd, 4, 1, 0, 0);
    }

    // Beam (Laser)
    if (state.lastRayDir.z != 0.0f) { // Se o controle foi detectado
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, state.beamPipeline);
        vkCmdBindVertexBuffers(cmd, 0, 1, &state.beamVertexBuffer, &offset);

        // O beam em CreateBeamResources vai de (0,0,0) ate (0,0,-2) no eixo Z negativo.
        // Precisamos criar um model matrix que translada para a origem do raio e rotaciona para a direcao.
        XrVector3f up = {0.0f, 1.0f, 0.0f};
        if (fabs(state.lastRayDir.y) > 0.99f) up = {1.0f, 0.0f, 0.0f}; // fallback se olhando pra cima/baixo
        
        XrVector3f z = {-state.lastRayDir.x, -state.lastRayDir.y, -state.lastRayDir.z};
        
        XrVector3f x = {
            up.y * z.z - up.z * z.y,
            up.z * z.x - up.x * z.z,
            up.x * z.y - up.y * z.x
        };
        float xLen = sqrtf(x.x*x.x + x.y*x.y + x.z*x.z);
        if (xLen > 0.0001f) { x.x /= xLen; x.y /= xLen; x.z /= xLen; }

        XrVector3f y = {
            z.y * x.z - z.z * x.y,
            z.z * x.x - z.x * x.z,
            z.x * x.y - z.y * x.x
        };

        float beamLength = 5.0f; // Default 5 meters if no hit
        if (state.lastHitDist > 0.0f) {
            beamLength = state.lastHitDist;
        }
        float zScale = beamLength / 2.0f; // Base geometry is 2.0m long

        Mat4 beamModel = {{
            x.x, x.y, x.z, 0.0f,
            y.x, y.y, y.z, 0.0f,
            z.x * zScale, z.y * zScale, z.z * zScale, 0.0f,
            state.lastRayOrigin.x, state.lastRayOrigin.y, state.lastRayOrigin.z, 1.0f
        }};

        BeamPushConstants beamPush{};
        beamPush.mvp = Mat4Multiply(Mat4Multiply(proj, view), beamModel);
        beamPush.color[0] = 0.0f; beamPush.color[1] = 0.5f; beamPush.color[2] = 1.0f; beamPush.color[3] = 1.0f;

        vkCmdPushConstants(cmd, state.beamPipelineLayout,
            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
            0, sizeof(beamPush), &beamPush);
        
        vkCmdDraw(cmd, 2, 1, 0, 0); // 2 vertices para a linha
    }
}

void RecordAndSubmitQuad(
    AppState& state, VkFramebuffer framebuffer, VkExtent2D extent, const Mat4& mvp,
    const Mat4& proj, const Mat4& view, XrVector3f headPos) {
    VkCommandBuffer cmd = state.vkCommandBuffer;
    VKR(vkResetCommandBuffer(cmd, 0));

    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VKR(vkBeginCommandBuffer(cmd, &beginInfo));

    VkClearValue clearValue{};
    clearValue.color = {{0.02f, 0.02f, 0.05f, 1.0f}}; // preto quase puro — ambiente escuro de cinema

    VkRenderPassBeginInfo renderPassBegin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    renderPassBegin.renderPass = state.renderPass;
    renderPassBegin.framebuffer = framebuffer;
    renderPassBegin.renderArea.offset = {0, 0};
    renderPassBegin.renderArea.extent = extent;
    renderPassBegin.clearValueCount = 1;
    renderPassBegin.pClearValues = &clearValue;
    vkCmdBeginRenderPass(cmd, &renderPassBegin, VK_SUBPASS_CONTENTS_INLINE);

    VkViewport viewport{};
    viewport.width = static_cast<float>(extent.width);
    viewport.height = static_cast<float>(extent.height);
    viewport.minDepth = 0.0f;
    viewport.maxDepth = 1.0f;
    vkCmdSetViewport(cmd, 0, 1, &viewport);

    VkRect2D scissor{{0, 0}, extent};
    vkCmdSetScissor(cmd, 0, 1, &scissor);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, state.pipeline);

    VkDeviceSize offset = 0;
    vkCmdBindVertexBuffers(cmd, 0, 1, &state.quadVertexBuffer, &offset);

    // Amarelo/ambar: contraste claro com o azul de fundo, para o quad se
    // destacar na verificacao visual (docs/VULKAN-MIGRATION-PLAN.md,
    // criterio de sucesso do Estagio 2).
    QuadPushConstants pushConstants{};
    pushConstants.mvp = mvp;
    pushConstants.color[0] = 0.05f; // quad muito escuro, quase invisivel,
    pushConstants.color[1] = 0.05f; // para nao competir visualmente com
    pushConstants.color[2] = 0.08f; // os paineis de UI que flutuam na frente
    pushConstants.color[3] = 1.0f;
    vkCmdPushConstants(
        cmd, state.pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(pushConstants),
        &pushConstants);

    vkCmdDraw(cmd, 4, 1, 0, 0);

    DrawUiQuads(state, cmd, proj, view, headPos);

    vkCmdEndRenderPass(cmd);
    VKR(vkEndCommandBuffer(cmd));

    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmd;
    VKR(vkQueueSubmit(state.vkQueue, 1, &submitInfo, VK_NULL_HANDLE));
    VKR(vkQueueWaitIdle(state.vkQueue));
}

// Estagio 3: importa um AHardwareBuffer como VkImage via
// VK_ANDROID_external_memory_android_hardware_buffer, cria ImageView e
// aloca um descriptor set para ele. O resultado e armazenado no cache
// (state.videoImageCache) e o ponteiro para a entrada e retornado.
//
// Retorna nullptr se o buffer nao puder ser importado (erro de driver, etc.).
// Neste caso o chamador cai no fallback do quad solido.
VideoFrame* GetOrImportVideoFrame(AppState& state, AHardwareBuffer* buffer) {
    // Verificar cache primeiro (equivalente ao m_eglImageCache)
    auto it = state.videoImageCache.find(buffer);
    if (it != state.videoImageCache.end()) {
        return &it->second;
    }

    // Evicao LRU simplificada: remover a entrada mais antiga se o cache
    // atingiu o limite (equivalente a vr_player_app.cpp:1393-1396).
    if (state.videoImageCache.size() >= kVideoImageCacheLimit) {
        auto oldest = state.videoImageCache.begin();
        VideoFrame& oldFrame = oldest->second;
        if (oldFrame.descriptorSet != VK_NULL_HANDLE) {
            vkFreeDescriptorSets(
                state.vkDevice, state.videoDescriptorPool, 1, &oldFrame.descriptorSet);
        }
        if (oldFrame.imageView != VK_NULL_HANDLE) {
            vkDestroyImageView(state.vkDevice, oldFrame.imageView, nullptr);
        }
        if (oldFrame.image != VK_NULL_HANDLE) {
            vkDestroyImage(state.vkDevice, oldFrame.image, nullptr);
        }
        if (oldFrame.memory != VK_NULL_HANDLE) {
            vkFreeMemory(state.vkDevice, oldFrame.memory, nullptr);
        }
        state.videoImageCache.erase(oldest);
        LOGI("Cache de video: evicao de entrada antiga");
    }

    // Consultar propriedades do AHardwareBuffer para Vulkan
    auto pfnGetProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(state.vkDevice, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (!pfnGetProperties) {
        LOGE("vkGetAndroidHardwareBufferPropertiesANDROID nao disponivel");
        return nullptr;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID hwbProps{
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
    hwbProps.pNext = &formatProps;

    VkResult probeResult = pfnGetProperties(state.vkDevice, buffer, &hwbProps);
    if (probeResult != VK_SUCCESS) {
        LOGE("vkGetAndroidHardwareBufferPropertiesANDROID falhou: %d", probeResult);
        return nullptr;
    }

    // Criar VkImage vinculada ao AHardwareBuffer externo
    VkExternalMemoryImageCreateInfo extMemInfo{
        VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    extMemInfo.handleTypes =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkExternalFormatANDROID extFormat{VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID};
    extFormat.pNext = &extMemInfo;
    extFormat.externalFormat = formatProps.externalFormat;

    // Consultar dimensoes do AHardwareBuffer
    AHardwareBuffer_Desc hwbDesc{};
    AHardwareBuffer_describe(buffer, &hwbDesc);

    VkImageCreateInfo imageCreateInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imageCreateInfo.pNext = &extFormat;
    imageCreateInfo.imageType = VK_IMAGE_TYPE_2D;
    imageCreateInfo.format = VK_FORMAT_UNDEFINED; // formato externo
    imageCreateInfo.extent = {hwbDesc.width, hwbDesc.height, 1};
    imageCreateInfo.mipLevels = 1;
    imageCreateInfo.arrayLayers = 1;
    imageCreateInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageCreateInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageCreateInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    imageCreateInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageCreateInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VideoFrame frame{};
    VkResult createResult =
        vkCreateImage(state.vkDevice, &imageCreateInfo, nullptr, &frame.image);
    if (createResult != VK_SUCCESS) {
        LOGE("vkCreateImage para AHardwareBuffer falhou: %d", createResult);
        return nullptr;
    }

    // Alocar memoria e vincular ao AHardwareBuffer via import
    auto pfnImport = reinterpret_cast<PFN_vkGetMemoryAndroidHardwareBufferANDROID>(
        vkGetDeviceProcAddr(state.vkDevice, "vkGetMemoryAndroidHardwareBufferANDROID"));
    (void)pfnImport; // usada indiretamente via VkImportAndroidHardwareBufferInfoANDROID

    VkImportAndroidHardwareBufferInfoANDROID importInfo{
        VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    importInfo.buffer = buffer;

    VkMemoryDedicatedAllocateInfo dedicatedAlloc{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicatedAlloc.pNext = &importInfo;
    dedicatedAlloc.image = frame.image;

    VkMemoryAllocateInfo memAllocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    memAllocInfo.pNext = &dedicatedAlloc;
    memAllocInfo.allocationSize = hwbProps.allocationSize;
    // VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT: o AHardwareBuffer ja vive na GPU
    memAllocInfo.memoryTypeIndex =
        FindMemoryType(state, hwbProps.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

    VkResult memResult =
        vkAllocateMemory(state.vkDevice, &memAllocInfo, nullptr, &frame.memory);
    if (memResult != VK_SUCCESS) {
        LOGE("vkAllocateMemory para AHardwareBuffer falhou: %d", memResult);
        vkDestroyImage(state.vkDevice, frame.image, nullptr);
        return nullptr;
    }

    VkBindImageMemoryInfo bindInfo{VK_STRUCTURE_TYPE_BIND_IMAGE_MEMORY_INFO};
    bindInfo.image = frame.image;
    bindInfo.memory = frame.memory;
    bindInfo.memoryOffset = 0;

    auto pfnBindImageMemory2 = reinterpret_cast<PFN_vkBindImageMemory2KHR>(
        vkGetDeviceProcAddr(state.vkDevice, "vkBindImageMemory2KHR"));
    if (!pfnBindImageMemory2) {
        LOGE("vkBindImageMemory2KHR nao disponivel");
        vkFreeMemory(state.vkDevice, frame.memory, nullptr);
        vkDestroyImage(state.vkDevice, frame.image, nullptr);
        return nullptr;
    }
    VkResult bindResult = pfnBindImageMemory2(state.vkDevice, 1, &bindInfo);
    if (bindResult != VK_SUCCESS) {
        LOGE("vkBindImageMemory2KHR falhou: %d", bindResult);
        vkFreeMemory(state.vkDevice, frame.memory, nullptr);
        vkDestroyImage(state.vkDevice, frame.image, nullptr);
        return nullptr;
    }

    // Criar VkImageView com a YcbcrConversion embutida
    VkSamplerYcbcrConversionInfo viewConvInfo{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
    viewConvInfo.conversion = state.ycbcrConversion;

    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.pNext = &viewConvInfo;
    viewInfo.image = frame.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_UNDEFINED; // deve coincidir com a imagem
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;

    VkResult viewResult =
        vkCreateImageView(state.vkDevice, &viewInfo, nullptr, &frame.imageView);
    if (viewResult != VK_SUCCESS) {
        LOGE("vkCreateImageView para video falhou: %d", viewResult);
        vkFreeMemory(state.vkDevice, frame.memory, nullptr);
        vkDestroyImage(state.vkDevice, frame.image, nullptr);
        return nullptr;
    }

    // Alocar descriptor set e atualizar com a ImageView
    VkDescriptorSetAllocateInfo dsAllocInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dsAllocInfo.descriptorPool = state.videoDescriptorPool;
    dsAllocInfo.descriptorSetCount = 1;
    dsAllocInfo.pSetLayouts = &state.videoDescriptorSetLayout;
    VkResult dsResult =
        vkAllocateDescriptorSets(state.vkDevice, &dsAllocInfo, &frame.descriptorSet);
    if (dsResult != VK_SUCCESS) {
        LOGE("vkAllocateDescriptorSets para video falhou: %d", dsResult);
        vkDestroyImageView(state.vkDevice, frame.imageView, nullptr);
        vkFreeMemory(state.vkDevice, frame.memory, nullptr);
        vkDestroyImage(state.vkDevice, frame.image, nullptr);
        return nullptr;
    }

    VkDescriptorImageInfo dsImageInfo{};
    // O sampler e imutavel no layout — nao precisa ser passado aqui.
    dsImageInfo.imageView = frame.imageView;
    dsImageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkWriteDescriptorSet writeDs{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    writeDs.dstSet = frame.descriptorSet;
    writeDs.dstBinding = 0;
    writeDs.descriptorCount = 1;
    writeDs.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writeDs.pImageInfo = &dsImageInfo;
    vkUpdateDescriptorSets(state.vkDevice, 1, &writeDs, 0, nullptr);

    LOGI("Cache de video: importado AHardwareBuffer %p (%ux%u)",
         static_cast<void*>(buffer), hwbDesc.width, hwbDesc.height);

    auto [inserted, ok] = state.videoImageCache.emplace(buffer, frame);
    (void)ok;
    return &inserted->second;
}

// Estagio 3: submete o frame de video para a swapchain via o pipeline de
// textura YCbCr. Se nao houver frame disponivel (activeVideoFrame == nullptr)
// cai no RecordAndSubmitQuad (fallback de Estagio 2).
void RecordAndSubmitVideo(
    AppState& state, VkFramebuffer framebuffer, VkExtent2D extent,
    const Mat4& mvp, const Mat4& proj, const Mat4& view, XrVector3f headPos, VideoFrame* videoFrame) {
    VkCommandBuffer cmd = state.vkCommandBuffer;
    VKR(vkResetCommandBuffer(cmd, 0));

    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VKR(vkBeginCommandBuffer(cmd, &beginInfo));

    // Transicionar a imagem de video para SHADER_READ_ONLY antes do render pass.
    // O AHardwareBuffer e preenchido pelo MediaCodec fora da fila Vulkan;
    // a barreira garante visibilidade para o fragment shader.
    VkImageMemoryBarrier imgBarrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    imgBarrier.srcAccessMask = VK_ACCESS_NONE;
    imgBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    imgBarrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imgBarrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    imgBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    imgBarrier.dstQueueFamilyIndex = state.vkQueueFamilyIndex;
    imgBarrier.image = videoFrame->image;
    imgBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    imgBarrier.subresourceRange.levelCount = 1;
    imgBarrier.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(
        cmd,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &imgBarrier);

    VkClearValue clearValue{};
    clearValue.color = {{0.0f, 0.0f, 0.0f, 1.0f}}; // preto ao redor do video

    VkRenderPassBeginInfo renderPassBegin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    renderPassBegin.renderPass = state.renderPass;
    renderPassBegin.framebuffer = framebuffer;
    renderPassBegin.renderArea.offset = {0, 0};
    renderPassBegin.renderArea.extent = extent;
    renderPassBegin.clearValueCount = 1;
    renderPassBegin.pClearValues = &clearValue;
    vkCmdBeginRenderPass(cmd, &renderPassBegin, VK_SUBPASS_CONTENTS_INLINE);

    VkViewport viewport{};
    viewport.width = static_cast<float>(extent.width);
    viewport.height = static_cast<float>(extent.height);
    viewport.minDepth = 0.0f;
    viewport.maxDepth = 1.0f;
    vkCmdSetViewport(cmd, 0, 1, &viewport);

    VkRect2D scissor{{0, 0}, extent};
    vkCmdSetScissor(cmd, 0, 1, &scissor);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, state.videoPipeline);

    VkDeviceSize offset = 0;
    vkCmdBindVertexBuffers(cmd, 0, 1, &state.videoVertexBuffer, &offset);

    vkCmdBindDescriptorSets(
        cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
        state.videoPipelineLayout, 0, 1, &videoFrame->descriptorSet, 0, nullptr);

    VideoPushConstants pc{};
    pc.mvp = mvp;
    vkCmdPushConstants(
        cmd, state.videoPipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,
        0, sizeof(pc), &pc);

    vkCmdDraw(cmd, 4, 1, 0, 0);

    DrawUiQuads(state, cmd, proj, view, headPos);

    vkCmdEndRenderPass(cmd);
    VKR(vkEndCommandBuffer(cmd));

    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmd;
    VKR(vkQueueSubmit(state.vkQueue, 1, &submitInfo, VK_NULL_HANDLE));
    VKR(vkQueueWaitIdle(state.vkQueue));
}

// Estagio 3: atualiza o frame de video ativo a partir do bridge Rust.
// Chamado uma vez por loop de frame, antes de RenderFrame.
// Equivale ao bloco vr_player_app.cpp:1354-1400 (m_eglImageCache).
void UpdateVideoFrame(AppState& state) {
    AHardwareBuffer* buffer = get_current_video_frame();

    if (buffer == nullptr) {
        // Sem frame: limpar estado de video ativo
        if (state.lastVideoBuffer != nullptr) {
            state.lastVideoBuffer = nullptr;
            state.activeVideoFrame = nullptr;
            LOGI("Video: sem frame disponivel, usando fallback quad solido");
        }
        return;
    }

    if (buffer == state.lastVideoBuffer) {
        // Mesmo buffer do frame anterior: reusar sem reimportar
        return;
    }

    state.lastVideoBuffer = buffer;
    state.activeVideoFrame = GetOrImportVideoFrame(state, buffer);
}

void RenderFrame(AppState& state) {
    XrFrameWaitInfo waitFrameInfo{XR_TYPE_FRAME_WAIT_INFO};
    XrFrameState frameState{XR_TYPE_FRAME_STATE};
    OXR(xrWaitFrame(state.session, &waitFrameInfo, &frameState));

    XrFrameBeginInfo beginFrameInfo{XR_TYPE_FRAME_BEGIN_INFO};
    OXR(xrBeginFrame(state.session, &beginFrameInfo));

    std::array<XrCompositionLayerProjectionView, kEyeCount> projectionViews{};
    const bool shouldSubmitLayer = frameState.shouldRender;

    if (shouldSubmitLayer) {
        std::array<XrView, kEyeCount> views{};
        for (auto& v : views) v.type = XR_TYPE_VIEW;

        XrViewLocateInfo locateInfo{XR_TYPE_VIEW_LOCATE_INFO};
        locateInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        locateInfo.displayTime = frameState.predictedDisplayTime;
        locateInfo.space = state.localSpace;

        XrViewState viewState{XR_TYPE_VIEW_STATE};
        uint32_t viewCountOutput = 0;
        OXR(xrLocateViews(state.session, &locateInfo, &viewState, kEyeCount, &viewCountOutput, views.data()));

        // Estagio 4/5: Processar interacoes apos obtermos a posicao da cabeca
        UpdateInteraction(state, frameState.predictedDisplayTime, views[0].pose.position);

        // Posicao/escala da tela virtual: identica ao caminho GLES
        // (vr_player_app.cpp:1588 m_screenPosition = {0, 1.5, -2},
        //  m_screenScale = {1.6, 0.9}) para facilitar comparacao visual.
        const Mat4 screenModel = Mat4Multiply(
            Mat4Translation(0.0f, 1.5f, -2.0f),
            Mat4Scale(1.6f, 0.9f, 1.0f));

        // Estagio 5: lerencia do ScreenMode a cada frame (mesmo que o GLES:
        // vr_player_app.cpp:993 "m_screenMode = static_cast<ScreenMode>(get_3d_mode())")
        state.screenMode = static_cast<ScreenMode>(get_3d_mode());
        const bool sphereMode = IsSphereMode(state.screenMode);
        const bool stereoFlat = IsFlatStereoMode(state.screenMode);

        for (int eye = 0; eye < kEyeCount; eye++) {
            EyeSwapchain& eyeChain = state.eyes[eye];

            XrSwapchainImageAcquireInfo acquireInfo{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
            uint32_t imageIndex = 0;
            OXR(xrAcquireSwapchainImage(eyeChain.handle, &acquireInfo, &imageIndex));

            XrSwapchainImageWaitInfo waitInfo{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
            waitInfo.timeout = XR_INFINITE_DURATION;
            OXR(xrWaitSwapchainImage(eyeChain.handle, &waitInfo));

            const Mat4 view = Mat4RigidInverse(Mat4FromXrPose(views[eye].pose));
            const Mat4 proj = Mat4ProjectionFromFov(views[eye].fov, 0.05f, 100.0f);
            const Mat4 mvp  = Mat4Multiply(Mat4Multiply(proj, view), screenModel);

            VkExtent2D extent{static_cast<uint32_t>(eyeChain.width), static_cast<uint32_t>(eyeChain.height)};
            VkFramebuffer fb = eyeChain.framebuffers[imageIndex];

            // ---------------------------------------------------------------
            // Despacho de video por ScreenMode (Estagio 3/5)
            // Estagio 5: ScreenMode SBS/OU/Sphere: usa pipeline estereo com
            // os parametros de olho no push constant.
            // Estagio 3: Flat2D com frame de video: usa pipeline de video.
            // Fallback: quad solido (Estagio 2) quando sem frame.
            // ---------------------------------------------------------------
            if (state.activeVideoFrame != nullptr) {
                if (sphereMode || stereoFlat) {
                    // Estagio 5: pipeline estereo/esfera
                    // Para a esfera: MVP = proj * view (sem model — a esfera
                    // acompanha a translacao mas nao a escala da tela plana)
                    const Mat4 sphereMvp = Mat4Multiply(proj, view);
                    StereoParams sp = GetStereoParams(state.screenMode, eye);

                    VkCommandBuffer cmd = state.vkCommandBuffer;
                    VKR(vkResetCommandBuffer(cmd, 0));
                    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
                    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
                    VKR(vkBeginCommandBuffer(cmd, &beginInfo));

                    // Barreira de pipeline para o frame de video externo
                    VkImageMemoryBarrier imgBarrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
                    imgBarrier.srcAccessMask = VK_ACCESS_NONE;
                    imgBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                    imgBarrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                    imgBarrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                    imgBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
                    imgBarrier.dstQueueFamilyIndex = state.vkQueueFamilyIndex;
                    imgBarrier.image = state.activeVideoFrame->image;
                    imgBarrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &imgBarrier);

                    VkClearValue clearValue{};
                    VkRenderPassBeginInfo rpBegin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
                    rpBegin.renderPass = state.renderPass;
                    rpBegin.framebuffer = fb;
                    rpBegin.renderArea.extent = extent;
                    rpBegin.clearValueCount = 1; rpBegin.pClearValues = &clearValue;
                    vkCmdBeginRenderPass(cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);

                    VkViewport vp{0, 0, (float)extent.width, (float)extent.height, 0.0f, 1.0f};
                    vkCmdSetViewport(cmd, 0, 1, &vp);
                    VkRect2D scissor{{0,0}, extent};
                    vkCmdSetScissor(cmd, 0, 1, &scissor);

                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, state.stereoPipeline);

                    StereoPushConstants spc{};
                    spc.mvp         = sphereMode ? sphereMvp : mvp;
                    spc.eyeIndex    = sp.eyeIndex;
                    spc.swapEyes    = sp.swapEyes;
                    spc.stereoLayout = sp.stereoLayout;
                    spc.polar180    = sp.polar180;
                    vkCmdPushConstants(cmd, state.stereoPipelineLayout,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                        0, sizeof(spc), &spc);
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        state.stereoPipelineLayout, 0, 1,
                        &state.activeVideoFrame->descriptorSet, 0, nullptr);

                    if (sphereMode) {
                        // Index draw para a geometria da esfera
                        VkDeviceSize offset = 0;
                        vkCmdBindVertexBuffers(cmd, 0, 1, &state.sphereVertexBuffer, &offset);
                        vkCmdBindIndexBuffer(cmd, state.sphereIndexBuffer, 0, VK_INDEX_TYPE_UINT32);
                        vkCmdDrawIndexed(cmd, state.sphereIndexCount, 1, 0, 0, 0);
                    } else {
                        // Quad plano SBS/OU: mesmo vertex buffer do video
                        VkDeviceSize offset = 0;
                        vkCmdBindVertexBuffers(cmd, 0, 1, &state.videoVertexBuffer, &offset);
                        vkCmdDraw(cmd, 4, 1, 0, 0);
                    }

                    DrawUiQuads(state, cmd, proj, view, views[eye].pose.position);

                    vkCmdEndRenderPass(cmd);
                    VKR(vkEndCommandBuffer(cmd));
                    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
                    submitInfo.commandBufferCount = 1; submitInfo.pCommandBuffers = &cmd;
                    VKR(vkQueueSubmit(state.vkQueue, 1, &submitInfo, VK_NULL_HANDLE));
                    VKR(vkQueueWaitIdle(state.vkQueue));
                } else {
                    // Estagio 3: Flat2D com textura de video
                    RecordAndSubmitVideo(state, fb, extent, mvp, proj, view, views[eye].pose.position, state.activeVideoFrame);
                }
            } else {
                // Fallback: quad solido (Estagio 2)
                RecordAndSubmitQuad(state, fb, extent, mvp, proj, view, views[eye].pose.position);
            }

            XrSwapchainImageReleaseInfo releaseInfo{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
            OXR(xrReleaseSwapchainImage(eyeChain.handle, &releaseInfo));

            projectionViews[eye].type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
            projectionViews[eye].pose = views[eye].pose;
            projectionViews[eye].fov  = views[eye].fov;
            projectionViews[eye].subImage.swapchain = eyeChain.handle;
            projectionViews[eye].subImage.imageRect.offset = {0, 0};
            projectionViews[eye].subImage.imageRect.extent = {eyeChain.width, eyeChain.height};
        }
    }

    XrCompositionLayerProjection projectionLayer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    projectionLayer.space     = state.localSpace;
    projectionLayer.viewCount = kEyeCount;
    projectionLayer.views     = projectionViews.data();
    const XrCompositionLayerBaseHeader* layers[1] = {
        reinterpret_cast<const XrCompositionLayerBaseHeader*>(&projectionLayer)};

    XrFrameEndInfo endFrameInfo{XR_TYPE_FRAME_END_INFO};
    endFrameInfo.displayTime           = frameState.predictedDisplayTime;
    endFrameInfo.environmentBlendMode  = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    endFrameInfo.layerCount            = shouldSubmitLayer ? 1 : 0;
    endFrameInfo.layers                = shouldSubmitLayer ? layers : nullptr;
    OXR(xrEndFrame(state.session, &endFrameInfo));
}

void HandleAppCmd(android_app* app, int32_t cmd) {
    AppState& state = *reinterpret_cast<AppState*>(app->userData);
    switch (cmd) {
        case APP_CMD_RESUME:
            state.resumed = true;
            break;
        case APP_CMD_PAUSE:
            state.resumed = false;
            break;
        case APP_CMD_DESTROY:
            state.requestExit = true;
            break;
        default:
            break;
    }
}

// Mesma logica de timeout do ALooper que o OVRFW usa (ActivityMainLoopContext
// ::HandleOsEvents, XrApp.cpp:1453-1474): bloqueia indefinidamente enquanto a
// activity nao esta resumed/com sessao ativa, para nao girar a CPU a toa.
void PollAndroidEvents(AppState& state) {
    for (;;) {
        int events = 0;
        android_poll_source* source = nullptr;
        const int timeoutMs =
            (!state.resumed && !state.sessionRunning && state.app->destroyRequested == 0) ? -1 : 0;
        if (ALooper_pollOnce(timeoutMs, nullptr, &events, reinterpret_cast<void**>(&source)) < 0) {
            break;
        }
        if (source != nullptr) {
            source->process(state.app, source);
        }
    }
}

void PollXrEvents(AppState& state) {
    for (;;) {
        XrEventDataBuffer eventBuffer{XR_TYPE_EVENT_DATA_BUFFER};
        if (xrPollEvent(state.instance, &eventBuffer) != XR_SUCCESS) break;

        if (eventBuffer.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            const auto* event = reinterpret_cast<XrEventDataSessionStateChanged*>(&eventBuffer);
            LOGI("Estado da sessao OpenXR mudou para %d", event->state);

            switch (event->state) {
                case XR_SESSION_STATE_READY: {
                    XrSessionBeginInfo beginInfo{XR_TYPE_SESSION_BEGIN_INFO};
                    beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    OXR(xrBeginSession(state.session, &beginInfo));
                    AttachInputsToSession(state);
                    state.sessionRunning = true;
                    break;
                }
                case XR_SESSION_STATE_STOPPING:
                    OXR(xrEndSession(state.session));
                    state.sessionRunning = false;
                    break;
                case XR_SESSION_STATE_EXITING:
                case XR_SESSION_STATE_LOSS_PENDING:
                    state.requestExit = true;
                    break;
                default:
                    break;
            }
        }
    }
}

} // namespace

void android_main(android_app* app) {
    AppState state;
    state.app = app;
    app->userData = &state;
    app->onAppCmd = HandleAppCmd;

    InitializeOpenXrLoader(state);
    CreateXrInstance(state);

    XrSystemGetInfo systemGetInfo{XR_TYPE_SYSTEM_GET_INFO};
    systemGetInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    OXR(xrGetSystem(state.instance, &systemGetInfo, &state.systemId));

    CreateVulkanInstanceAndDevice(state);

    XrGraphicsBindingVulkanKHR graphicsBinding{XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR};
    graphicsBinding.instance = state.vkInstance;
    graphicsBinding.physicalDevice = state.vkPhysicalDevice;
    graphicsBinding.device = state.vkDevice;
    graphicsBinding.queueFamilyIndex = state.vkQueueFamilyIndex;
    graphicsBinding.queueIndex = 0;

    XrSessionCreateInfo sessionCreateInfo{XR_TYPE_SESSION_CREATE_INFO};
    sessionCreateInfo.next = &graphicsBinding;
    sessionCreateInfo.systemId = state.systemId;
    OXR(xrCreateSession(state.instance, &sessionCreateInfo, &state.session));
    SetupOpenXrInputs(state);

    CreateReferenceSpace(state);
    CreateSwapchains(state);
    CreateRenderPass(state);
    CreateFramebuffers(state);
    CreateGraphicsPipeline(state);
    CreateQuadVertexBuffer(state);
    CreateCommandResources(state);
    // Estagio 3: YCbCr + pipeline de video + vertex buffer de video
    CreateYcbcrAndVideoPipeline(state);
    CreateVideoVertexBuffer(state);
    // Estagio 4: pipeline de UI/controles (RGBA8888 + AImageReader)
    CreateUiPipeline(state, app);
    // Estagio 5: geometria de esfera, pipeline estereo, beam cursor
    CreateSphereGeometry(state);
    CreateStereoPipeline(state);
    CreateBeamResources(state);

    // O video e iniciado via nativePlayVideo (JNI) quando o usuario seleciona
    // um arquivo no painel de UI — identico ao caminho GLES.
    // Nao ha start_video_playback aqui para nao interferir com o fluxo normal.

    LOGI("Estagios 3-5 (textura video, UI, estereo/esfera, beam) inicializados — loop principal");

    while (app->destroyRequested == 0 && !state.requestExit) {
        PollAndroidEvents(state);
        PollXrEvents(state);

        if (app->destroyRequested != 0 || state.requestExit) break;
        if (!state.sessionRunning) continue;

        // Estagio 3: atualizar frame de video antes de renderizar
        UpdateVideoFrame(state);
        // Estagio 4: atualizar texturas de UI/controles a partir dos AImageReaders
        UpdateUiFrames(state);
        RenderFrame(state);
    }

    LOGI("Encerrando: destruindo sessao/recursos Vulkan");
    // Estagio 3: destruir cache de video antes de destruir sampler/conversion
    for (auto& [buf, frame] : state.videoImageCache) {
        if (frame.descriptorSet != VK_NULL_HANDLE) {
            vkFreeDescriptorSets(
                state.vkDevice, state.videoDescriptorPool, 1, &frame.descriptorSet);
        }
        if (frame.imageView != VK_NULL_HANDLE) {
            vkDestroyImageView(state.vkDevice, frame.imageView, nullptr);
        }
        if (frame.image != VK_NULL_HANDLE) {
            vkDestroyImage(state.vkDevice, frame.image, nullptr);
        }
        if (frame.memory != VK_NULL_HANDLE) {
            vkFreeMemory(state.vkDevice, frame.memory, nullptr);
        }
    }
    state.videoImageCache.clear();
    if (state.videoDescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state.vkDevice, state.videoDescriptorPool, nullptr);
    }
    if (state.videoPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state.vkDevice, state.videoPipeline, nullptr);
    }
    if (state.videoPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state.vkDevice, state.videoPipelineLayout, nullptr);
    }
    if (state.videoDescriptorSetLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state.vkDevice, state.videoDescriptorSetLayout, nullptr);
    }
    if (state.videoSampler != VK_NULL_HANDLE) {
        vkDestroySampler(state.vkDevice, state.videoSampler, nullptr);
    }
    if (state.ycbcrConversion != VK_NULL_HANDLE) {
        auto pfnDestroyYcbcrConversion =
            reinterpret_cast<PFN_vkDestroySamplerYcbcrConversion>(
                vkGetDeviceProcAddr(state.vkDevice, "vkDestroySamplerYcbcrConversion"));
        if (pfnDestroyYcbcrConversion) {
            pfnDestroyYcbcrConversion(state.vkDevice, state.ycbcrConversion, nullptr);
        }
    }
    if (state.videoVertexBuffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(state.vkDevice, state.videoVertexBuffer, nullptr);
    }
    if (state.videoVertexMemory != VK_NULL_HANDLE) {
        vkFreeMemory(state.vkDevice, state.videoVertexMemory, nullptr);
    }
    if (state.quadVertexBuffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(state.vkDevice, state.quadVertexBuffer, nullptr);
    }
    if (state.quadVertexMemory != VK_NULL_HANDLE) {
        vkFreeMemory(state.vkDevice, state.quadVertexMemory, nullptr);
    }
    if (state.pipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state.vkDevice, state.pipeline, nullptr);
    }
    if (state.pipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state.vkDevice, state.pipelineLayout, nullptr);
    }
    for (auto& eyeChain : state.eyes) {
        for (auto framebuffer : eyeChain.framebuffers) {
            vkDestroyFramebuffer(state.vkDevice, framebuffer, nullptr);
        }
        for (auto imageView : eyeChain.imageViews) {
            vkDestroyImageView(state.vkDevice, imageView, nullptr);
        }
    }
    if (state.renderPass != VK_NULL_HANDLE) {
        vkDestroyRenderPass(state.vkDevice, state.renderPass, nullptr);
    }
    if (state.vkCommandPool != VK_NULL_HANDLE) {
        vkDestroyCommandPool(state.vkDevice, state.vkCommandPool, nullptr);
    }
    for (auto& eye : state.eyes) {
        if (eye.handle != XR_NULL_HANDLE) xrDestroySwapchain(eye.handle);
    }
    if (state.localSpace != XR_NULL_HANDLE) xrDestroySpace(state.localSpace);
    if (state.session != XR_NULL_HANDLE) xrDestroySession(state.session);
    if (state.vkDevice != VK_NULL_HANDLE) vkDestroyDevice(state.vkDevice, nullptr);
    if (state.vkInstance != VK_NULL_HANDLE) vkDestroyInstance(state.vkInstance, nullptr);
    if (state.instance != XR_NULL_HANDLE) xrDestroyInstance(state.instance);
}
