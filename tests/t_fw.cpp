#include <cassert>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <random>
#include "common/symbiosis/firmware_vault.h"
using namespace Symbiosis;
namespace fs = std::filesystem;

void mk(const std::string& p, u64 size){
    std::ofstream f(p, std::ios::binary);
    std::mt19937 rng(42); std::vector<char> buf(65536);
    u64 left=size;
    while(left){ u64 n=std::min<u64>(left,buf.size());
        for(u64 i=0;i<n;i++) buf[i]=char(rng()&0xFF);
        f.write(buf.data(),n); left-=n; }
}

int main(){
    const std::string dir="/tmp/fwtest";
    fs::remove_all(dir); fs::create_directories(dir);
    const u64 MB=1024*1024;
    // Имитация реальной прошивки
    mk(dir+"/sysmodule_a.nca", 2*MB);       // essential
    mk(dir+"/sysmodule_b.nca", 3*MB);       // essential
    mk(dir+"/shared_font_latin.nca", 6*MB); // font (маленький, оставим)
    mk(dir+"/shared_font_cjk.nca", 40*MB);  // font (большой, вырежем)
    mk(dir+"/applet_browser.nca", 90*MB);   // applet
    mk(dir+"/applet_album.nca", 60*MB);     // applet
    mk(dir+"/lang_extra.nca", 10*MB);       // language

    auto a = FirmwareVault::Analyse(dir);
    printf("total=%llu MiB entries=%u\n",(unsigned long long)(a.total_bytes/MB),a.entry_count);
    printf("  essential=%llu font=%llu applet=%llu lang=%llu prunable=%llu MiB\n",
      (unsigned long long)(a.essential_bytes/MB),(unsigned long long)(a.font_bytes/MB),
      (unsigned long long)(a.applet_bytes/MB),(unsigned long long)(a.language_bytes/MB),
      (unsigned long long)(a.prunable_bytes/MB));
    assert(a.entry_count==7);
    assert(a.applet_bytes >= 150*MB);   // оба апплета распознаны

    PruneOptions minimal{}; // ничего лишнего не держим
    u64 est = FirmwareVault::EstimatePrunedSize(a, minimal);
    printf("\nestimated after prune: %llu MiB (was %llu)\n",
      (unsigned long long)(est/MB),(unsigned long long)(a.total_bytes/MB));
    assert(est < a.total_bytes/2);   // экономия больше половины

    u64 freed = FirmwareVault::Prune(dir, a, minimal);
    printf("actually freed: %llu MiB\n",(unsigned long long)(freed/MB));
    assert(freed >= 150*MB);

    // Латинский шрифт ДОЛЖЕН остаться, иначе пропадёт текст
    assert(fs::exists(dir+"/shared_font_latin.nca"));
    assert(!fs::exists(dir+"/applet_browser.nca"));
    assert(fs::exists(dir+"/sysmodule_a.nca"));
    printf("latin font kept, applets gone, sysmodules kept: OK\n");

    // Упаковка в контейнер
    auto a2 = FirmwareVault::Analyse(dir);
    u64 packed = FirmwareVault::Pack(dir, "/tmp/fw.symfw");
    printf("\npacked container: %llu MiB from %llu MiB of files\n",
      (unsigned long long)(packed/MB),(unsigned long long)(a2.total_bytes/MB));
    assert(packed >= a2.total_bytes);  // без сжатия — не меньше
    assert(fs::exists("/tmp/fw.symfw"));

    fs::remove_all(dir); fs::remove("/tmp/fw.symfw");
    printf("\nALL FIRMWARE TESTS PASSED\n");
}
