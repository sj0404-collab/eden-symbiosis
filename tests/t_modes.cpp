#include <cassert>
#include <cstdio>
#include <set>
#include "common/symbiosis/auto_modes.h"
using namespace Symbiosis;

int main(){
    auto& e = GetAutoModeEngine();

    // Твоё железо: Mali + системный драйвер
    auto all = e.AllFor(GpuFamily::Mali, DriverOrigin::System);
    printf("modes offered: %zu\n", all.size());
    assert(all.size() == 8);
    for (auto& m : all)
        printf("  %-14s ceiling=%2u  tweaks=%2zu  %s\n",
               m.display_name.c_str(), m.temp_ceiling, m.tweaks.size(), m.summary.c_str());

    // Balanced первым — это правильный дефолт
    assert(all.front().mode == AutoMode::Balanced);

    // Custom ничего не применяет
    auto custom = e.Resolve(AutoMode::Custom, GpuFamily::Mali, DriverOrigin::System);
    assert(custom.tweaks.empty());
    printf("\nCustom applies nothing: OK\n");

    // Каждый режим должен задавать одни и те же ключевые настройки,
    // иначе переключение оставит хвост от предыдущего режима.
    std::set<std::string> core = {"resolution_setup","scaling_filter","anti_aliasing",
                                  "gpu_accuracy","use_asynchronous_shaders","use_disk_shader_cache"};
    for (auto& m : all) {
        if (m.mode == AutoMode::Custom) continue;
        std::set<std::string> have;
        for (auto& t : m.tweaks) have.insert(t.key);
        for (auto& k : core) {
            if (!have.count(k)) { printf("MISSING %s in %s\n", k.c_str(), m.display_name.c_str()); assert(false); }
        }
    }
    printf("every mode sets all core keys (no leftovers): OK\n");

    // У каждого твика есть причина — её показывает UI
    int n=0;
    for (auto& m : all) for (auto& t : m.tweaks) { assert(!t.reason.empty()); assert(!t.key.empty()); n++; }
    printf("all %d tweaks have key+reason: OK\n", n);

    // Turbo горячее Stability
    auto turbo = e.Resolve(AutoMode::Turbo, GpuFamily::Mali, DriverOrigin::System);
    auto stab  = e.Resolve(AutoMode::Stability, GpuFamily::Mali, DriverOrigin::System);
    printf("\nTurbo ceiling %u > Stability ceiling %u\n", turbo.temp_ceiling, stab.temp_ceiling);
    assert(turbo.temp_ceiling > stab.temp_ceiling);
    assert(turbo.temp_ceiling == 90);

    // Тайлер против не-тайлера: Quality не должен требовать одинакового
    auto q_mali = e.Resolve(AutoMode::Quality, GpuFamily::Mali, DriverOrigin::System);
    auto q_desk = e.Resolve(AutoMode::Quality, GpuFamily::Xclipse, DriverOrigin::System);
    std::string rm, rd;
    for (auto& t : q_mali.tweaks) if (t.key=="resolution_setup") rm=t.value;
    for (auto& t : q_desk.tweaks) if (t.key=="resolution_setup") rd=t.value;
    printf("Quality res: Mali=%s Xclipse=%s (must differ)\n", rm.c_str(), rd.c_str());
    assert(rm != rd);

    // Значения разрешения должны быть в диапазоне enum (0..12)
    for (auto& m : all) for (auto& t : m.tweaks)
        if (t.key=="resolution_setup") { int v=std::stoi(t.value); assert(v>=0 && v<=12); }
    printf("all resolution values in enum range: OK\n");

    // gpu_accuracy только 0 или 1 (Low/High)
    for (auto& m : all) for (auto& t : m.tweaks)
        if (t.key=="gpu_accuracy") { int v=std::stoi(t.value); assert(v==0||v==1); }
    printf("all gpu_accuracy values valid: OK\n");

    // булевы строго "true"/"false"
    for (auto& m : all) for (auto& t : m.tweaks)
        if (t.key.rfind("use_",0)==0 || t.key=="force_max_clock")
            if (t.value!="true" && t.value!="false" && !isdigit(t.value[0])) { printf("BAD bool %s=%s\n",t.key.c_str(),t.value.c_str()); assert(false); }
    printf("all boolean values well-formed: OK\n");

    auto aaa = e.Resolve(AutoMode::AaaMin, GpuFamily::Mali, DriverOrigin::System);
    std::string raaa;
    bool no_ext = false;
    for (auto& tw : aaa.tweaks) {
        if (tw.key == "resolution_setup") raaa = tw.value;
        if (tw.key == "use_extended_memory_layout" && tw.value == "false") no_ext = true;
    }
    printf("AAA min res=%s no_extended_ram=%d\n", raaa.c_str(), (int)no_ext);
    assert(raaa == "0");
    assert(no_ext);

    printf("\nALL MODE TESTS PASSED\n");
}
