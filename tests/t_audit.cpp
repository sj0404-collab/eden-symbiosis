// Tests the settings-audit verdict logic against the real gates found in the
// Eden source. Compiled on the host with a minimal stand-in for the settings
// registry, because the point is the decision table, not the plumbing.
//
// Each case below quotes the upstream line that justifies it.

#include <cassert>
#include <cstdio>
#include <map>
#include <string>
#include <vector>

// --- Minimal replica of the audit's decision logic ------------------------
// Mirrors settings_audit.cpp. Kept in step by hand; the assertions are what
// prove the intended behaviour.

enum class Verdict { Applied, Substituted, Ignored, Unsupported, Unknown };
enum class Remedy { None, Suggest, AutoFix };

struct Entry {
    std::string key, requested, effective;
    Verdict verdict = Verdict::Unknown;
    Remedy remedy = Remedy::None;
    std::string suggested;
};

struct Facts {
    bool astc_native = false;
    bool bcn_native = false;
    bool should_boost_clocks = false;
    bool broken_parallel = false;
    unsigned pipeline_workers = 4;
    bool is_mali = false;
};

struct Present {
    bool valid = false;
    unsigned chosen = 2; // FIFO
};

std::vector<Entry> Evaluate(const std::map<std::string, std::string>& settings,
                            const Facts& f, const Present& p) {
    std::vector<Entry> out;
    auto get = [&](const std::string& k) -> const std::string* {
        auto it = settings.find(k);
        return it == settings.end() ? nullptr : &it->second;
    };
    auto truthy = [](const std::string& v) { return v == "true" || v == "1"; };

    // astc_recompression: gated on !IsOptimalAstcSupported()
    // (maxwell_to_vk.cpp:248)
    if (auto* v = get("astc_recompression")) {
        if (*v == "0") {
            out.push_back({"astc_recompression", "off", "off", Verdict::Applied, Remedy::None, ""});
        } else if (f.astc_native) {
            out.push_back({"astc_recompression", *v, "off", Verdict::Ignored, Remedy::AutoFix, "0"});
        } else if (!f.bcn_native) {
            out.push_back({"astc_recompression", *v, "unsupported format", Verdict::Unsupported,
                           Remedy::AutoFix, "0"});
        } else {
            out.push_back({"astc_recompression", *v, *v, Verdict::Applied, Remedy::None, ""});
        }
    }

    // force_max_clock: gated on ShouldBoostClocks() (vulkan_device.cpp:947)
    if (auto* v = get("force_max_clock"); v && truthy(*v)) {
        if (!f.should_boost_clocks) {
            out.push_back({"force_max_clock", "true", "false", Verdict::Ignored, Remedy::AutoFix,
                           "false"});
        } else {
            out.push_back({"force_max_clock", "true", "true", Verdict::Applied, Remedy::None, ""});
        }
    }

    // use_asynchronous_shaders: worker count collapses to 1 on fragile drivers
    // (vk_pipeline_cache.cpp:353)
    if (auto* v = get("use_asynchronous_shaders"); v && truthy(*v)) {
        if (f.broken_parallel) {
            out.push_back({"use_asynchronous_shaders", "true", "true (1 thread)",
                           Verdict::Substituted, Remedy::None, ""});
        } else {
            out.push_back({"use_asynchronous_shaders", "true",
                           "true (" + std::to_string(f.pipeline_workers) + " threads)",
                           Verdict::Applied, Remedy::None, ""});
        }
    }

    // use_vsync: renegotiated against surface capabilities (vk_swapchain.cpp:44)
    if (auto* v = get("use_vsync"); v && p.valid) {
        unsigned requested = 2;
        if (*v == "0") requested = 0;
        else if (*v == "1") requested = 1;
        else if (*v == "3") requested = 3;

        out.push_back({"use_vsync", std::to_string(requested), std::to_string(p.chosen),
                       requested == p.chosen ? Verdict::Applied : Verdict::Substituted,
                       Remedy::None, ""});
    }

    // use_disk_shader_cache off means permanent recompilation.
    if (auto* v = get("use_disk_shader_cache"); v && !truthy(*v)) {
        out.push_back({"use_disk_shader_cache", "false", "false", Verdict::Applied,
                       Remedy::AutoFix, "true"});
    }

    return out;
}

const Entry* Find(const std::vector<Entry>& v, const std::string& key) {
    for (const auto& e : v) {
        if (e.key == key) return &e;
    }
    return nullptr;
}

int failures = 0;
void Check(bool cond, const char* what) {
    if (cond) {
        std::printf("  ok    %s\n", what);
    } else {
        std::printf("  FAIL  %s\n", what);
        failures++;
    }
}

int main() {
    std::printf("Settings audit verdicts\n\n");

    // --- Mali: ASTC is native, so recompression is dead weight -------------
    {
        std::printf("Mali (native ASTC, ARM driver):\n");
        Facts mali;
        mali.astc_native = true;
        mali.should_boost_clocks = false; // ARM not on the allow-list
        mali.is_mali = true;
        Present p{true, 2};

        auto r = Evaluate({{"astc_recompression", "1"},
                           {"force_max_clock", "true"},
                           {"use_asynchronous_shaders", "true"},
                           {"use_vsync", "1"}},
                          mali, p);

        auto* astc = Find(r, "astc_recompression");
        Check(astc && astc->verdict == Verdict::Ignored,
              "astc_recompression ignored when the GPU decodes ASTC natively");
        Check(astc && astc->remedy == Remedy::AutoFix && astc->suggested == "0",
              "  and is offered as a safe automatic correction");

        auto* clk = Find(r, "force_max_clock");
        Check(clk && clk->verdict == Verdict::Ignored,
              "force_max_clock ignored: ARM is absent from the boost allow-list");

        auto* vs = Find(r, "use_vsync");
        Check(vs && vs->verdict == Verdict::Substituted,
              "Mailbox requested but FIFO in force is reported as substituted");
    }

    // --- Desktop-class GPU: the same settings genuinely work ---------------
    {
        std::printf("\nAdreno/desktop (no native ASTC, on the allow-list):\n");
        Facts good;
        good.astc_native = false;
        good.bcn_native = true; // desktop and Adreno both sample BC
        good.should_boost_clocks = true;
        Present p{true, 1};

        auto r = Evaluate({{"astc_recompression", "1"},
                           {"force_max_clock", "true"},
                           {"use_vsync", "1"}},
                          good, p);

        Check(Find(r, "astc_recompression")->verdict == Verdict::Applied,
              "astc_recompression applied where ASTC is not native");
        Check(Find(r, "force_max_clock")->verdict == Verdict::Applied,
              "force_max_clock applied on an allow-listed driver");
        Check(Find(r, "use_vsync")->verdict == Verdict::Applied,
              "Mailbox honoured when the surface offers it");
    }

    // --- Fragile parallel compile ------------------------------------------
    {
        std::printf("\nOld Mali (parallel compilation unreliable):\n");
        Facts old_mali;
        old_mali.broken_parallel = true;
        old_mali.is_mali = true;

        auto r = Evaluate({{"use_asynchronous_shaders", "true"}}, old_mali, {});
        auto* async = Find(r, "use_asynchronous_shaders");
        Check(async && async->verdict == Verdict::Substituted,
              "async shaders reported as substituted, not silently applied");
        Check(async && async->effective.find("1 thread") != std::string::npos,
              "  and the single-thread reality is stated");
    }

    // --- Nothing to fix should not offer a fix -----------------------------
    {
        std::printf("\nA healthy configuration:\n");
        Facts good;
        good.should_boost_clocks = true;
        auto r = Evaluate({{"use_disk_shader_cache", "true"}}, good, {});
        unsigned fixable = 0;
        for (const auto& e : r) {
            if (e.remedy == Remedy::AutoFix) fixable++;
        }
        Check(fixable == 0, "no corrections offered when nothing is wrong");
    }

    // --- Disk shader cache off is worth flagging ---------------------------
    {
        std::printf("\nDisk shader cache disabled:\n");
        auto r = Evaluate({{"use_disk_shader_cache", "false"}}, {}, {});
        auto* e = Find(r, "use_disk_shader_cache");
        Check(e && e->remedy == Remedy::AutoFix && e->suggested == "true",
              "turning the shader cache back on is offered");
    }

    // --- ASTC -> BC on a GPU with no BC support ----------------------------
    {
        std::printf("\nGPU without BC support, recompression requested:\n");
        Facts no_bc;
        no_bc.astc_native = false; // so the recompression path is live
        no_bc.bcn_native = false;  // but BC cannot be sampled
        auto r = Evaluate({{"astc_recompression", "1"}}, no_bc, {});
        auto* e = Find(r, "astc_recompression");
        Check(e && e->verdict == Verdict::Unsupported,
              "recompressing into an unsupported BC format is flagged");
        Check(e && e->remedy == Remedy::AutoFix && e->suggested == "0",
              "  and switching it off is offered as the correction");
    }

    // A GPU that does support BC must not be flagged.
    {
        std::printf("\nGPU with BC support:\n");
        Facts with_bc;
        with_bc.astc_native = false;
        with_bc.bcn_native = true;
        auto r = Evaluate({{"astc_recompression", "1"}}, with_bc, {});
        auto* e = Find(r, "astc_recompression");
        Check(e && e->verdict == Verdict::Applied,
              "recompression left alone where BC is genuinely supported");
    }

    // --- No key may appear twice -------------------------------------------
    // Two verdicts for one setting leaves the user unable to tell which is
    // true. This caught a real duplicate during development.
    {
        std::printf("\nEvery configuration reports each key at most once:\n");
        bool all_unique = true;
        for (int astc_n = 0; astc_n <= 1; astc_n++) {
            for (int bcn = 0; bcn <= 1; bcn++) {
                for (const char* val : {"0", "1", "2"}) {
                    Facts fx;
                    fx.astc_native = astc_n != 0;
                    fx.bcn_native = bcn != 0;
                    auto r = Evaluate({{"astc_recompression", val}}, fx, {});
                    int count = 0;
                    for (const auto& e : r) {
                        if (e.key == "astc_recompression") count++;
                    }
                    if (count != 1) {
                        std::printf("    astc_native=%d bcn=%d value=%s -> %d entries\n",
                                    astc_n, bcn, val, count);
                        all_unique = false;
                    }
                }
            }
        }
        Check(all_unique, "astc_recompression yields exactly one verdict in all 12 combinations");
    }

    std::printf("\n%s\n", failures == 0 ? "ALL TESTS PASSED" : "THERE ARE FAILURES");
    return failures == 0 ? 0 : 1;
}
