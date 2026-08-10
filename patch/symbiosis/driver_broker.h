// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file driver_broker.h
 * @brief Loads several possibly-incompatible Vulkan drivers and composes one
 *        working dispatch table out of them.
 *
 * Stock Eden loads exactly one libvulkan and lives or dies with it. On Mali
 * devices that is a coin flip: the vendor blob may be too old for the features
 * Eden wants, a user-supplied Turnip build is Adreno-only and will not even
 * report a device, and PanVK is fast but incomplete.
 *
 * The broker takes the opposite stance. It opens *every* candidate it can find,
 * asks each one what it can actually do (by probing, not by trusting version
 * strings), and then builds a routing table: each Vulkan entry point is served
 * by the healthiest provider that can service it. If a provider faults at
 * runtime, it is quarantined and its entry points are re-pointed at the next
 * best provider without tearing down the session.
 *
 * Nothing here requires the providers to be mutually compatible. They never see
 * each other; only the broker sees them all.
 */

#pragma once

#include <array>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "common/common_types.h"
#include "common/symbiosis/symbiosis_types.h"

namespace Common {
class DynamicLibrary;
}

namespace Symbiosis {

/// One candidate Vulkan implementation.
struct Provider {
    std::string name;          ///< Human-readable, shown in the UI/log.
    std::string path;          ///< Filesystem path that was opened.
    DriverOrigin origin{DriverOrigin::System};
    GpuFamily family{GpuFamily::Unknown};
    Health health{Health::Untested};
    CapabilitySet caps;        ///< What probing proved it can do.
    u32 fault_count{0};        ///< Recoverable faults charged to this provider.
    u32 api_version{0};        ///< Reported VkPhysicalDevice apiVersion.
    bool is_primary{false};    ///< Owns the VkInstance/VkDevice.

    std::shared_ptr<Common::DynamicLibrary> library;
    void* raw_handle{nullptr}; ///< dlopen handle (adrenotools or plain dlopen).

    [[nodiscard]] bool Usable() const {
        return health == Health::Good || health == Health::Degraded;
    }
};

/// Where the broker should look for driver binaries.
struct BrokerPaths {
    std::string hook_lib_dir;    ///< adrenotools hook libs.
    std::string custom_driver_dir;
    std::string custom_driver_name;
    std::string file_redirect_dir;
    std::string extra_scan_dir;  ///< Symbiosis-managed pool of user blobs.
};

/**
 * @brief Central registry and router for driver providers.
 *
 * Thread-safe: the emulation thread may report faults while the UI thread
 * queries status.
 */
class DriverBroker {
public:
    DriverBroker();
    ~DriverBroker();

    DriverBroker(const DriverBroker&) = delete;
    DriverBroker& operator=(const DriverBroker&) = delete;

    /// Sets how much risk the broker may take. Must be called before Discover().
    void SetMode(SymbiosisMode mode);

    /// Overrides GPU family detection (user override / testing).
    /// Must be called before Discover() to affect blob filtering.
    void SetHostFamily(GpuFamily family);

    [[nodiscard]] SymbiosisMode Mode() const {
        return mode;
    }

    /**
     * @brief Opens every candidate driver and probes it.
     *
     * Never throws and never aborts: a candidate that fails to load is recorded
     * as Health::Dead and the scan continues. Returns the number of usable
     * providers (may be zero, in which case the caller should fall back to
     * stock behaviour).
     */
    std::size_t Discover(const BrokerPaths& paths);

    /**
     * @brief The provider that should own the VkInstance/VkDevice.
     *
     * This is the one whose loader semantics the rest of Eden will see. It is
     * chosen for *stability*, not for feature count — a provider that can only
     * do CoreDispatch but never crashes beats one that advertises everything
     * and dies on the third frame.
     */
    [[nodiscard]] Provider* Primary();

    /// Best usable provider for a given capability, or nullptr.
    [[nodiscard]] Provider* ProviderFor(Capability cap);

    /// True if anybody at all can service @p cap.
    [[nodiscard]] bool Supports(Capability cap);

    /**
     * @brief Charges a runtime fault to a provider.
     *
     * After @p kQuarantineThreshold faults the provider is quarantined and
     * routing is recomputed. This is how a driver that lies about its
     * capabilities gets demoted without taking the session down.
     */
    void ReportFault(Provider* provider, std::string_view reason);

    /// Recomputes the capability routing table after a health change.
    void Rebalance();

    /**
     * @brief Replaces a provider's capability set with what the live device
     *        actually reports, then reroutes.
     *
     * Load-time probing can only inspect exported symbols; format and feature
     * support is a device property and is only knowable once a VkDevice
     * exists. Calling this upgrades (or downgrades) the conservative guesses
     * made during Discover().
     */
    void UpdateObservedCapabilities(Provider* provider, CapabilitySet observed,
                                    u32 api_version);

    [[nodiscard]] const std::vector<Provider>& Providers() const {
        return providers;
    }

    /// Multi-line human-readable summary for the log and the UI.
    [[nodiscard]] std::string DescribeTopology() const;

    static constexpr u32 kQuarantineThreshold = 3;

private:
    Provider* AddCandidate(std::string name, std::string path, DriverOrigin origin,
                           void* handle);
    void Probe(Provider& provider);
    void ScanDirectory(const std::string& dir);

    mutable std::mutex mutex;
    std::vector<Provider> providers;
    std::array<Provider*, kCapabilityCount> routing{};
    SymbiosisMode mode{SymbiosisMode::Cooperative};
    GpuFamily host_family{GpuFamily::Unknown};
    bool discovered{false};
};

/// Process-wide broker.
DriverBroker& GetDriverBroker();

} // namespace Symbiosis
