# Transplant scope — Option A: EZ2 game code into ez1-quest-engine (Android)

Target: `--build-games=ez2` producing Quest `libserver.so` / `libclient.so`
for Entropy: Zero 2, reusing the old tree's Android platform (waf NDK,
togles GLES, OpenXR slices D–H, APK pipeline). The desktop contract
(convars, `-mobile` hook) is proven; this doc covers only the game-code move.

## 1. What the old tree already has

- `game/server/{ez1,ez2,mod,Human_Error}/` — STARTER set only:
  - ez2/: 7 files (basepredator, bullsquid, zombigaunt, command point).
    These exist because EZ1 MAPS spawn them, not because EZ2 runs.
  - Human_Error/: drivable APC pair. `hlss_weapon_id.h` MISSING (new tree has it).
  - mod/: EMPTY (new tree's custom weapons/flaregun/smg2 live in
    source-ez2's mod list, not on disk there — same gap).
  - ez1/: 4 files (manhack toss, achievements reset, link compat).
- `game/server/server_ez1_extras.vpc` — the fragment pattern to copy:
  `$Configuration{}` stub + `$Folder` groups. waf parses it via vpc_parser.
- `game/server/wscript` + `game/client/wscript` — `games` dict +
  per-game DEFINES + include dirs. Extension point is one dict entry each.
- `game/server/gameinterface.cpp:842` — same `exec game.cfg` shape as
  SDK2013's `:852` / EZ2's `:892`. The mobile-hook hunk ports verbatim
  (plus `#include "tier0/icommandline.h"` — verify presence there).
- Client has NO ez2 dir yet; `client_ez1_extras.vpc` is its pattern.

## 2. File manifest to copy (source-ez2 → old tree)

From `source-ez2/sp/src/game/` (all paths relative to game/):

- server/ez2/*.cpp + *.h — 31 + ~20 files (achievements, surrender/beast
  behaviors, ez2_player, arbeit core, assassin, clonecop, crabsynth,
  flora, husk family, pitdrone, voltigore, wilson, xenhair/xentree,
  zassassin, zombigaunt, command point, OICW, displacer pistol).
  SKIP list (already present): basepredator, bullsquid, zombigaunt,
  prop_command_point — diff before overwriting, old tree's copies may
  carry Android fixes.
- server/Human_Error/hlss_weapon_id.h — missing old-tree.
- server/ez2vr/ez2vr_cvars_server.cpp + shared/ez2vr/ez2vr_cvars.h
  (from the ez2vr/contract branch) + client/ez2vr/ez2vr_cvars_client.cpp.
- client/ez2/* — 9 .cpp + headers (c_ez2_player, assassin, husks,
  crabsynth, wilson, OICW, pulse pistol, slam/gloop proxies, healthvial).
- client/Human_Error/{c_vehicle_drivable_apc,c_weapon_sniperrifle}.cpp.
- shared/ez2/* — 1 file (husk base shared header).
- shared/ez2vr/ez2vr_cvars.h (same header, include dir covers both DLLs).

NOT copied: anything under shared/mapbase/, vscript/, workshop_*,
Valve-side base files. The old tree's 2017-era base stays authoritative.

## 3. Build-system changes (mechanical)

server/wscript:
```python
'ez2': ['server_base.vpc', 'server_hl2.vpc', 'server_ez2_extras.vpc'],
# configure():
if game == 'ez2':
    conf.env.append_unique('DEFINES',
        ['EZ=1', 'EZ2=1', 'HE_APC=1', 'GLOWS_ENABLE=1', 'CSS_WEAPONS_IN_HL2=1'])
# build(): includes += ['ez2', 'Human_Error', 'ez2vr']  (plus shared/ez2vr
# via the vpc $AdditionalIncludeDirectories, mirroring server_ez2.vpc)
```
New `game/server/server_ez2_extras.vpc` (clone the ez1-extras fragment
pattern): EZ2 folder (full roster), Human_Error (+hlss_weapon_id.h),
EZ2VR folder (cvars server). Mirror for client:
`client_ez2_extras.vpc` + `'ez2'` entry + same defines in client/wscript.

Defines MUST match server_ez2.vpc/client_ez2.vpc (`EZ;EZ2;HE_APC;
GLOWS_ENABLE;CSS_WEAPONS_IN_HL2`) or `#ifdef` behavior drifts from desktop.

## 4. Known clash areas (the real work — compile-driven)

1. **No Mapbase backplane.** Old tree `game/shared/` has NO mapbase/.
   EZ2's ez2/*.cpp show no direct `#include "mapbase/..."` (checked),
   but they inherit Mapbase-modified bases (player, gamerules, weapon
   parse, squad/ally systems). Expect: unknown members on CBaseHLPlayer/
   gamerules, missing VScript/mapbase symbols at link. Resolution is
   per-error: stub the member, guard with `#ifdef EZ2`, or port the
   minimal backplane file. This is Layer 2 — budget weeks, not days.
2. **Engine-era drift.** Old tree base is 2017-leak-era; EZ2 code targets
   SDK2013+Mapbase v8. Player/weapon/render APIs drifted both directions.
   The 7 already-imported files are the canary: if THEY compile under
   `--build-games=ez1` today, the era gap is bridgeable file by file.
3. **CI lies.** Old-tree CI builds `hl2`, NOT `ez1` — the extras set
   (including our 7 canary files) may never have compiled. First NDK
   loop must be `--build-games=ez1` to establish the canary baseline
   BEFORE adding EZ2 files. Do not debug EZ2 files against a broken
   baseline.
4. **Missing shared pieces.** `mod/` custom weapons, `shared/ez2` husk
   header, workshop files — copy or stub per map need, not upfront.

## 5. Staged milestones (exit criteria)

- **M0 — canary baseline.** `--build-games=ez1` compiles+links under NDK
  TODAY (no changes). If red, fix extras includes first. Proves the
  fragment mechanism works in this tree.
- **M1 — ez2 profile skeleton.** `'ez2'` waf entries + empty extras vpc +
  ez2vr convar files only; `libserver.so`/`libclient.so` link. Proves
  wiring + our contract cross-compiles (ARM64, clang, libc++).
- **M2 — player + APC.** ez2_player, OICW/displacer, drivable APC,
  command point, mobile hook in old-tree GameInit. Boot test = menu;
  maps load with NPC warnings. Missing NPCs spawn as errors, not crashes.
- **M3 — roster expansion, map by map.** Add husk family → Arbeit maps;
  xenians → caves; zassassin/clonecop → late game. Each map's console
  error list is the work list.
- **M4 — XR input reads the convars.** Locomotion/turn/visor/FFR paths
  (engine-side, old tree sourcevr/) consume ez2vr_*. First in-headset
  milestone; needs M2 booting on device first.

## 6. Explicit non-goals

- No Mapbase full-port. No VScript, no workshop, no gamepadui, no
  episodic/CSS-weapon extras unless a target map demands them.
- No changes to the old tree's engine/renderer/XR platform in M0–M2.
- Desktop `source-ez2` stays the reference implementation; behavior
  questions get answered there first (5-minute MSVC loop beats NDK).
