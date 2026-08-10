#include <cassert>
#include <cstdio>
#include "common/symbiosis/device_profiles.h"
#include "common/symbiosis/selftest.h"
using namespace Symbiosis;
int main(){
    auto& e = GetProfileEngine();
    printf("catalogue: %zu profiles\n\n", e.All().size());

    // Твоё железо: Mali + только системный драйвер
    auto m = e.ProfilesFor(GpuFamily::Mali, DriverOrigin::System);
    printf("Mali/System matches: %zu\n", m.size());
    assert(m.size() >= 4);
    for (auto& p : m) printf("  - %s [stock_ok=%d]\n", p.display_name.c_str(), (int)p.works_on_stock_driver);

    // Профиль под максимум ФПС должен работать на стоковом драйвере
    auto fps = e.Resolve(GpuFamily::Mali, DriverOrigin::System, TuningIntent::MaxFps);
    printf("\nMaxFPS -> %s\n", fps.display_name.c_str());
    assert(fps.works_on_stock_driver);
    assert(fps.family == GpuFamily::Mali);
    assert(fps.tweaks.size() >= 5);
    printf("tweaks: %zu, effect: %s\n", fps.tweaks.size(), fps.expected_effect.c_str());

    // Immortalis должен наследовать профили Mali
    auto imm = e.ProfilesFor(GpuFamily::Immortalis, DriverOrigin::System);
    printf("\nImmortalis inherits %zu Mali profiles\n", imm.size());
    assert(!imm.empty());

    // Неизвестное железо -> generic, не пусто и не падает
    auto unk = e.Resolve(GpuFamily::PowerVR, DriverOrigin::Turnip, TuningIntent::Quality);
    printf("PowerVR/Turnip/Quality -> %s\n", unk.display_name.c_str());
    assert(!unk.id.empty());

    // Все твики должны иметь причину (это UI показывает пользователю)
    int total=0;
    for (auto& p : e.All()) for (auto& t : p.tweaks) { assert(!t.reason.empty()); assert(!t.key.empty()); total++; }
    printf("\nall %d tweaks have key+reason\n", total);

    // Selftest не должен падать без инициализации брокера
    auto r = RunSelfTest();
    printf("selftest: %u pass / %u warn / %u fail\n", r.passed, r.warned, r.failed);
    assert(!r.results.empty());

    printf("\nALL PROFILE TESTS PASSED\n");
}
