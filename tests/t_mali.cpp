#include <cassert>
#include <cstdio>
#include "common/symbiosis/mali_tuning.h"
using namespace Symbiosis;
int main(){
    struct C { const char* name; bool arm; u32 sg; u32 api; MaliGeneration want; };
    // VK_MAKE_API_VERSION(0,1,1,0) = 0x00401000 ; 1.3 = 0x00403000
    const u32 v11 = (1u<<22)|(1u<<12), v13=(1u<<22)|(3u<<12), v10=(1u<<22);
    C cases[] = {
        {"Mali-G610 MC6", true, 16, v13, MaliGeneration::ValhallGen3},
        {"Mali-G710",     true, 16, v13, MaliGeneration::ValhallGen3},
        {"Mali-G78 MC14", true, 16, v13, MaliGeneration::ValhallGen2},
        {"Mali-G77 MC9",  true, 16, v11, MaliGeneration::ValhallGen1},
        {"Mali-G76 MC4",  true, 8,  v11, MaliGeneration::BifrostGen2},
        {"Mali-G72 MP3",  true, 8,  v11, MaliGeneration::BifrostGen2},
        {"Mali-G71 MP20", true, 4,  v10, MaliGeneration::BifrostGen1},
        {"Mali-G51",      true, 4,  v10, MaliGeneration::BifrostGen1},
        {"Mali-T880 MP12",true, 4,  v10, MaliGeneration::Midgard},
        {"Mali-T720",     true, 4,  v10, MaliGeneration::Midgard},
        {"Immortalis-G715 MC11", true, 16, v13, MaliGeneration::Immortalis},
        {"Adreno (TM) 640", false, 64, v13, MaliGeneration::NotMali},
        {"NVIDIA GeForce RTX 3080", false, 32, v13, MaliGeneration::NotMali},
    };
    for (auto& c : cases) {
        auto t = MaliTuning::Identify(c.name, c.arm, c.sg, c.api);
        auto a = MaliTuning::Advise(t);
        printf("%-24s -> %-22s res=%u async=%d astc=%d budget=%.0f%%\n",
               c.name, ToString(t.generation), a.resolution_index,
               (int)a.allow_async_shaders, (int)a.gpu_astc, a.texture_budget_fraction*100);
        assert(t.generation == c.want);
        // Тайлер никогда не должен разрешать reactive flushing
        assert(!a.allow_reactive_flushing || t.generation==MaliGeneration::NotMali);
    }
    // Разбор числа ядер
    auto g610 = MaliTuning::Identify("Mali-G610 MC6", true, 16, v13);
    printf("\ncores parsed from 'MC6': %u\n", g610.core_count);
    assert(g610.core_count == 6);

    // Слабая деталь (2 ядра) должна понижать разрешение даже на Valhall
    auto weak = MaliTuning::Identify("Mali-G610 MC2", true, 16, v13);
    auto wa = MaliTuning::Advise(weak);
    printf("Mali-G610 MC2 -> res index %u (must be 2)\n", wa.resolution_index);
    assert(wa.resolution_index == 2);

    // Старое железо не должно получать async shaders
    auto old = MaliTuning::Identify("Mali-G71 MP20", true, 4, v10);
    assert(!MaliTuning::Advise(old).allow_async_shaders);
    printf("old Bifrost: async shaders disabled OK\n");

    // Неизвестная Mali не падает и получает осторожные значения
    auto unk = MaliTuning::Identify("Mali-G999 MC99", true, 16, v13);
    printf("unknown Mali -> %s\n", ToString(unk.generation));
    assert(unk.generation != MaliGeneration::NotMali);

    printf("\nALL MALI TESTS PASSED\n");
}
