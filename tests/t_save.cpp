#include <unistd.h>
#include <cassert>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include "common/symbiosis/save_guard.h"
#include "common/symbiosis/symbiosis_log.h"
using namespace Symbiosis;
namespace fs=std::filesystem;

void mkfile(const std::string&p,const std::string&c){
    fs::create_directories(fs::path(p).parent_path());
    std::ofstream(p)<<c;
}
int main(){
    fs::remove_all("/tmp/sg"); 
    const std::string save="/tmp/sg/save", vault="/tmp/sg/vault";
    mkfile(save+"/0100/data.bin","progress-v1");
    mkfile(save+"/0100/sub/extra.bin","more");

    SaveVault::Configure(vault, 3);
    u64 b1=SaveVault::Backup(save,"0100ABC","World 1");
    printf("backup1: %llu bytes\n",(unsigned long long)b1);
    assert(b1>0);

    // Пользователь играет дальше
    mkfile(save+"/0100/data.bin","progress-v2-further");
    sleep(1);
    u64 b2=SaveVault::Backup(save,"0100ABC","World 2");
    assert(b2>0);

    auto list=SaveVault::List();
    printf("backups stored: %zu\n",list.size());
    assert(list.size()==2);
    printf("  newest first: %llu > %llu\n",(unsigned long long)list[0].timestamp,
      (unsigned long long)list[1].timestamp);
    assert(list[0].timestamp >= list[1].timestamp);

    // КАТАСТРОФА: пользователь очистил данные приложения
    fs::remove_all(save);
    assert(!fs::exists(save));
    printf("\n-- app data cleared, saves gone --\n");
    // Хранилище ВНЕ приватной папки — уцелело
    assert(fs::exists(vault));
    auto after=SaveVault::List();
    assert(after.size()==2);
    printf("vault survived: %zu backups intact\n",after.size());

    // Восстановление
    std::string err;
    bool ok=SaveVault::Restore(after.back(),save,err);  // самый старый = v1
    printf("restore: %d (%s)\n",(int)ok,err.c_str());
    assert(ok);
    std::ifstream f(save+"/0100/data.bin"); std::string got; std::getline(f,got);
    printf("restored content: '%s'\n",got.c_str());
    assert(got=="progress-v1");

    // Ротация: лимит 3 поколения
    for(int i=0;i<5;i++){ sleep(1); mkfile(save+"/0100/data.bin","gen"+std::to_string(i));
        SaveVault::Backup(save,"0100ABC","gen"); }
    int mine=0; for(auto&x:SaveVault::List()) if(x.title_id=="0100ABC") mine++;
    printf("\nafter 5 more backups, kept for this title: %d (limit 3)\n",mine);
    assert(mine<=3);

    // --- Анализатор крашей ---
    printf("\n=== crash analyst ===\n");
    GetLog().Clear();
    auto none=CrashAnalyst::Analyse();
    printf("clean log -> %zu findings (honest 'unknown')\n",none.size());

    LogError(LogArea::Driver,"quarantined 'system libvulkan' after 3 faults");
    LogWarning(LogArea::Driver,"fault #3 in 'system libvulkan': VK_ERROR_DEVICE_LOST");
    LogInfo(LogArea::Memory,"reclaimed 200 MiB of 300 MiB requested");
    LogInfo(LogArea::Memory,"reclaimed 150 MiB of 300 MiB requested");
    LogInfo(LogArea::Memory,"reclaimed 100 MiB of 300 MiB requested");
    auto f2=CrashAnalyst::Analyse();
    printf("after driver+memory events -> %zu findings\n",f2.size());
    assert(f2.size()>=2);
    printf("%s",CrashAnalyst::Describe(f2).c_str());
    assert(f2[0].confidence >= f2[1].confidence);  // отсортировано

    fs::remove_all("/tmp/sg");
    printf("ALL SAVE+CRASH TESTS PASSED\n");
}
