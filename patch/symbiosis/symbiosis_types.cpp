// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/symbiosis/symbiosis_types.h"

namespace Symbiosis {

const char* ToString(GpuFamily family) {
    switch (family) {
    case GpuFamily::Unknown:
        return "Unknown";
    case GpuFamily::Mali:
        return "Mali";
    case GpuFamily::Adreno:
        return "Adreno";
    case GpuFamily::PowerVR:
        return "PowerVR";
    case GpuFamily::Xclipse:
        return "Xclipse";
    case GpuFamily::Immortalis:
        return "Immortalis";
    case GpuFamily::Software:
        return "Software";
    }
    return "Unknown";
}

const char* ToString(DriverOrigin origin) {
    switch (origin) {
    case DriverOrigin::System:
        return "System";
    case DriverOrigin::UserBlob:
        return "UserBlob";
    case DriverOrigin::Turnip:
        return "Turnip";
    case DriverOrigin::PanVK:
        return "PanVK";
    case DriverOrigin::Emulated:
        return "Emulated";
    }
    return "Unknown";
}

const char* ToString(Capability capability) {
    switch (capability) {
    case Capability::CoreDispatch:
        return "CoreDispatch";
    case Capability::Timeline:
        return "Timeline";
    case Capability::BufferDeviceAddress:
        return "BufferDeviceAddress";
    case Capability::AstcDecode:
        return "AstcDecode";
    case Capability::BcnDecode:
        return "BcnDecode";
    case Capability::Etc2Decode:
        return "Etc2Decode";
    case Capability::Float16:
        return "Float16";
    case Capability::Int8:
        return "Int8";
    case Capability::ExtendedDynamicState:
        return "ExtendedDynamicState";
    case Capability::PushDescriptor:
        return "PushDescriptor";
    case Capability::NullDescriptor:
        return "NullDescriptor";
    case Capability::HostImageCopy:
        return "HostImageCopy";
    case Capability::COUNT:
        break;
    }
    return "Unknown";
}

const char* ToString(Health health) {
    switch (health) {
    case Health::Untested:
        return "Untested";
    case Health::Good:
        return "Good";
    case Health::Degraded:
        return "Degraded";
    case Health::Quarantined:
        return "Quarantined";
    case Health::Dead:
        return "Dead";
    }
    return "Unknown";
}

const char* ToString(SymbiosisMode mode) {
    switch (mode) {
    case SymbiosisMode::Off:
        return "Off";
    case SymbiosisMode::Safe:
        return "Safe";
    case SymbiosisMode::Cooperative:
        return "Cooperative";
    case SymbiosisMode::Desperate:
        return "Desperate";
    }
    return "Unknown";
}

} // namespace Symbiosis
