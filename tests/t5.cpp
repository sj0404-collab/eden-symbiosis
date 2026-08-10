#include <cassert>
#include <cstdio>
#include "common/symbiosis/symbiosis_log.h"
#include "common/symbiosis/thermal_monitor.h"
using namespace Symbiosis;
int main(){
    // --- Лог ---
    auto& L=GetLog();
    L.Clear();
    LogInfo(LogArea::Driver,"driver picked");
    LogWarning(LogArea::Memory,"budget tight");
    LogError(LogArea::Thermal,"too hot");
    LogDebug(LogArea::Render,"retro on");
    printf("count=%zu\n",L.Count()); assert(L.Count()==4);
    auto warns=L.Entries(LogArea::General,true,LogLevel::Warning);
    printf("warn+ entries=%zu\n",warns.size()); assert(warns.size()==2);
    auto only_drv=L.Entries(LogArea::Driver,false,LogLevel::Debug);
    assert(only_drv.size()==1);
    auto pc=L.ProblemCounts();
    assert(pc[(size_t)LogArea::Memory]==1 && pc[(size_t)LogArea::Thermal]==1);
    printf("dump:\n%s",L.Dump(LogArea::General,true,LogLevel::Debug).c_str());

    // Кольцевой буфер не должен переполняться
    for(int i=0;i<600;i++) LogInfo(LogArea::General,"spam");
    printf("after 600: count=%zu (cap=%zu)\n",L.Count(),SymbiosisLog::kCapacity);
    assert(L.Count()==SymbiosisLog::kCapacity);

    // --- Термополитика (твой выбор: 90 ceiling / 95 warn) ---
    ThermalReading r{}; r.max_temp_millic=75000; r.gpu_clock_percent=100;
    auto v=EvaluateThermalPolicy(r,90,95);
    printf("\n75C -> warn=%d\n",(int)v.should_warn); assert(!v.should_warn);

    r.max_temp_millic=91000;
    v=EvaluateThermalPolicy(r,90,95);
    printf("91C -> warn=%d over=%d rest=%umin\n  %s\n",(int)v.should_warn,(int)v.over_ceiling,v.suggested_rest_minutes,v.body.c_str());
    assert(v.should_warn && v.over_ceiling);

    r.max_temp_millic=96000;
    v=EvaluateThermalPolicy(r,90,95);
    printf("96C -> rest=%umin title='%s'\n",v.suggested_rest_minutes,v.title.c_str());
    assert(v.suggested_rest_minutes==10);

    // Без сенсоров, но частота придушена
    ThermalReading n{}; n.max_temp_millic=-1; n.gpu_clock_percent=40;
    v=EvaluateThermalPolicy(n,90,95);
    printf("no sensors, 40%% clock -> warn=%d\n",(int)v.should_warn);
    assert(v.should_warn);
    printf("\nALL LOG+THERMAL TESTS PASSED\n");
}
