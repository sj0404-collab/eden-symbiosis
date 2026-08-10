// Воспроизводит ИМЕННО тот краш: кэш уничтожен, губернатор жив, идёт Reclaim.
#include <cassert>
#include <cstdio>
#include "common/symbiosis/memory_governor.h"
using namespace Symbiosis;

struct FakeCache {
    u64 used = 800ULL<<20;
    DonorHandle handle = kInvalidDonor;
    bool unregister_on_destroy;

    explicit FakeCache(MemoryGovernor& g, bool fixed) : unregister_on_destroy(fixed) {
        handle = g.RegisterDonor(Donor{"texture cache",
            [this](u64 want)->u64{
                // Обращение к this — если объект мёртв, ASan поймает
                u64 freed = want < used ? want : used;
                used -= freed;
                return freed;
            }, 10});
    }
    ~FakeCache() {
        if (unregister_on_destroy) {
            GetMemoryGovernor().UnregisterDonor(handle);
        }
    }
};

int main(){
    auto& g = GetMemoryGovernor();
    g.Initialise(8ULL<<30);

    // Сессия 1: игра запущена, кэш зарегистрирован
    {
        FakeCache cache{g, /*fixed=*/true};
        u64 got = g.Reclaim(100ULL<<20);
        printf("session alive: reclaimed %llu MiB\n",(unsigned long long)(got>>20));
        assert(got > 0);
    } // кэш уничтожен (пользователь нажал "назад"/выход)

    // Сессия 2: новая попытка -> раньше здесь был use-after-free
    u64 after = g.Reclaim(100ULL<<20);
    printf("after cache destroyed: reclaimed %llu MiB (must be 0, no crash)\n",
           (unsigned long long)(after>>20));
    assert(after == 0);

    // Повторная регистрация работает
    {
        FakeCache cache2{g, true};
        u64 got2 = g.Reclaim(50ULL<<20);
        printf("second session: reclaimed %llu MiB\n",(unsigned long long)(got2>>20));
        assert(got2 > 0);
    }

    // ClearDonors тоже безопасен
    g.ClearDonors();
    assert(g.Reclaim(10ULL<<20) == 0);

    printf("\nNO USE-AFTER-FREE\n");
}
