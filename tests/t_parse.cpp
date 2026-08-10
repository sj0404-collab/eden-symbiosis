#include <cassert>
#include <cstdio>
#include "common/symbiosis/mali_tuning.h"
using namespace Symbiosis;
int main(){
    const u32 v13=(1u<<22)|(3u<<12);
    // Реальные имена, которые рапортует Vulkan на Mali
    const char* names[]={"Mali-G610 MC6","Mali-G610","Mali G610","Mali-G78","ARM Mali-G610 MC6",
                         "Mali-G715-Immortalis MC11","Mali-G57 MC2","Mali-T860"};
    for(auto n:names){
        auto t=MaliTuning::Identify(n,true,16,v13);
        printf("%-28s -> %-22s fragile=%d\n",n,ToString(t.generation),(int)t.fragile_parallel_compile);
    }
}
