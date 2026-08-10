# Eden Symbiosis

A fork of the [Eden](https://git.eden-emu.dev/eden-emu/eden) Android emulator, tuned for
**ARM Mali GPUs on 8 GB devices where no custom Vulkan driver can be installed**.

Target hardware for this work: **Mali-G57 MC2 / MediaTek MT6789, 8 GB RAM (~5 GB usable)**.

---

## Why this fork exists

Stock Eden assumes a desktop-class GPU or an Adreno with replaceable drivers. On Mali:

- **OpenGL is unavailable.** Eden's GL renderer requires desktop OpenGL 4.6
  (`gl_device.cpp:163` throws otherwise); Android offers GLES 3.2. Vulkan is the only path.
- **No custom drivers.** Unlike Adreno, there is no widely deployed Turnip/adrenotools
  equivalent. The vendor blob is all there is.
- **Eden has no Mali knowledge.** `vulkan_device.cpp` carries a large Qualcomm quirk block;
  for ARM there is nothing, even though a Bifrost G71 and an Immortalis G720 differ enormously.

## Settings are requests, not guarantees

The core insight behind this fork. Between a toggle and the GPU sit silent filters that
never report back, so a setting can stay on while doing nothing:

| Setting | Gate | Effect on Mali |
|---|---|---|
| `astc_recompression` | `!device.IsOptimalAstcSupported()` — `maxwell_to_vk.cpp:248` | **Dead.** Mali decodes ASTC in hardware, so the value is read and discarded. |
| `force_max_clock` | `ShouldBoostClocks()` — `vulkan_device.cpp:947` | **Dead.** The allow-list names AMD, NVIDIA, Intel, Qualcomm, Samsung. ARM is absent. |
| `use_vsync` | `ChooseSwapPresentMode()` — `vk_swapchain.cpp:44` | **Renegotiated silently** against surface capabilities; caps the frame rate. |
| `use_asynchronous_shaders` | `vk_pipeline_cache.cpp:353` | Survives, but worker count collapses to 1 on fragile drivers. |

The **launch report** (Diagnostics → Launch report, or the overlay button next to the FPS
counter) states, per setting, whether it reached the renderer, what it became if not, and
which line of code decided that.

---

## Repository layout

```
patch/
  symbiosis/              C++ layer, ~6500 lines (src/common/symbiosis/)
  shaders/                present_retro.frag
  android/                Kotlin fragments, adapters, utils, layouts, strings
  native_symbiosis.cpp    JNI bridge, 53 entry points
  upstream_changes.patch  git diff against upstream Eden
tools/
  apply_patch.sh          restores the tree into a fresh Eden checkout
  setup_env.sh            JDK 17 + Android SDK/NDK + swap
  run_build.sh            gradle assembleLegacyDebug
tests/
  t_*.cpp                 host tests for the C++ layer
  SetupPageTest.kt        setup-page visibility logic
  test_l10n.py            Russian localisation checks
docs/
  BUGS.md                 every bug found, with evidence
```

## Building

```bash
bash tools/setup_env.sh                                   # toolchain + swap
git clone --depth 50 https://git.eden-emu.dev/eden-emu/eden.git /work/eden
bash tools/apply_patch.sh                                 # apply this fork
bash tools/run_build.sh                                   # ~31 min
```

Requires ~6 GB swap: the Kotlin compiler is OOM-killed on 2 GB RAM without
`kotlin.compiler.execution.strategy=in-process`.

## Running the tests

```bash
g++ -std=c++20 -O1 -o /tmp/t tests/t_audit.cpp && /tmp/t
kotlinc tests/SetupPageTest.kt -include-runtime -d /tmp/t.jar && java -jar /tmp/t.jar
cd <eden>/src/android/app/src/main/res && python3 test_l10n.py
```

## Honest status

Everything here is verified on the host — unit tests, `llvm-nm` symbol checks,
`aapt2` resource dumps, and a clean re-apply of the patch onto a fresh clone.
**None of it has been tested on physical Android hardware**, because none is available
in the build environment. Where a conclusion is a guess, it is labelled as one.

## Licence

GPL-3.0-or-later, matching upstream Eden.
