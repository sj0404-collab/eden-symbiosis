// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file abi_shim.h
 * @brief Resolves symbols across binaries that were never meant to be mixed.
 *
 * Concrete problems this solves on old Mali devices:
 *
 *  - A driver blob extracted from a newer vendor image imports symbols that the
 *    device's own libc/libhardware does not export. dlopen() fails wholesale
 *    even though 99% of the blob would work.
 *  - A Vulkan entry point exists in one provider and not another; stock code
 *    would branch on a feature flag and take a slow path for everything.
 *  - Aliased names: `vkGetSemaphoreCounterValue` vs `...KHR`, `vkCmdSetCullMode`
 *    vs `...EXT`. The same function under two names across two vendors.
 *
 * The shim answers a single question — "give me something callable for this
 * name" — by trying, in order: the preferred provider, aliases of the name,
 * every other usable provider, and finally a built-in emulated stub.
 */

#pragma once

#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "common/common_types.h"
#include "common/symbiosis/symbiosis_types.h"

namespace Symbiosis {

struct Provider;
class DriverBroker;

/// Result of a cross-provider symbol resolution.
struct Resolution {
    void* address{nullptr};
    Provider* owner{nullptr};   ///< Which binary actually supplied it.
    bool via_alias{false};      ///< Resolved under a different name.
    bool emulated{false};       ///< Served by a Symbiosis stub.

    [[nodiscard]] bool Ok() const {
        return address != nullptr;
    }
};

/**
 * @brief Symbol resolver spanning all loaded providers.
 */
class AbiShim {
public:
    explicit AbiShim(DriverBroker& broker);

    AbiShim(const AbiShim&) = delete;
    AbiShim& operator=(const AbiShim&) = delete;

    /**
     * @brief Finds a callable address for @p name from any provider.
     *
     * @param name      Vulkan entry point name.
     * @param preferred Provider to try first, or nullptr for the primary.
     */
    Resolution Resolve(std::string_view name, Provider* preferred = nullptr);

    /// Registers a fallback implementation used when no provider has @p name.
    void RegisterEmulated(std::string name, void* implementation);

    /// Declares that @p alias and @p canonical are the same function.
    void RegisterAlias(std::string alias, std::string canonical);

    /// Number of entry points that ended up served by a non-preferred provider.
    [[nodiscard]] u32 CrossProviderCount() const {
        return cross_provider_count;
    }

    [[nodiscard]] std::string DescribeResolutions() const;

    /// Installs the default KHR/EXT alias table.
    void InstallDefaultAliases();

private:
    void* LookupIn(Provider& provider, std::string_view name) const;

    DriverBroker& broker;
    mutable std::mutex mutex;
    std::unordered_map<std::string, std::string> aliases;
    std::unordered_map<std::string, void*> emulated;
    std::unordered_map<std::string, Resolution> cache;
    u32 cross_provider_count{0};
};

AbiShim& GetAbiShim();

} // namespace Symbiosis
