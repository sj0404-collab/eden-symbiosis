#include <cstdio>
#include <fstream>
#include <filesystem>
#include "common/symbiosis/rom_tools.h"
using namespace Symbiosis;
const u64 MB=1024*1024;
int main(){
    std::filesystem::create_directories("/tmp/dbg");
    std::ofstream f("/tmp/dbg/broken.xci",std::ios::binary);
    f.seekp(500*MB-1); f.put(0);
    u32 magic=0x44414548; f.seekp(0x100); f.write((char*)&magic,4);
    u64 units=(2048*MB)/0x200-1; f.seekp(0x118); f.write((char*)&units,8);
    f.close();
    auto r=RomTools::Inspect("/tmp/dbg/broken.xci");
    printf("file_size=%llu MiB\nvalid_data_end=%llu MiB\nhealth=%s\nsummary=%s\n",
      (unsigned long long)(r.file_size/MB),(unsigned long long)(r.valid_data_end/MB),
      ToString(r.health), r.summary.c_str());
    printf("check: valid_data_end > file_size*4 ? %llu > %llu = %d\n",
      (unsigned long long)r.valid_data_end,(unsigned long long)(r.file_size*4),
      (int)(r.valid_data_end > r.file_size*4));
}
