#include <cassert>
#include <cstdio>
#include "common/symbiosis/driver_broker.h"
#include "common/symbiosis/memory_governor.h"
using namespace Symbiosis;
int main(){
    DriverBroker b; b.SetMode(SymbiosisMode::Cooperative); b.SetHostFamily(GpuFamily::Mali);
    BrokerPaths p{}; p.extra_scan_dir="pool";
    b.Discover(p);
    auto* prim=b.Primary(); assert(prim);

    // До device-query: ASTC считается неподдерживаемым (консервативно)
    bool astc_before = b.Supports(Capability::AstcDecode);
    printf("ASTC before device query: %d\n",(int)astc_before);
    assert(!astc_before);

    // Симулируем реальный ответ устройства: GPU УМЕЕТ ASTC
    CapabilitySet obs = prim->caps;
    obs.Set(Capability::AstcDecode,true);
    obs.Set(Capability::Float16,true);
    b.UpdateObservedCapabilities(prim,obs,4206592);
    bool astc_after=b.Supports(Capability::AstcDecode);
    printf("ASTC after device query: %d, api=%u\n",(int)astc_after,prim->api_version);
    assert(astc_after);
    assert(b.Supports(Capability::Float16));
    assert(prim->api_version==4206592);
    printf("=> capability upgrade works (no more needless CPU fallback)\n");

    // Карантин по device-lost: 3 сбоя -> провайдер выведен, сессия жива
    for(int i=0;i<3;i++) b.ReportFault(prim,"VK_ERROR_DEVICE_LOST");
    assert(prim->health==Health::Quarantined);
    assert(b.Primary()!=prim);
    printf("=> device-lost quarantine reroutes to '%s'\n", b.Primary()->name.c_str());

    // Governor: донор реально освобождает
    MemoryGovernor g; g.Initialise(8ULL<<30);
    u64 fake=900ULL<<20;
    g.RegisterDonor({"texture cache",[&](u64 want){u64 r=(want<fake)?want:fake;fake-=r;return r;},10});
    u64 got=g.Reclaim(300ULL<<20);
    printf("reclaimed %llu MiB\n",(unsigned long long)(got>>20));
    assert(got>=(300ULL<<20));

    // GPU-учёт: дельта не уходит в минус и отражается в Used()
    g.NoteGpuAllocation(500LL<<20);
    u64 u1=g.Used();
    g.NoteGpuAllocation(-(200LL<<20));
    u64 u2=g.Used();
    printf("used with gpu: %llu -> %llu MiB\n",(unsigned long long)(u1>>20),(unsigned long long)(u2>>20));
    assert(u1>u2);
    printf("\nALL V3 TESTS PASSED\n");
}
