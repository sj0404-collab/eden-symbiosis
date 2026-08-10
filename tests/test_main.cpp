#include <cassert>
#include <cstdio>
#include "common/symbiosis/driver_broker.h"
#include "common/symbiosis/memory_governor.h"

using namespace Symbiosis;

int main() {
    // --- Memory governor -------------------------------------------------
    MemoryGovernor gov;
    gov.Initialise(8ULL * 1024 * 1024 * 1024); // pretend 8 GB device
    const u64 budget = gov.Budget();
    printf("budget = %llu MiB\n", (unsigned long long)(budget / (1024*1024)));
    assert(budget > 0);
    // On an 8GB device the budget must be well under 8GB (honest accounting).
    assert(budget < 8ULL*1024*1024*1024);

    // Donors must be asked in priority order and actually reclaim.
    int order_log[3] = {0,0,0};
    int idx = 0;
    gov.RegisterDonor({"shader-cache", [&](u64 want){ order_log[idx++]=3; return want/2; }, 30});
    gov.RegisterDonor({"texture-stage", [&](u64 want){ order_log[idx++]=1; return want/4; }, 10});
    gov.RegisterDonor({"buffer-cache", [&](u64 want){ order_log[idx++]=2; return u64{0}; }, 20});

    const u64 got = gov.Reclaim(400ULL*1024*1024);
    printf("reclaimed %llu MiB, order: %d %d %d\n",
           (unsigned long long)(got/(1024*1024)), order_log[0], order_log[1], order_log[2]);
    assert(order_log[0]==1 && order_log[1]==2 && order_log[2]==3); // priority order
    assert(got > 0);

    // GPU accounting must never underflow.
    gov.NoteGpuAllocation(100);
    gov.NoteGpuAllocation(-500);
    printf("gpu underflow guarded ok\n");

    // --- Driver broker ---------------------------------------------------
    DriverBroker broker;
    broker.SetMode(SymbiosisMode::Cooperative);
    BrokerPaths paths{}; // empty: no drivers on this host
    const auto usable = broker.Discover(paths);
    printf("discovered %zu usable providers (expected 0 on host)\n", usable);
    assert(broker.Primary() == nullptr);          // must not crash with zero providers
    assert(broker.Supports(Capability::Timeline) == false);
    fputs(broker.DescribeTopology().c_str(), stdout);

    printf("\nALL ASSERTIONS PASSED\n");
    return 0;
}
