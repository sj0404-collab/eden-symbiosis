#include <cassert>
#include <cstdio>
#include <string>
#include "common/symbiosis/driver_broker.h"
#include "common/symbiosis/abi_shim.h"

using namespace Symbiosis;

int main() {
    DriverBroker broker;
    broker.SetMode(SymbiosisMode::Cooperative);
    broker.SetHostFamily(GpuFamily::Mali);

    BrokerPaths paths{};
    paths.extra_scan_dir = "pool";
    const auto usable = broker.Discover(paths);
    printf("usable providers: %zu\n", usable);
    fputs(broker.DescribeTopology().c_str(), stdout);
    assert(usable >= 2);

    Provider* primary = broker.Primary();
    assert(primary != nullptr);
    printf("\nPRIMARY = %s\n", primary->name.c_str());

    // Capability routing: timeline lives ONLY in panvk, EDS only in mali blob.
    Provider* tl  = broker.ProviderFor(Capability::Timeline);
    Provider* eds = broker.ProviderFor(Capability::ExtendedDynamicState);
    printf("Timeline -> %s\n", tl ? tl->name.c_str() : "(none)");
    printf("EDS      -> %s\n", eds? eds->name.c_str(): "(none)");
    assert(tl  != nullptr && tl->name.find("panvk") != std::string::npos);
    assert(eds != nullptr && eds->name.find("mali")  != std::string::npos);
    // The two capabilities are served by DIFFERENT binaries => symbiosis.
    assert(tl != eds);
    printf("=> two incompatible blobs jointly cover the feature set\n");

    // ABI shim: ask the MALI provider for a symbol only PANVK has.
    AbiShim shim{broker};
    auto r = shim.Resolve("vkGetBufferDeviceAddress", eds);
    printf("\nvkGetBufferDeviceAddress resolved=%d owner=%s alias=%d\n",
           (int)r.Ok(), r.owner? r.owner->name.c_str():"-", (int)r.via_alias);
    assert(r.Ok());
    assert(r.owner != eds);            // borrowed from the other binary
    printf("=> borrowed across binaries OK\n");

    // Alias resolution: core name requested, only the KHR name exists.
    auto r2 = shim.Resolve("vkGetSemaphoreCounterValue", eds);
    printf("vkGetSemaphoreCounterValue resolved=%d owner=%s alias=%d\n",
           (int)r2.Ok(), r2.owner? r2.owner->name.c_str():"-", (int)r2.via_alias);
    assert(r2.Ok() && r2.via_alias);
    printf("=> KHR/core alias bridging OK\n");

    // Fault handling: kill the timeline provider, routing must move or drop.
    for (int i = 0; i < 3; ++i) broker.ReportFault(tl, "simulated device lost");
    Provider* tl_after = broker.ProviderFor(Capability::Timeline);
    printf("\nafter quarantine, Timeline -> %s\n", tl_after? tl_after->name.c_str():"(none/emulated)");
    assert(tl->health == Health::Quarantined);
    assert(tl_after != tl);
    printf("=> quarantine + reroute OK\n");

    // Session must survive: a primary is still elected.
    assert(broker.Primary() != nullptr);
    printf("=> session survived driver loss, primary = %s\n", broker.Primary()->name.c_str());

    printf("\nALL SYMBIOSIS ASSERTIONS PASSED\n");
    return 0;
}
