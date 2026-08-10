#include <cstdio>
#include "common/symbiosis/driver_broker.h"
using namespace Symbiosis;
int main(){
    DriverBroker b; b.SetMode(SymbiosisMode::Cooperative);
    BrokerPaths p{}; p.extra_scan_dir="pool";
    printf("usable=%zu\n", b.Discover(p));
    fputs(b.DescribeTopology().c_str(), stdout);
    for (auto& pr : b.Providers())
        printf("PROV '%s' health=%s caps=0x%llx\n", pr.name.c_str(), ToString(pr.health), (unsigned long long)pr.caps.Raw());
}
