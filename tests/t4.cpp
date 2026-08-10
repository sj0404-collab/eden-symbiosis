#include <cstdio>
#include <cassert>
#include "common/symbiosis/thermal_monitor.h"
using namespace Symbiosis;
int main(){
    auto& m=GetThermalMonitor();
    auto r=m.Sample();
    printf("state=%s\ntemp=%d millic (zone '%s')\ngpu clock=%d%%\nsummary: %s\nadvice: %s\n",
      ToString(r.state), r.max_temp_millic, r.hottest_zone.c_str(),
      r.gpu_clock_percent, r.summary.c_str(), r.advice.c_str());
    // Должен корректно работать даже там, где сенсоров нет (как в этой песочнице)
    assert(!r.summary.empty());
    auto r2=m.Last();
    assert(r2.state==r.state);
    printf("\nTHERMAL OK (graceful on sensorless host)\n");
}
