// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <cstring>
#include <filesystem>

#include "common/dynamic_library.h"
#include "common/logging.h"
#include "common/symbiosis/driver_broker.h"
#include "common/symbiosis/symbiosis_log.h"

#if defined(__ANDROID__) || defined(__unix__) || defined(__APPLE__)
#define SYMBIOSIS_HAS_DLOPEN 1
#include <dlfcn.h>
#endif

#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif

#ifdef ARCHITECTURE_arm64
#include <adrenotools/driver.h>
#endif

namespace Symbiosis {

namespace {

/// Minimal Vulkan typedefs. We deliberately avoid including vulkan.h here so
/// that common/ does not gain a dependency on video_core's Vulkan headers.
using VkInstanceHandle = void*;
using PFN_vkVoidFunction_ = void (*)();
using PFN_vkGetInstanceProcAddr_ = PFN_vkVoidFunction_ (*)(VkInstanceHandle, const char*);

/// Entry points whose presence we treat as evidence of a capability.
///
/// Each capability lists every spelling we accept. This matters enormously on
/// old Mali blobs, which frequently export *only* the KHR/EXT name of a
/// function that was later promoted to core: probing for the core name alone
/// would wrongly conclude the driver cannot do timeline semaphores at all.
struct CapabilityProbe {
    Capability cap;
    std::array<const char*, 3> symbols;
};

/// Probing by symbol presence is crude but it is the only thing that works
/// across ABI boundaries: we cannot call vkGetPhysicalDeviceFeatures2 on a
/// driver whose struct layout we do not trust yet.
constexpr std::array<CapabilityProbe, 6> kProbes{{
    {Capability::Timeline,
     {"vkGetSemaphoreCounterValue", "vkGetSemaphoreCounterValueKHR", nullptr}},
    {Capability::BufferDeviceAddress,
     {"vkGetBufferDeviceAddress", "vkGetBufferDeviceAddressKHR",
      "vkGetBufferDeviceAddressEXT"}},
    {Capability::ExtendedDynamicState,
     {"vkCmdSetCullMode", "vkCmdSetCullModeEXT", nullptr}},
    {Capability::PushDescriptor,
     {"vkCmdPushDescriptorSetKHR", "vkCmdPushDescriptorSet", nullptr}},
    {Capability::HostImageCopy,
     {"vkCopyMemoryToImageEXT", "vkCopyMemoryToImage", nullptr}},
    {Capability::CoreDispatch, {"vkGetInstanceProcAddr", nullptr, nullptr}},
}};

/// Heuristic: infer the GPU family from a driver/soname string.
GpuFamily GuessFamily(std::string_view text) {
    const auto contains = [&](std::string_view needle) {
        return text.find(needle) != std::string_view::npos;
    };
    if (contains("mali") || contains("bifrost") || contains("valhall")) {
        return GpuFamily::Mali;
    }
    if (contains("immortalis")) {
        return GpuFamily::Immortalis;
    }
    if (contains("turnip") || contains("freedreno") || contains("adreno")) {
        return GpuFamily::Adreno;
    }
    if (contains("panvk") || contains("panfrost")) {
        return GpuFamily::Mali;
    }
    if (contains("powervr") || contains("rogue")) {
        return GpuFamily::PowerVR;
    }
    if (contains("xclipse") || contains("amdgpu")) {
        return GpuFamily::Xclipse;
    }
    if (contains("swiftshader") || contains("lavapipe")) {
        return GpuFamily::Software;
    }
    return GpuFamily::Unknown;
}

DriverOrigin GuessOrigin(std::string_view filename) {
    const auto contains = [&](std::string_view needle) {
        return filename.find(needle) != std::string_view::npos;
    };
    if (contains("turnip") || contains("freedreno")) {
        return DriverOrigin::Turnip;
    }
    if (contains("panvk") || contains("panfrost")) {
        return DriverOrigin::PanVK;
    }
    return DriverOrigin::UserBlob;
}

/// Detect the host GPU family once, from the system driver's own strings.
GpuFamily DetectHostFamily() {
#ifdef __ANDROID__
    // Read the property directly rather than shelling out to `getprop`.
    // popen() forks the whole process, which on Android is both slow and a
    // genuine hazard: forking a multi-threaded process that holds allocator
    // locks can deadlock the child, and some vendor images restrict exec from
    // an app sandbox entirely.
    char value[PROP_VALUE_MAX]{};
    if (__system_property_get("ro.hardware.vulkan", value) > 0) {
        std::string lowered{value};
        std::transform(lowered.begin(), lowered.end(), lowered.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        const auto family = GuessFamily(lowered);
        if (family != GpuFamily::Unknown) {
            return family;
        }
    }
    // Fall back to the GPU model string some vendors expose instead.
    char model[PROP_VALUE_MAX]{};
    if (__system_property_get("ro.hardware.egl", model) > 0) {
        std::string lowered{model};
        std::transform(lowered.begin(), lowered.end(), lowered.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        return GuessFamily(lowered);
    }
#endif
    return GpuFamily::Unknown;
}

} // Anonymous namespace

DriverBroker::DriverBroker() = default;

DriverBroker::~DriverBroker() = default;

void DriverBroker::SetHostFamily(GpuFamily family) {
    std::scoped_lock lock{mutex};
    host_family = family;
}

void DriverBroker::SetMode(SymbiosisMode new_mode) {
    std::scoped_lock lock{mutex};
    mode = new_mode;
}

Provider* DriverBroker::AddCandidate(std::string name, std::string path, DriverOrigin origin,
                                     void* handle) {
    if (handle == nullptr) {
        Provider dead{};
        dead.name = std::move(name);
        dead.path = std::move(path);
        dead.origin = origin;
        dead.health = Health::Dead;
        providers.push_back(std::move(dead));
        LOG_WARNING(Common, "[Symbiosis] candidate '{}' failed to open", providers.back().name);
        return nullptr;
    }

    Provider provider{};
    provider.name = std::move(name);
    provider.path = std::move(path);
    provider.origin = origin;
    provider.raw_handle = handle;
    provider.library = std::make_shared<Common::DynamicLibrary>(handle);
    providers.push_back(std::move(provider));
    return &providers.back();
}

void DriverBroker::Probe(Provider& provider) {
    if (provider.library == nullptr || !provider.library->IsOpen()) {
        provider.health = Health::Dead;
        return;
    }

    // A driver that cannot even give us vkGetInstanceProcAddr is useless to
    // everybody; mark it dead rather than letting it poison routing.
    PFN_vkGetInstanceProcAddr_ gipa{};
    if (!provider.library->GetSymbol("vkGetInstanceProcAddr", &gipa) || gipa == nullptr) {
        provider.health = Health::Dead;
        LOG_WARNING(Common, "[Symbiosis] '{}' has no vkGetInstanceProcAddr", provider.name);
        return;
    }

    provider.caps.Clear();
    for (const auto& probe : kProbes) {
        for (const char* symbol : probe.symbols) {
            if (symbol == nullptr) {
                continue;
            }
            // Symbol presence is the only trustworthy signal at this stage.
            //
            // Note: we deliberately do NOT probe via vkGetInstanceProcAddr with
            // a null instance. The Vulkan spec requires that call to return
            // NULL for everything except a handful of global commands, so it
            // would report "unsupported" for every device-level function on a
            // conformant driver -- and non-conformant blobs return garbage
            // instead of NULL, which is even worse. dlsym gives us the truth.
            if (provider.library->GetSymbolAddress(symbol) != nullptr) {
                provider.caps.Set(probe.cap);
                break;
            }
        }
    }

    // Compressed-texture support cannot be probed by symbol presence; it is a
    // format property. Assume the conservative answer and let the texture cache
    // upgrade us later via ReportCapability-style feedback.
    provider.caps.Set(Capability::AstcDecode, false);
    provider.caps.Set(Capability::Etc2Decode, provider.family != GpuFamily::Software);

    provider.health = provider.caps.Has(Capability::CoreDispatch) ? Health::Good : Health::Degraded;

    LOG_INFO(Common, "[Symbiosis] probed '{}' origin={} family={} caps=0x{:x} health={}",
             provider.name, ToString(provider.origin), ToString(provider.family),
             provider.caps.Raw(), ToString(provider.health));
}

void DriverBroker::ScanDirectory(const std::string& dir) {
    if (dir.empty()) {
        return;
    }
    std::error_code ec;
    if (!std::filesystem::is_directory(dir, ec)) {
        return;
    }

    for (const auto& entry : std::filesystem::directory_iterator{dir, ec}) {
        if (ec) {
            break;
        }
        if (!entry.is_regular_file(ec)) {
            continue;
        }
        const auto path = entry.path();
        if (path.extension() != ".so") {
            continue;
        }

        std::string filename = path.filename().string();
        std::string lowered = filename;
        std::transform(lowered.begin(), lowered.end(), lowered.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });

        const DriverOrigin origin = GuessOrigin(lowered);
        const GpuFamily family = GuessFamily(lowered);

        // A Turnip blob on a Mali device can never produce a device. In Safe
        // and Cooperative modes we refuse to even dlopen it, because some
        // builds abort in their constructor on unsupported hardware.
        if (mode != SymbiosisMode::Desperate && host_family == GpuFamily::Mali &&
            family == GpuFamily::Adreno) {
            LOG_INFO(Common, "[Symbiosis] skipping Adreno-only blob '{}' on Mali host", filename);
            continue;
        }

#ifdef SYMBIOSIS_HAS_DLOPEN
        void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            // A blob that will not load at all is still recorded, so the user
            // can see *why* their driver did nothing.
            LOG_WARNING(Common, "[Symbiosis] dlopen('{}') failed: {}", filename,
                        dlerror() != nullptr ? dlerror() : "unknown");
        }
#else
        void* handle = nullptr;
#endif
        auto* provider = AddCandidate(filename, path.string(), origin, handle);
        if (provider != nullptr) {
            provider->family = family;
            Probe(*provider);
        }
    }
}

std::size_t DriverBroker::Discover(const BrokerPaths& paths) {
    std::scoped_lock lock{mutex};
    if (discovered) {
        return static_cast<std::size_t>(
            std::count_if(providers.begin(), providers.end(),
                          [](const Provider& p) { return p.Usable(); }));
    }
    discovered = true;
    providers.reserve(8);

    if (host_family == GpuFamily::Unknown) {
        host_family = DetectHostFamily();
    }
    LOG_INFO(Common, "[Symbiosis] host GPU family: {}, mode: {}", ToString(host_family),
             ToString(mode));

#if defined(__ANDROID__) && defined(ARCHITECTURE_arm64)
    // 1. The user's explicitly selected custom driver, loaded through
    //    adrenotools so that its file accesses get redirected correctly.
    if (!paths.custom_driver_name.empty()) {
        void* handle = adrenotools_open_libvulkan(
            RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, paths.hook_lib_dir.c_str(),
            paths.custom_driver_dir.c_str(), paths.custom_driver_name.c_str(),
            paths.file_redirect_dir.empty() ? nullptr : paths.file_redirect_dir.c_str(), nullptr);
        auto* provider =
            AddCandidate(paths.custom_driver_name, paths.custom_driver_dir, DriverOrigin::UserBlob,
                         handle);
        if (provider != nullptr) {
            provider->family = GuessFamily(paths.custom_driver_name);
            Probe(*provider);
        }
    }

    // 2. The system driver. This one is always tried, even if a custom driver
    //    loaded fine, because it is the most likely to be *stable* and makes an
    //    excellent fallback donor for entry points the custom blob lacks.
    {
        void* handle = adrenotools_open_libvulkan(
            RTLD_NOW, 0, nullptr, paths.hook_lib_dir.c_str(), nullptr, nullptr,
            paths.file_redirect_dir.empty() ? nullptr : paths.file_redirect_dir.c_str(), nullptr);
        auto* provider =
            AddCandidate("system libvulkan", "/system/lib64/libvulkan.so", DriverOrigin::System,
                         handle);
        if (provider != nullptr) {
            provider->family = host_family;
            Probe(*provider);
        }
    }
#endif

#if defined(__ANDROID__)
    // 3. Plain system loader, in case adrenotools' hooking failed outright.
    if (std::none_of(providers.begin(), providers.end(),
                     [](const Provider& p) { return p.Usable(); })) {
        void* handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        auto* provider = AddCandidate("libvulkan.so (direct)", "libvulkan.so",
                                      DriverOrigin::System, handle);
        if (provider != nullptr) {
            provider->family = host_family;
            Probe(*provider);
        }
    }
#endif

    // 4. Anything the user dropped into the Symbiosis pool.
    ScanDirectory(paths.extra_scan_dir);

    Rebalance();

    const auto usable = static_cast<std::size_t>(
        std::count_if(providers.begin(), providers.end(),
                      [](const Provider& p) { return p.Usable(); }));
    LOG_INFO(Common, "[Symbiosis] discovery complete: {} usable of {} candidates", usable,
             providers.size());
    LogInfo(LogArea::Driver, "discovery: " + std::to_string(usable) + " usable of " +
                                 std::to_string(providers.size()) + " candidate(s)");
    return usable;
}

void DriverBroker::UpdateObservedCapabilities(Provider* provider, CapabilitySet observed,
                                              u32 api_version) {
    if (provider == nullptr) {
        return;
    }
    std::scoped_lock lock{mutex};

    const u64 before = provider->caps.Raw();
    provider->caps = observed;
    provider->api_version = api_version;

    if (before != observed.Raw()) {
        LOG_INFO(Common,
                 "[Symbiosis] '{}' capabilities corrected from device query: 0x{:x} -> 0x{:x}",
                 provider->name, before, observed.Raw());
    }
    Rebalance();
}

void DriverBroker::Rebalance() {
    routing.fill(nullptr);

    // Score providers so that stability dominates feature count. A Degraded
    // provider is only chosen when no Good provider can do the job.
    const auto score = [](const Provider& p) -> int {
        int value = 0;
        switch (p.health) {
        case Health::Good:
            value += 1000;
            break;
        case Health::Degraded:
            value += 400;
            break;
        default:
            return -1;
        }
        // Prefer the system driver as a donor: it is the one the kernel driver
        // was actually shipped with.
        if (p.origin == DriverOrigin::System) {
            value += 120;
        }
        value -= static_cast<int>(p.fault_count) * 50;
        return value;
    };

    for (std::size_t i = 0; i < kCapabilityCount; ++i) {
        const auto cap = static_cast<Capability>(i);
        int best_score = -1;
        Provider* best = nullptr;
        for (auto& provider : providers) {
            if (!provider.Usable() || !provider.caps.Has(cap)) {
                continue;
            }
            const int provider_score = score(provider);
            if (provider_score > best_score) {
                best_score = provider_score;
                best = &provider;
            }
        }
        routing[i] = best;
    }

    // Elect the primary: the healthiest CoreDispatch provider.
    for (auto& provider : providers) {
        provider.is_primary = false;
    }
    if (auto* core = routing[static_cast<std::size_t>(Capability::CoreDispatch)]) {
        core->is_primary = true;
    }
}

Provider* DriverBroker::Primary() {
    std::scoped_lock lock{mutex};
    for (auto& provider : providers) {
        if (provider.is_primary) {
            return &provider;
        }
    }
    // Nobody was elected (e.g. Rebalance never ran); fall back to the first
    // usable provider so the caller still gets something.
    for (auto& provider : providers) {
        if (provider.Usable()) {
            return &provider;
        }
    }
    return nullptr;
}

Provider* DriverBroker::ProviderFor(Capability cap) {
    std::scoped_lock lock{mutex};
    const auto index = static_cast<std::size_t>(cap);
    if (index >= kCapabilityCount) {
        return nullptr;
    }
    return routing[index];
}

bool DriverBroker::Supports(Capability cap) {
    return ProviderFor(cap) != nullptr;
}

void DriverBroker::ReportFault(Provider* provider, std::string_view reason) {
    if (provider == nullptr) {
        return;
    }
    std::scoped_lock lock{mutex};
    provider->fault_count++;
    LOG_WARNING(Common, "[Symbiosis] fault #{} in '{}': {}", provider->fault_count, provider->name,
                reason);

    if (provider->health == Health::Good) {
        provider->health = Health::Degraded;
    }
    if (provider->fault_count >= kQuarantineThreshold &&
        provider->health != Health::Quarantined) {
        provider->health = Health::Quarantined;
        LOG_ERROR(Common, "[Symbiosis] quarantining '{}' after {} faults; rerouting",
                  provider->name, provider->fault_count);
        LogError(LogArea::Driver, "quarantined '" + provider->name + "' after " +
                                      std::to_string(provider->fault_count) + " faults");
    }
    Rebalance();
}

std::string DriverBroker::DescribeTopology() const {
    std::scoped_lock lock{mutex};
    std::string out = "Symbiosis driver topology:\n";
    out += "  mode: ";
    out += ToString(mode);
    out += '\n';

    if (providers.empty()) {
        out += "  (no candidates discovered)\n";
        return out;
    }

    for (const auto& provider : providers) {
        out += "  - ";
        out += provider.name;
        out += " [";
        out += ToString(provider.origin);
        out += '/';
        out += ToString(provider.family);
        out += "] health=";
        out += ToString(provider.health);
        if (provider.is_primary) {
            out += " PRIMARY";
        }
        if (provider.fault_count > 0) {
            out += " faults=" + std::to_string(provider.fault_count);
        }
        out += '\n';
    }

    out += "  routing:\n";
    for (std::size_t i = 0; i < kCapabilityCount; ++i) {
        const auto* target = routing[i];
        out += "    ";
        out += ToString(static_cast<Capability>(i));
        out += " -> ";
        out += target != nullptr ? target->name : "emulated fallback";
        out += '\n';
    }
    return out;
}

DriverBroker& GetDriverBroker() {
    static DriverBroker instance;
    return instance;
}

} // namespace Symbiosis
