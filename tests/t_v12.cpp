// Проверка исправлений v12
#include <cassert>
#include <cstdio>
#include <string>
#include <vector>
#include "common/settings.h"
#include "common/symbiosis/device_profiles.h"
#include "common/symbiosis/launcher_profiles.h"
using namespace Symbiosis;

int main(){
    printf("=== ФИКС 3: ключ vsync ===\n");
    auto& pe = GetProfileEngine();
    int vsync_tweaks=0, wrong=0;
    for (auto& p : pe.All())
        for (auto& t : p.tweaks) {
            if (t.key=="use_vsync") vsync_tweaks++;
            if (t.key=="vsync_mode") wrong++;
        }
    printf("use_vsync: %d, vsync_mode (неверный): %d\n", vsync_tweaks, wrong);
    assert(wrong==0 && vsync_tweaks>0);
    printf("=> ключ исправлен, настройка теперь применяется\n");

    // Значения VSyncMode: Immediate=0, Mailbox=1, Fifo=2, FifoRelaxed=3
    for (auto& p : pe.All())
        for (auto& t : p.tweaks)
            if (t.key=="use_vsync") { int v=std::stoi(t.value); assert(v>=0&&v<=3); }
    printf("=> все значения в диапазоне enum\n");

    printf("\n=== ФИКС 4: пресет читается один раз ===\n");
    // Idempotent: одинаковые настройки -> одинаковый generation
    u64 a = RetroGeneration();
    u64 b = RetroGeneration();
    printf("generation стабилен: %d\n", (int)(a==b));
    assert(a==b);

    // Смена настройки -> generation меняется (перестройка сработает)
    Settings::values.retro_width.SetValue(320);
    Settings::values.symbiosis_launcher.SetValue(11); // Custom
    u64 c = RetroGeneration();
    printf("после изменения настройки generation другой: %d\n", (int)(a!=c));
    assert(a!=c);
    printf("=> перестройка конвейера сработает только когда нужно\n");

    printf("\nВСЕ ПРОВЕРКИ ПРОЙДЕНЫ\n");
}
