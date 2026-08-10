// Воспроизводит ваш баг: "от качества не зависит фпс"
#include <cassert>
#include <cstdio>
#include <string>
#include <vector>
#include "common/settings.h"
#include "common/symbiosis/auto_modes.h"
using namespace Symbiosis;

// Настройка разрешения, как в реальном Eden
static Settings::Basic g_res{"resolution_setup","3"};

void wire(){
    Settings::values.linkage.by_category[0] = { &g_res };
}

int main(){
    wire();
    printf("=== СЦЕНАРИЙ: пользователь меняет качество вручную ===\n");

    // 1. Пользователь выбрал режим Balanced -> ставится native (3)
    auto& e = GetAutoModeEngine();
    e.Apply(AutoMode::Balanced, GpuFamily::Mali, DriverOrigin::System);
    printf("после выбора Balanced: resolution_setup=%s\n", g_res.value.c_str());
    assert(g_res.value=="3");

    // 2. Пользователь ВРУЧНУЮ ставит 0.75x чтобы поднять ФПС
    g_res.value = "2";
    printf("пользователь вручную поставил: resolution_setup=%s (0.75x)\n", g_res.value.c_str());

    // 3. Запускает игру -> раньше ApplyCurrentOnStartup затирал обратно на "3"
    e.ApplyCurrentOnStartup(GpuFamily::Mali, DriverOrigin::System);
    printf("после запуска игры:  resolution_setup=%s\n", g_res.value.c_str());

    if (g_res.value=="3") {
        printf("\nПРОВАЛ: настройка затёрта -> ФПС не изменится\n");
        return 1;
    }
    printf("=> настройка СОХРАНЕНА, ФПС изменится\n");
    assert(g_res.value=="2");

    // 4. Явный выбор режима по-прежнему работает
    e.Apply(AutoMode::Performance, GpuFamily::Mali, DriverOrigin::System);
    printf("\nявный выбор Performance: resolution_setup=%s\n", g_res.value.c_str());
    assert(g_res.value=="2");
    e.Apply(AutoMode::Quality, GpuFamily::Mali, DriverOrigin::System);
    printf("явный выбор Quality:     resolution_setup=%s (1.5x)\n", g_res.value.c_str());
    assert(g_res.value=="5");

    // 5. И он тоже переживает перезапуск
    e.ApplyCurrentOnStartup(GpuFamily::Mali, DriverOrigin::System);
    printf("после перезапуска:       resolution_setup=%s\n", g_res.value.c_str());
    assert(g_res.value=="5");

    printf("\nОБА ПОВЕДЕНИЯ КОРРЕКТНЫ\n");
}
