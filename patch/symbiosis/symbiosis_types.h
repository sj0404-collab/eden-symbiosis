// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file symbiosis_types.h
 * @brief Core vocabulary for the Symbiosis compatibility layer.
 *
 * The Symbiosis layer exists to make *mutually incompatible* components
 * cooperate instead of failing:
 *
 *   - Vulkan drivers built against a different ABI than the host loader
 *     (vendor Mali blobs, Panfrost/PanVK, system libvulkan, Turnip .so files
 *     dropped in by users who don't know Turnip is Adreno-only).
 *   - Binaries whose exported symbol set is *partially* usable: some entry
 *     points resolve, some are missing, some crash on first call.
 *   - A memory budget that is a hard wall (8 GB device, ~5 GB usable).
 *
 * The design principle is "mutual aid" (взаимовыручка): no single component is
 * required to be complete. Each provider advertises what it can do, the broker
 * composes a working whole out of partially-broken parts, and any hole left
 * over is patched by an emulated fallback rather than aborting.
 */

#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

/// Identifies the GPU vendor family we are dealing with.
enum class GpuFamily : u32 {
    Unknown = 0,
    Mali,     ///< ARM Mali (Bifrost/Valhall/Midgard) — primary target.
    Adreno,   ///< Qualcomm Adreno.
    PowerVR,  ///< Imagination PowerVR.
    Xclipse,  ///< Samsung Xclipse (RDNA-based).
    Immortalis, ///< ARM Immortalis (Valhall 5th gen+).
    Software, ///< SwiftShader / lavapipe.
};

/// Origin of a Vulkan driver binary.
enum class DriverOrigin : u32 {
    System = 0,   ///< /system/lib64/libvulkan.so — always present, often old.
    UserBlob,     ///< User-supplied .so in the driver directory.
    Turnip,       ///< Mesa Turnip (Adreno-only; poison for Mali).
    PanVK,        ///< Mesa PanVK (Mali, experimental).
    Emulated,     ///< Pure-CPU shim provided by Symbiosis itself.
};

/// A capability that a provider may or may not be able to service.
///
/// Capabilities are deliberately coarse: the broker only needs to know
/// "can somebody do this at all", not the exact Vulkan feature bit.
enum class Capability : u32 {
    CoreDispatch = 0,     ///< vkGetInstanceProcAddr and friends actually work.
    Timeline,             ///< VK_KHR_timeline_semaphore usable without hanging.
    BufferDeviceAddress,  ///< buffer_device_address does not fault.
    AstcDecode,           ///< native ASTC sampling.
    BcnDecode,            ///< native BCn sampling (rare on Mali).
    Etc2Decode,           ///< native ETC2 sampling.
    Float16,              ///< shaderFloat16 without miscompiles.
    Int8,                 ///< shaderInt8 without miscompiles.
    ExtendedDynamicState, ///< EDS1/2 without driver crashes.
    PushDescriptor,       ///< VK_KHR_push_descriptor.
    NullDescriptor,       ///< robustness2 nullDescriptor.
    HostImageCopy,        ///< VK_EXT_host_image_copy.
    COUNT,
};

inline constexpr std::size_t kCapabilityCount = static_cast<std::size_t>(Capability::COUNT);

/// Health of a provider as observed at runtime, not as advertised.
enum class Health : u32 {
    Untested = 0, ///< Never exercised.
    Good,         ///< Passed probing, no faults recorded.
    Degraded,     ///< Works, but has produced recoverable faults.
    Quarantined,  ///< Faulted hard; excluded from routing but kept loaded.
    Dead,         ///< Failed to load or unrecoverably broken.
};

/// How aggressively the layer is allowed to bend the rules.
enum class SymbiosisMode : u32 {
    Off = 0,      ///< Behave like stock Eden.
    Safe,         ///< Only ABI-safe adaptations and fallbacks.
    Cooperative,  ///< Default: cross-provider routing + emulated fallbacks.
    Desperate,    ///< Accept known-risky providers to get *something* running.
};

const char* ToString(GpuFamily family);
const char* ToString(DriverOrigin origin);
const char* ToString(Capability capability);
const char* ToString(Health health);
const char* ToString(SymbiosisMode mode);

/// Bitset of capabilities, small enough to pass by value.
class CapabilitySet {
public:
    constexpr CapabilitySet() = default;

    constexpr void Set(Capability cap, bool value = true) {
        const auto index = static_cast<std::size_t>(cap);
        if (index >= kCapabilityCount) {
            return;
        }
        if (value) {
            bits |= (u64{1} << index);
        } else {
            bits &= ~(u64{1} << index);
        }
    }

    [[nodiscard]] constexpr bool Has(Capability cap) const {
        const auto index = static_cast<std::size_t>(cap);
        if (index >= kCapabilityCount) {
            return false;
        }
        return (bits & (u64{1} << index)) != 0;
    }

    [[nodiscard]] constexpr bool Empty() const {
        return bits == 0;
    }

    [[nodiscard]] constexpr u64 Raw() const {
        return bits;
    }

    constexpr void Clear() {
        bits = 0;
    }

private:
    u64 bits{};
};

} // namespace Symbiosis
