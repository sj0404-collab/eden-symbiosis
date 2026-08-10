#include <cassert>
#include <cstdio>
#include <filesystem>
#include "common/symbiosis/symbiosis_log.h"
using namespace Symbiosis;
int main(){
    const std::string dir="/tmp/sym_guard_test";
    std::filesystem::remove_all(dir);
    std::filesystem::create_directories(dir);
    CrashGuard::SetMarkerDirectory(dir);

    // Чистый первый запуск
    assert(!CrashGuard::PreviousRunCrashed());
    printf("clean first run: layer enabled\n");

    // Сессия началась и корректно завершилась
    CrashGuard::BeginSession();
    CrashGuard::EndSession();
    CrashGuard::SetMarkerDirectory(dir); // сброс кэша проверки
    assert(!CrashGuard::PreviousRunCrashed());
    printf("clean shutdown: layer stays enabled\n");

    // Сессия началась и процесс УМЕР (EndSession не вызван)
    CrashGuard::BeginSession();
    CrashGuard::SetMarkerDirectory(dir);
    assert(CrashGuard::PreviousRunCrashed());
    printf("after crash: layer auto-disabled -> app still launches\n");

    // Пользователь сбросил состояние
    CrashGuard::Reset();
    assert(!CrashGuard::PreviousRunCrashed());
    printf("after reset: layer re-enabled\n");

    std::filesystem::remove_all(dir);
    printf("\nCRASH GUARD OK\n");
}
