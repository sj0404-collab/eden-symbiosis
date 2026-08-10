// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/dynamic_library.h"
#include "common/logging.h"
#include "common/symbiosis/abi_shim.h"
#include "common/symbiosis/driver_broker.h"

namespace Symbiosis {

AbiShim::AbiShim(DriverBroker& broker_) : broker{broker_} {
    InstallDefaultAliases();
}

void AbiShim::InstallDefaultAliases() {
    // Promoted extensions: the same entry point ships under two names
    // depending on how old the driver is. Old Mali blobs only have the KHR/EXT
    // spelling; newer ones only have the core spelling.
    static constexpr std::pair<const char*, const char*> kPairs[]{
        {"vkGetSemaphoreCounterValueKHR", "vkGetSemaphoreCounterValue"},
        {"vkWaitSemaphoresKHR", "vkWaitSemaphores"},
        {"vkSignalSemaphoreKHR", "vkSignalSemaphore"},
        {"vkGetBufferDeviceAddressKHR", "vkGetBufferDeviceAddress"},
        {"vkGetBufferDeviceAddressEXT", "vkGetBufferDeviceAddress"},
        {"vkCmdSetCullModeEXT", "vkCmdSetCullMode"},
        {"vkCmdSetFrontFaceEXT", "vkCmdSetFrontFace"},
        {"vkCmdSetPrimitiveTopologyEXT", "vkCmdSetPrimitiveTopology"},
        {"vkCmdSetDepthTestEnableEXT", "vkCmdSetDepthTestEnable"},
        {"vkCmdSetDepthWriteEnableEXT", "vkCmdSetDepthWriteEnable"},
        {"vkCmdSetDepthCompareOpEXT", "vkCmdSetDepthCompareOp"},
        {"vkCmdSetStencilTestEnableEXT", "vkCmdSetStencilTestEnable"},
        {"vkCmdSetStencilOpEXT", "vkCmdSetStencilOp"},
        {"vkCmdBeginRenderingKHR", "vkCmdBeginRendering"},
        {"vkCmdEndRenderingKHR", "vkCmdEndRendering"},
        {"vkGetPhysicalDeviceFeatures2KHR", "vkGetPhysicalDeviceFeatures2"},
        {"vkGetPhysicalDeviceProperties2KHR", "vkGetPhysicalDeviceProperties2"},
        {"vkGetPhysicalDeviceMemoryProperties2KHR", "vkGetPhysicalDeviceMemoryProperties2"},
        {"vkGetImageMemoryRequirements2KHR", "vkGetImageMemoryRequirements2"},
        {"vkGetBufferMemoryRequirements2KHR", "vkGetBufferMemoryRequirements2"},
        {"vkBindImageMemory2KHR", "vkBindImageMemory2"},
        {"vkBindBufferMemory2KHR", "vkBindBufferMemory2"},
        {"vkCmdPipelineBarrier2KHR", "vkCmdPipelineBarrier2"},
        {"vkQueueSubmit2KHR", "vkQueueSubmit2"},
    };

    std::scoped_lock lock{mutex};
    for (const auto& [alias, canonical] : kPairs) {
        aliases.emplace(alias, canonical);
        // Register the reverse direction too: if Eden asks for the core name
        // and only the KHR name exists, we must find it.
        aliases.emplace(canonical, alias);
    }
}

void AbiShim::RegisterAlias(std::string alias, std::string canonical) {
    std::scoped_lock lock{mutex};
    aliases[std::move(alias)] = std::move(canonical);
}

void AbiShim::RegisterEmulated(std::string name, void* implementation) {
    if (implementation == nullptr) {
        return;
    }
    std::scoped_lock lock{mutex};
    emulated[std::move(name)] = implementation;
}

void* AbiShim::LookupIn(Provider& provider, std::string_view name) const {
    if (provider.library == nullptr || !provider.library->IsOpen()) {
        return nullptr;
    }
    const std::string owned{name};
    return provider.library->GetSymbolAddress(owned.c_str());
}

Resolution AbiShim::Resolve(std::string_view name, Provider* preferred) {
    const std::string key{name};

    {
        std::scoped_lock lock{mutex};
        if (const auto it = cache.find(key); it != cache.end()) {
            // Invalidate if the owning provider has since been quarantined:
            // that is exactly the case where we must find a new donor.
            if (it->second.owner == nullptr || it->second.owner->Usable()) {
                return it->second;
            }
            cache.erase(it);
        }
    }

    Resolution result{};

    // 1. Preferred provider (or the primary), under the requested name.
    Provider* first = preferred != nullptr ? preferred : broker.Primary();
    if (first != nullptr && first->Usable()) {
        if (void* address = LookupIn(*first, name)) {
            result.address = address;
            result.owner = first;
        }
    }

    // 2. Preferred provider, under a known alias.
    if (!result.Ok() && first != nullptr && first->Usable()) {
        std::string alias;
        {
            std::scoped_lock lock{mutex};
            if (const auto it = aliases.find(key); it != aliases.end()) {
                alias = it->second;
            }
        }
        if (!alias.empty()) {
            if (void* address = LookupIn(*first, alias)) {
                result.address = address;
                result.owner = first;
                result.via_alias = true;
            }
        }
    }

    // 3. Any other usable provider — this is the actual symbiosis: a function
    //    missing from the primary blob is borrowed from a different vendor's
    //    binary that happens to export it.
    if (!result.Ok()) {
        // Copy the pointers out under no lock; Providers() is stable for the
        // lifetime of the broker after Discover().
        for (auto& provider : const_cast<std::vector<Provider>&>(broker.Providers())) {
            if (&provider == first || !provider.Usable()) {
                continue;
            }
            if (void* address = LookupIn(provider, name)) {
                result.address = address;
                result.owner = &provider;
                break;
            }
            std::string alias;
            {
                std::scoped_lock lock{mutex};
                if (const auto it = aliases.find(key); it != aliases.end()) {
                    alias = it->second;
                }
            }
            if (!alias.empty()) {
                if (void* address = LookupIn(provider, alias)) {
                    result.address = address;
                    result.owner = &provider;
                    result.via_alias = true;
                    break;
                }
            }
        }
        if (result.Ok()) {
            cross_provider_count++;
            LOG_INFO(Common, "[Symbiosis] '{}' borrowed from '{}'{}", key,
                     result.owner != nullptr ? result.owner->name : "?",
                     result.via_alias ? " (via alias)" : "");
        }
    }

    // 4. Emulated stub as the last resort.
    if (!result.Ok()) {
        std::scoped_lock lock{mutex};
        if (const auto it = emulated.find(key); it != emulated.end()) {
            result.address = it->second;
            result.emulated = true;
            LOG_INFO(Common, "[Symbiosis] '{}' served by emulated fallback", key);
        }
    }

    if (!result.Ok()) {
        LOG_DEBUG(Common, "[Symbiosis] '{}' unresolved by any provider", key);
    }

    {
        std::scoped_lock lock{mutex};
        cache[key] = result;
    }
    return result;
}

std::string AbiShim::DescribeResolutions() const {
    std::scoped_lock lock{mutex};
    std::string out = "Symbiosis ABI shim:\n";
    out += "  aliases known:   " + std::to_string(aliases.size()) + '\n';
    out += "  emulated stubs:  " + std::to_string(emulated.size()) + '\n';
    out += "  cached symbols:  " + std::to_string(cache.size()) + '\n';
    out += "  cross-provider:  " + std::to_string(cross_provider_count) + '\n';

    u32 shown = 0;
    for (const auto& [name, resolution] : cache) {
        if (resolution.owner == nullptr && !resolution.emulated) {
            continue;
        }
        if (shown++ >= 24) {
            out += "  ...\n";
            break;
        }
        out += "    " + name + " -> ";
        if (resolution.emulated) {
            out += "[emulated]";
        } else if (resolution.owner != nullptr) {
            out += resolution.owner->name;
        }
        if (resolution.via_alias) {
            out += " (alias)";
        }
        out += '\n';
    }
    return out;
}

AbiShim& GetAbiShim() {
    static AbiShim instance{GetDriverBroker()};
    return instance;
}

} // namespace Symbiosis
