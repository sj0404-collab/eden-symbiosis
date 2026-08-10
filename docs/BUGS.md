# Bugs found and fixed

Every entry states the evidence. Where a cause is unproven, it says so.
Bugs marked **(mine)** were introduced by this fork; the rest are upstream.

---

## v17 — the setup button disappeared, again

**Reported:** "Снова баг с общей папкой кнопка пропала" — the data-folder button was
missing from the first-run wizard for the third time.

My two previous attempts fixed the wrong thing. The first (v14) blamed
`pageSteps = { PageState.COMPLETE }`; the second (v15) changed it to `INCOMPLETE`.
Both were real problems, but neither was *the* problem, because the button kept vanishing.

### Actual cause: recycled ViewHolder state (mine, and upstream design)

`ViewPager2` is a `RecyclerView`, so one `SetupPageViewHolder` is reused across pages.
`SetupAdapter.onStepCompleted(pageFullyCompleted = true)` does this:

```kotlin
ViewUtils.hideView(binding.pageButtonContainer, 200)
ViewUtils.showView(binding.textConfirmation, 200)
```

and **`bind()` never undoes it**. The permissions page legitimately reports COMPLETE once
notifications are granted. When its holder is recycled for the data page, that page inherits
a hidden container: its buttons are created, and are invisible.

A second defect in the same method: `bind()` never called `removeAllViews()`, so re-binding
the same page (rotation, `notifyDataSetChanged`) appended a **second copy of every button**.

**Proof** — `tests/SetupPageTest.kt` models the adapter and fails on the old logic:

```
ViewHolder recycled from the permissions page to the data page:
  ok    permissions page shows "Done!"
  ok    data page did create its buttons
  FAIL  container visible again
  FAIL  "Done!" cleared
Same page bound twice:
  FAIL  still four buttons, not eight
```

After the fix all 12 assertions pass.

### Second cause: a page that collapses while still useful (mine)

Even with the reset, `pageFullyCompleted` hid the container wholesale — including the
data-folder button, which is *never* "done": pointing at a different folder is valid at any
time. `onStepCompleted` now collapses the page only when no button is still actionable:

```kotlin
if (pageFullyCompleted && !hasActionableButton()) { ... }
```

A button reporting `BUTTON_ACTION_UNDEFINED` (optional but live, which is exactly the folder
button) keeps the page open.

**Files:** `patch/android/adapters/SetupAdapter.kt`

---

## v16 — resolution off by one (mine)

The launch report showed `resolution_setup: 1` while the overlay said `0.5x`, and the
profile comment promised 0.75x. The real enum is:

```
ENUM(ResolutionSetup, Res1_4X=0, Res1_2X=1, Res3_4X=2, Res1X=3, Res5_4X=4, Res3_2X=5, Res2X=6, ...)
```

All **ten** `resolution_setup` values in `device_profiles.cpp` were one short of the value
their own comment described — `"1"` labelled "0.75x" is actually 0.5x. Every Mali profile
rendered blurrier than advertised.

Fixed per entry. The report now prints the scale (`1x (native)`), not the bare index; the
naked number is what hid the error.

## v16 — ASTC recompressed into an unsupported format (upstream)

`maxwell_to_vk.cpp:248` selects BC1/BC3 when recompression is enabled, **without checking
`textureCompressionBC`**. That this matters is proved by the branch 20 lines below, which
transcodes BCn away "on hardware that doesn't support BCn natively". Mali typically samples
ASTC but not BC — so the emulator can choose a format the GPU cannot sample.

**First fix was wrong and was reverted.** Overriding the format at the point of use
desynchronises three independent consumers — format selection (`maxwell_to_vk.cpp:248`),
size accounting (`texture_cache/util.cpp:611`) and the compressor
(`texture_cache/util.cpp:943`) — corrupting the upload buffer. Worse than the original bug.

Correct fix: normalise the *setting* once in `Device::Device()`, where BC support is known,
so all three consumers agree.

## v16 — three false statements in the launch report (mine)

- `astc_recompression: 0` was described as "textures are recompressed" — 0 means **off**.
- `use_speed_limit` at 100% was reported as "frames discarded on purpose" — 100% is normal
  speed, and flagging it sent the user chasing a non-problem.
- Resolution printed a bare enum index, meaningless to a reader.

---

## Earlier

| Version | Bug | Evidence |
|---|---|---|
| v12 (mine) | `SetWindowAdaptPass` returned before `layers.clear()`; stale descriptors → device lost → app dropped to the game list on any settings change | traced through `applySettings() → RefreshBaseSettings() → SetWindowAdaptPass()` |
| v11 (mine) | `ApplyCurrentOnStartup` re-applied the mode on every launch, silently overwriting manual edits — "quality settings do nothing to the FPS" | test: set 2 → after launch 3 (old) vs 2 (new) |
| v11 | `load/` never created, so mods were ignored; Android never calls `Common::FS::CreateEdenPaths()` | added `SharedDataDirectory.ensureLayout()`, 17 directories |
| v5 (mine) | Use-after-free: TextureCache registered a memory donor capturing `this` with no deregistration | ASan: `stack-use-after-scope in FakeCache::FakeCache` → after fix, `reclaimed 0 MiB (no crash)` |

---

## Open / unproven

- **Crash a few seconds into Blade Chimera (NSP).** Not reproduced; no device logs. The
  launch report now records the loader status and shows it as the first line, so the next
  occurrence should name its own cause.
- **20 FPS in menus vs 40 in game.** Not explained. Presentation logic is shared.
- **Firmware compression is impossible.** Measured on 200 MB: `xz -9` produced 209,768,460
  bytes from 209,766,400 — 2 KB *larger*. NCA content is encrypted, so it is incompressible.
