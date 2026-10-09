# EZ2 VR port — session handoff (2026-10-03)

## Update (2026-10-09, wear-test) — VR scene renders in-headset; launcher path holds focus

Wear-tested with a human in the Quest 3S (screen25.png, ez2vr13.log). The
diagnostic scene renders in stereo with rock-solid head tracking, no ANR on
button presses, and the full launcher->engine path now holds FOCUSED
indefinitely (one immersive transition per launch, no cycling).

Bugs found by the wear-test, all fixed and verified same session:
- Projection matrix had three misplaced elements (vr_scene.cpp) -> quad
  never rasterized (clear color only, live session). Caught by rendering
  lineMajor raw: perfect B&W grid.
- Eye/image indexing mismatch (image-major lookup vs eye-major store) put
  eyes in each other's images on some frames.
- Floor at LOCAL y=0 == head height at tracking start (measured y~0.001):
  camera inside the ground plane. Floor now pinned 1.6 m below the head.
- Fog permanently off: with it live every fragment came out fogColor
  (vDist viz: white everywhere) despite proven poses/matrices/varyings.
  Undiagnosed driver interaction; fog was only a distance cue.
- uTime now CPU seconds (ns-through-float-sin is garbage on mobile GPUs);
  lines widened to 3/2 px; marker to 3.5 m; input queue drained on the XR
  worker (undrained controller input raised a real system ANR dialog).
- The 4-second focus loss on the launcher path was the launcher's own
  onStop finish(), not the runtime: VrShell resumes home when the launcher
  task dies, even with the engine in its own task. The panel now stays
  stopped (finish only if the engine never covers us), and
  EZ2EngineActivity got the taskAffinity the code always claimed it had.
  The 2026-10-03 cycling does not recur.

Committed + pushed: engine `7c074baa` (wear-test fixes) on
feat/sourcevr-parity, launcher `ba167b7` (stay-alive + affinity) on main.
55/55 launcher tests green.

Still open (unchanged): engine-through-the-seam rendering (toggles
external-FBO path, not in this tree), content polish (npc_citizen convars,
ambient wavs, flat-shot checker squares).

## Update (2026-10-09, later) — VR focus stall FIXED; session reaches FOCUSED

The READY-forever stall was self-inflicted, not the runtime declining. The
native frame loop waited for VISIBLE/FOCUSED before its first `xrWaitFrame`,
but the `xrBeginSession` contract says the app "should then start its frame
loop", and `xrWaitFrame` is legal in every running state (only a non-running
session errors). A runtime that advances the session in response to frame
cadence never had a reason to move a session that never pumped a frame.
Fix (`launcher/android/vr_main.cpp`, `EzVrRun`): pump wait/begin/end from
`sessionBegun` on; `shouldRender` gates the draw, not the loop. Plus a
"frame pump alive" pacing log for the spun-but-invisible phase, and event
type 52 is now named (`INTERACTION_PROFILE_CHANGED`, harmless).

Device proof (`ez2vr11.log` / `ez2vr12.log`, `screen8.png`), Quest 3S, nobody
wearing it: UNKNOWN→IDLE→READY→begun→frame pump→first present→SYNCHRONIZED→
VISIBLE→FOCUSED, all within ~140 ms of begin. 49+ frames submitted, Java sees
`EZ_XR_RUNNING` ("XR loop is presenting", no flat fallback). Focus drops to
VISIBLE ~4 s later when the display sleeps — expected off-head; a wear-test
should hold FOCUSED. Session survives 90 s+ with zero errors.

Two things found along the way:
- The old default (`EZQUEST_VR_TRY_ENGINE` unset = boot full engine) crashed
  the `:engine` process 0.7 s after first present: `LauncherMainAndroid`
  SDL-inits video, which cannot succeed under the bare NativeActivity
  (`SDL_Init(SDL_INIT_VIDEO) failed ... include SDL_main.h`), then a JNI
  `CallStaticObjectMethod` with NULL jclass → ART abort → SIGABRT, killing
  the healthy XR loop with it. Default is now opt-in (`=1` to re-arm);
  TRY_ENGINE is for integration experiments only, after the toggles
  external-FBO path lands (not in this tree — see the comment in
  `ezquest_vr_engine.cpp`). VR mode currently presents the built-in
  diagnostic scene; engine-through-the-seam rendering is the next mountain.
- `EZ2EngineActivity` javadoc updated (the old "runtime declining" theory is
  corrected where it stood); fallback message now points at the new log lines.
- Launcher tests 55/55 green. Engine rebuilt (2 files), staged, APK
  reassembled + reinstalled. Nothing committed anywhere yet (still true).

## Update (2026-10-09) — SDK_* shader aliases VERIFIED on device

The 10 `BEGIN_INHERITED_SHADER` SDK_* aliases (written 10-04, engine rebuilt
13:02, staged, APK 15:30) were finally exercised on the headset today and they
work. Flat boot (`--es boot_mode flat` + `auto_launch`): engine boots clean
(3410 SRCENG lines, **zero** error/fail/missing/overflow lines), signon
completes through "Redownloading all lightmaps", and the intro map renders
fully textured in the 2D panel — night sky + aurora, radio towers, truck,
buildings, rocks, road, props. No magenta/black checker anywhere
(`screen6.png`, `ez2vr10.log`). Only benign warnings: `$basetexture`
non-texture requests on skybox + dev materials. Minor: 5 small dark-checker
squares top-right of the panel in the screenshot — unidentified, non-fatal.
Installed APK (v1, lastUpdateTime 2026-10-04 15:30:44) hash-matches the
on-disk 15:30:19 build; build-tree/staged/in-APK `libstdshader_dx9.so` are
byte-identical (SHA256 `32EB9A62…ADC1C2B`) and contain all 10 SDK_ names.

Next: the flat game is visually complete. Remaining big items are (a) the VR
session-focus stall (READY never advances — orthogonal, unchanged), (b)
committing both repos (nothing committed since `876e79c0`), (c) non-fatal
content gaps (`npc_citizen_*` convars, some ambient wavs).

## Update (2026-10-04) — CUtlRBTree SOLVED; engine is IN-GAME with audio

**The flat path now boots all the way into a running map.** Response scripts
parse clean, listen-server signon completes, lightmaps download, a map runs
and renders (EZ2 HUD + intro drive visible in the 2D panel), audio plays.

### How CUtlRBTree was diagnosed (gdbserver, not the unwinder)

`_Unwind_Backtrace` captured only its own frame — cross-DSO unwind from
liblauncher into libtier0 fails at runtime despite both libs shipping
`.eh_frame(_hdr)` + `PT_GNU_EH_FRAME`. Unexplained; not worth chasing,
because the process **stays alive** at the SDL "Engine Error" dialog, so
gdbserver attach after the fact gives the full stack anyway:

```
run-as com.ez2vr.launcher sh -c 'cat /data/local/tmp/gdbserver > files/../../gdbserver'  # run-as cannot exec /data/local/tmp
gdbserver --attach :5039 <engine pid>     # via run-as, from the app dir
adb forward tcp:5039 tcp:5039
gdb: set solib-search-path <staged jniLibs>; target remote :5039; thread <SDLThread tid from logcat>; bt 40
```

Frame #39: `CUtlMap<char const*, Criteria, short>` at
`defaultresponsesytem+48` — the response-system criteria dict (16-bit short
index, 32767 cap) overflowing.

### Root cause (two bugs, one fix + one robustness guard)

EZ2's `scripts/talker/npc_citizen.txt` uses Mapbase rule keyword
`applyContextToSquad`. This engine's `CResponseSystem::ParseRule` didn't know
it, so the token fell into the inline-criteria branch, which ate the next
line's `response` keyword as a criterion value — sliding past the rule's
closing `}` and consuming text until the next `rule` token. There it wedged:
ParseRule treats `rule` as an inline criterion, ParseOneCriterion ungets it
as a root command, same token ping-pongs forever, generating
`[CitizenAllyKilled000]`…`[CitizenAllyKilled32766]` until the dict overflowed.
Tell-tale log pattern: criterion names whose numeric suffix tracks the global
insert count exactly.

Fix (engine, uncommitted, all `#ifdef EZ2`): ported Mapbase's
`APPLYCONTEXT_*` flags end-to-end — `AI_Response::m_iContextFlags`
(AI_Criteria.h/.cpp, incl. `Init` int overload), `Rule::m_iContextFlags`
(AI_ResponseSystem.cpp), `applyContextToSquad`/`applyContextToEnemy` parse
keywords, squad/enemy/world/self application in
`CAI_Expresser::SpeakDispatchResponse` + `ParseApplyContext`
(ai_speech.cpp/.h). Two deliberate deviations from upstream, both noted
inline: fixed EZ2's player-squad iterator bug (it iterates
`pNPC->GetSquad()` in the player branch) and guarded a `Q_strstr()+1`
null-deref. Also added a ParseRule guard: a root command where an inline
criterion is expected now ends the rule with a warning instead of
infinite-looping (the whole crash class, not just this keyword).

### Second failure after RBTree: client signon

`Client missing DT class CDisplacerPistol` → CreateDecoders failed →
disconnect. Server registers `DT_DisplacerPistol`; client had no stub. Fixed:
added `STUB_WEAPON_CLASS( weapon_displacer_pistol, DisplacerPistol, ... )`
to `c_weapon__stubs_hl2.cpp` under `#ifdef EZ2` (mirrors source-ez2). The
other three ez2 server DTs (EZ2_Player, OICW, PointDetonatable) already had
client sides.

### New blocker level: missing Mapbase shaders (visual, non-fatal)

Map runs but most EZ2 surfaces render magenta/black checker. Unknown shaders:
`SDK_LightmappedGeneric`, `SDK_UnlitGeneric`, `SDK_VertexLitGeneric`,
`SDK_DecalModulate` (Mapbase; stdshader_dx9 has only stock). Debug*
shaders are dev-only, ignore. Next milestone: port or alias the four SDK_*
shaders. Also non-fatal: `npc_citizen_*` convars unregistered, some ambient
wavs missing, `game_sounds_weapons_css.txt` absent (expected).

### Repo state

- Engine: builds clean; two fixes above are uncommitted on top of the
  previously-uncommitted backtrace/EZDIAG/EZQUEST_VR_ENABLE work. Nothing
  committed or pushed since `876e79c0`.
- Launcher: tests 54/54 green; APK rebuilt+installed with both fixes.
- HANDOFF's "current blocker" section is now historical — superseded here.


Where the work stands, what is proven, what was ruled out, and what is next.
Written so a fresh session can resume without re-deriving any of it.

## TL;DR

**Option B is done and it works.** The flat-SDL boot path is ported, runs on the
headset, and **renders**. The engine boots monoscopic, loads all 30 native modules
including the EZ2 `libclient.so`/`libserver.so`, mounts the EZ2 mod, and draws
Source's loading screen — verified by screenshot. The M2 port is therefore
verifiable on device for the first time, which was the entire point of B.

It now dies on a specific, narrow engine error deep in content load:
`Engine Error: CUtlRBTree overflow!` (see "Current blocker"). That is ordinary M2
work, not a blocker to the approach.

The VR/OpenXR question is **unchanged and now orthogonal**: `EZ2EngineActivity`
polls the XR loop and hands off to the flat path on its own, so both paths are
exercised from one button press and the OpenXR question no longer gates anything.

**Still worth doing eventually:** the EZ1 control experiment (option A) — was EZ1's
VR path ever rendering on this headset?

## Repos

| Path | What it is | State |
|---|---|---|
| `C:\Users\neilr\EZ2Launcher` | Android launcher (Gradle). Git repo, 1 commit. | **8 uncommitted entries** |
| `C:\Users\neilr\ez1-quest-engine` | Source/Mapbase engine + Quest port. Git repo. | **clean**, 4 new commits |
| `C:\Users\neilr\source-ez2` | Upstream EZ2 reference (`ez2vr/contract` branch). | read-only reference |

Device: **Quest 3S** (`panther`), Android 14, VRS 207, 11 GB free of 106 GB.
Package: `com.ez2vr.launcher`.

## What is done and verified

### Engine (`ez1-quest-engine`, 4 commits, tree clean)

Branch `feat/sourcevr-parity`. Builds and links for **aarch64 (NDK r20b, API 21)**
under `--build-games=ez2`; `hl2` and `ez1` also rebuild from scratch, so no shared-file
edit regressed the existing profiles.

- `3095009a` — Windows-host ARM64 cross-compile (xcompile.py win32 clang shim,
  togles GL shims, vendored `external/openxr`).
- `9dff40ce` — OpenXR session-focus fixes to the EZ1 launcher/manifest.
- `7bf8eb5d` — the `ez2` waf profile + ez2vr convar contract + the one-APK CI workflow.
- `876e79c0` — **M2 roster port + minimal mapbase/AI backplane.** ~7,600 lines.

Notable content of `876e79c0`:
- Copied EZ2 player/weapons/client roster from `source-ez2`.
- Ported the Mapbase AI backplane: `GetLastSpokeConcept`, `GetRealTimeSpeechComplete`,
  `AI_CriteriaSet::MergeSet`, `ReAppendContextCriteria`, the `AI_CriteriaSet` overloads of
  `Speak`/`SpeakFindResponse`, `SpeakDispatchResponse` + `$context` substitution,
  `IsTalkingInAScriptedScene` chain, `GetContextValue(name)`, 3-arg `AddContext`,
  `Weapon_HandleEquip`, `JustStartedFearing`, `GetPlayerSquad`, `CanAutoSwitchToNextBestWeapon`,
  `IsSilentCommandable`, `AI_Response::GetRule`, `IsDisplacementImpossible`, `UTIL_EntitiesAtPoint`,
  EZ_CHALLENGE enum, TLK_ concepts, `CDisplacerSink`, dual-wield accessors, and the SLAM
  glow chain end-to-end.
- **Both trees turned out to be Mapbase**, so this was a version delta, not a port from
  scratch. 6 of the 9 initial blockers were near-free.

### Launcher content (`EZ2Launcher`, 9.0 GB on device)

Verified exact match against source:

| Depot path | Source | Files | Size |
|---|---|---|---|
| `shared/EntropyZero2/` | `EntropyZero2/entropyzero2/` | 836 | 6,489 MB |
| `shared/hl2/` | `EntropyZero2/hl2/` | 671 | 2,677 MB |
| `shared/platform/` | `EntropyZero2/platform/` | 250 | 8.6 MB |

All four conditions `EZ2ContentPresence.check()` tests are satisfied.

**Layout note:** EZ2 bundles its own `hl2/` and `platform/`, so Half-Life 2 is **not**
needed. The app's own status text tells the user to point the picker at the folder
containing `Half-Life 2/` **and** `EntropyZero2/`, which on this install means a
**39 GB** import that does not fit in 20 GB — and would copy the Steam *install root* as the
mod dir, which has no top-level `gameinfo.txt`, so the presence check would fail anyway.
**The launcher's import guidance is wrong for a self-contained EZ2 install and should be
fixed.**

### Launcher boot path (installed on device)

`app-debug.apk`, 124.4 MB, 31 native libs declared/verified/packaged, all AArch64
(`e_machine=0xB7`). The `.load` graph is a real manifest now, parsed on device and
checked against the installed libs — previously the gate only required a file to *exist*.

## Device evidence (the important part)

```
ANativeActivity_onCreate: starting XR worker
instance created with 3 extensions
EGL 1.5 / GLES 3.2 context
swapchain 1680x1760 (recommended 1680x1760, scale 1.00)
action set attached (17 suggested bindings)
scene ready: 6 targets, 1680x1760
session state: UNKNOWN(0) -> IDLE(1) -> READY(2)
session begun (primary stereo)
EZQuest-VR: waiting for session focus: begun=1 rendering=0 state=READY(2)   [forever]
```

- Meta runtime **is** reachable (RIPC `HandInputDataServer`, controller + hand input arriving).
- Memory flat at PSS 84 MiB. **Zero crash and zero ANR reports.**
- So: content gate, staging, 31-lib verification and `System.loadLibrary` all pass. Only
  the session handoff fails.

## Ruled out — do not re-try these

| Theory | Verdict | Why |
|---|---|---|
| Launcher invisible in VR shell | **Real bug, fixed** | `com.oculus.intent.category.VR` on the *2D* launcher made the shell run a full immersive transition (`isPassthroughTransition: false`). Symptom: Meta loading screen → home, window "visible" with `buffer=0x0`. Removed the category. |
| `launchMode="singleTask"` caused duplicate tasks | **Wrong** | It removed the duplicate task, but the focus cycling did not stop. EZ1 (proven) *has* `singleTask`, so it was restored. |
| Engine must call `xrWaitFrame` to leave `READY` | **Wrong, spec-checked** | `XrSessionState(3)`: "After returning from the xrBeginSession call, the runtime may then immediately transition forward through the `XR_SESSION_STATE_SYNCHRONIZED` state." The runtime declines; the app cannot force it. `xrWaitFrame` from `READY` returns an error. |
| Manifest missing OpenXR permissions / runtime-broker `<queries>` | **Wrong** | Identical to the EZ1 reference, and a session could not exist if the broker were unreachable. |
| `adb push` under-delivered → broken APK | **Wrong** | `adb push` silently aborts on `secure_mkdirs` denial (see traps), but the packaged libs were verified complete. |

## What DID fix something

The launcher no longer stays alive behind the engine. `startActivityForResult` → `startActivity`,
plus an `onStop()` that finishes once the engine covers it (10 s backstop), plus
`CLEAR_TOP|SINGLE_TOP`. Effect measured on device: the shell's `Switching to immersive app`
cycling **stopped** (one switch at launch, then silence, where before it cycled every 20–70 s).
It was not the cause of the session stall, but it was a genuine contention bug.

Accepted tradeoff: `onEngineReturned()` had no return trip left, so engine-crash
detection moved to the **next** launcher open via a persisted crash-report baseline in
`SharedPreferences`. A crash is now reported one launch late.

## Current blocker (flat path, content load)

```
Engine Error
CUtlRBTree overflow!
```

`public/tier1/utlrbtree.h:693`. Fires when `m_Elements.Grow()` fails to yield a
slot while allocating a tree node. In a release build the guarding `Assert` at
:690 is compiled out, so a failure or corruption of `m_LastAlloc` falls straight
through to `Error()`. It happens immediately after sound-script loading.

**Not yet diagnosed — needs a stack.** No minidump: Breakpad initialises
(`Using Breakpad minidump system. Version: 0 AppID: 1583720`) but only catches
signals, and `Error()` is a clean exit, so there is nothing in dropbox or
`/data/tombstones`. Options, cheapest first:

1. Temporarily un-NDEBUG the tier1 build and let the `Assert` at :690 fire with
   file/line — it is already in the tree and names the condition.
2. Add a `Msg` ring around the sound-emitter / scripts load to bisect.
3. Attach gdbserver — viable because the build is debuggable.

Two adjacent oddities from the same run, probably related:

- `server.so loaded for "Half-Life 2"` — the EZ2 server DLL self-identifies as
  HL2. Either `GameName()` is wrong in the ez2 profile or `gameinfo.txt` is not
  being read. Cheap to check and worth checking before the RBTree, because a mod
  resolving to the wrong game directory would also explain missing scripts.
- Content gaps logged, non-fatal so far:
  `scripts/game_sounds_weapons_css.txt` missing (CSS = HL2/CS content, not in the
  EZ2 depot), and repeated
  `KeyValues Error: LoadFromBuffer: missing { in file scripts/npc_sounds_xen_flora.txt`.

## Five bugs found and fixed getting the flat path to render

All five were silent or misattributed. Each is now covered by a JVM test except
the last two, which are pinned by tests on the launcher side.

1. **The engine forced VR on for every boot**, so any flat boot rendered black.
   `sourcevr/ezquest_openxr_vr.cpp` had `ShouldRunInVR()` and
   `ShouldForceVRMode()` hardcoded `true`, and `engine/sys_dll2.cpp:1138` forces
   `m_bSupportsVR = true` on Android. With no XR frame loop nothing publishes
   tracking, so `GetEyeProjectionMatrix()` returned an **identity matrix** while
   the caller still set `m_bViewToProjectionOverride`, `GetDisplayBounds()`
   claimed 2880x1440, and the 3D pass rendered into per-eye targets nothing
   composites. Fixed with an `EZQUEST_VR_ENABLE` opt-out. **Opt-out only** —
   unset means on, so hl2/ez1 are bit-for-bit unaffected.
2. **`SOURCEVR_USER_LAUNCH_ARGS_PATH` pointed at a convar dump.** The engine opens
   that file and appends every whitespace-separated token to argv
   (`launcher/android/main.cpp:100-127`); it was pointed at `ez2vr_user.cfg`, so
   the engine appended the **14 words of its comment header** as command-line
   arguments. Symptom was a silent `exit(255)` ~66 ms into boot with no log
   output at all. Now set to empty.
3. **`VALVE_GAME_PATH` pointed at the write dir.** On Android
   `GetBaseDirectory()` (`launcher/launcher.cpp:256-261`) returns
   `getenv("VALVE_GAME_PATH")` under `#ifdef ANDROID` — it ignores both the
   computed `g_szBasedir` and any `-basedir` override. Pointing it at the write
   dir (which holds only `cfg/`) meant no content resolved, and the engine died
   on its first content load: `Can't find background image
   'materials/console/startup_loading.vtf'`. Now the **content root**, which also
   repairs the VR path's `-game` (it was resolving to `<writeDir>/EntropyZero2`).
4. **No `INTERNET` permission.** The engine opens a client socket during
   `Host_Init` even single-player offline; `net_ws.cpp:2633` turns the failure
   into a fatal `Couldn't allocate any client IP port`. It rendered the loading
   screen first, so this looked like a content problem.
5. **`getLibraries()` derived by string-chop** — `"liblauncher.so".substring(3)`
   is `"launcher.so"`, and SDL wraps entries as `lib<name>.so`, so it asked for
   `liblauncher.so.so`. Caught immediately because SDL's fail-loud dialog named
   the exact string. Lesson: `SDLActivity.getMainSharedObject()` (SDLActivity.java:138-147)
   builds `nativeLibraryDir + "/lib" + last(getLibraries()) + ".so"` itself.

## What the flat path is, and why it was cheap

`LauncherMainAndroid` (`launcher/android/main.cpp:256`) — the flat entry point —
was **already compiled into the same `liblauncher.so` the VR path uses and already
exported from it**. The flat path needed no engine work beyond the `EZQUEST_VR_ENABLE`
opt-out. EZ1 splits the two boot paths purely in Java, at
`LauncherActivity.bootMode()`, on the `com.ezquest.engine.BOOT_MODE` manifest
meta-data.

Note the two paths never ran in the same process before, so flat-path-only engine
code had **never been executed**: `GrabSourceMutex()` (`launcher.cpp:955`) already
returns true on Android, but the whole region between `LauncherMain`'s entry and
`CSteamApplication::Run` is cold. Expect more first-run surprises there.

Also: **EZ1's `ValveActivity2` gets the ordering wrong.** It calls `super.onCreate`
first and configures the environment afterwards, but `SDLActivity` starts the
engine on its own thread the moment the surface exists (`SDLActivity.java:589`)
and `nativeRunMain` calls `SDL_main` synchronously (`SDL_android.c:772`). Its
`setArgs("-game ...")` therefore races the boot. `EZ2FlatEngineActivity` does all
of it before `super.onCreate`, which needs an explicit `System.loadLibrary("launcher")`
first because the JNI shim binds by name.

## Device evidence (flat path, 2026-10-03)

```
V SDL     : Running main function LauncherMainAndroid from library .../lib/arm64/liblauncher.so
I SRCENG  : GetTotalMemory() = 7.58
I SRCENG  : prop ro.product.model=Quest 3S
I SRCENG  : EZDIAG: cmdline = "..." "-game" ".../shared/EntropyZero2" "-mobile" "+exec" "ez2vr_boot.cfg" ...
I SRCENG  : EZDIAG: base dir = .../ez2vr/v1/shared
I EZQuest-SourceVR: EZQUEST_VR_ENABLE=0 -> VR stereo path OFF
I EZQuest-SourceVR: Activate: ignored -- VR stereo path disabled by EZQUEST_VR_ENABLE
I EZQuest-SourceVR: init: VR stereo path disabled -- booting monoscopic
I SRCENG  : Using Breakpad minidump system. Version: 0 AppID: 1583720
I SRCENG  : LoadLibrary: pModule: client, path: .../lib/arm64/libclient.so
I SRCENG  : LoadLibrary: pModule: server.so, path: .../lib/arm64/libserver.so
I SRCENG  : server.so loaded for "Half-Life 2"
I SRCENG  : LoadLibrary: pModule: engine.so
I SRCENG  : KeyValues Error: ... scripts/npc_sounds_xen_flora.txt
I SRCENG  : CSoundEmitterSystem::AddSoundsFromFile: No such file scripts/game_sounds_weapons_css.txt
I SRCENG  : CUtlRBTree overflow!
```

Screenshot confirmed the Source loading screen rendering with "Loading..." before
the error dialog. PSS ~230 MB, no ANR, no native crash.

`EZDIAG:` breadcrumbs are **intentional, keep them** — four `Msg` lines in
`LauncherMain` (cmdline, base dir, chdir, entering `CSteamApplication::Run`).
They are what made bugs 2 and 3 findable at all: both produced a silent
`exit(255)` with no other output.

## Driving it without a human on the headset

The engine activities are `exported="false"`, so `am start` cannot reach them.
`EZ2LauncherActivity` **is** exported, and now honours two extras:

```powershell
adb shell am start -n com.ez2vr.launcher/.EZ2LauncherActivity `
    --ez auto_launch true --es boot_mode flat
```

`auto_launch` stages and starts the engine 1.5 s after the UI comes up;
`boot_mode` (`vr`|`flat`) sets and persists the mode. Anything other than `flat`
resolves to `vr`, so a typo cannot silently select the experimental path. This
addresses trap 9 rather than working around it by loosening `exported` on the
one component that loads native code.

There is also a **Switch to Flat/VR boot** button on the launcher, because
flipping modes through the manifest would otherwise cost a rebuild and reinstall
per experiment. The manifest meta-data `com.ez2vr.launcher.BOOT_MODE` (`vr`) is
only the default; the toggle wins, then the meta-data, then `vr`.

## Uncommitted work (`EZ2Launcher`)

```
 M app/build.gradle                                            (stageEngineLibs hookup)
 M app/src/main/AndroidManifest.xml                            (VR category fix, singleTask,
                                                               flat activity, INTERNET, BOOT_MODE)
 M app/src/main/java/.../EZ2EngineActivity.java                 (real native-graph verification,
                                                               XR poll + automatic flat fallback)
 M app/src/main/java/.../EZ2LauncherActivity.java               (launch lifecycle, boot-mode toggle,
                                                               adb auto_launch extra)
?? app/src/main/assets/ez2/native/                             (ez2vr.load, 31-lib manifest)
?? app/src/main/java/.../EZ2NativeGraph.java                   (parser + completeness check)
?? app/src/main/java/.../EZ2BootMode.java                      (vr|flat resolution)
?? app/src/main/java/.../EZ2EngineEnv.java                     (shared env + argv builders)
?? app/src/main/java/.../EZ2NativeGate.java                    (native-graph gate, both paths)
?? app/src/main/java/.../EZ2FlatEngineActivity.java            (flat SDL engine host)
?? app/src/main/java/com/valvesoftware/ValveActivity2.java     (JNI shim: setenv/setArgs)
?? app/src/main/java/com/ezquest/engine/EngineActivity.java    (JNI shim: XR status)
?? app/src/main/java/org/libsdl/app/*.java                     (8 files, copied from the engine tree)
?? app/src/test/java/.../EZ2NativeGraphTest.java               (12 tests)
?? app/src/test/java/.../EZ2BootModeTest.java                  (6 tests)
?? app/src/test/java/.../EZ2EngineEnvTest.java                 (17 tests)
?? app/stage-engine-libs.gradle                                (staging + drift check)
```

Tests: **54/54 green** (31 pre-existing + 23 new).

The two JNI shim classes sit in foreign packages (`com.valvesoftware`,
`com.ezquest.engine`) on purpose: `liblauncher.so` binds those symbols by
fully-qualified name, so the declaring class has to match exactly. Each carries a
comment saying so. Do not rename or move them.

## The env contract, and one trap in it

`EZ2EngineEnv` builds two **disjoint** sets. `EZ2_*` is the forward contract for a
Mapbase EZ2 fork and nothing in the current engine reads it. `APP_DATA_PATH`,
`NATIVE_LIB_DIR`, `SOURCEVR_*`, `VALVE_GAME_PATH`, `EXTRAS_VPK_PATH` is what the
shipped nillerusr-derived engine actually reads — without it the engine mounts no
content root at all and comes up blind.

Two facts about that engine that are easy to get wrong:

- **`VALVE_GAME_PATH` is the base directory on Android**, and must be the
  *content root* — not the write dir, and not the mod dir. Content root because
  the engine's own `-game` synthesis builds `<VALVE_GAME_PATH>/<SOURCEVR_GAME>`.
  This single name is what the whole depot layout hangs off.
- **`SOURCEVR_USER_LAUNCH_ARGS_PATH` is not a cfg path.** The engine appends its
  whitespace-separated tokens to argv. Point it at a convar `KeyValues` dump and
  it silently injects the file's prose as command-line arguments (bug 2 above).
  It is now always empty.

## Commands that matter

```powershell
# engine: configure + build (must re-run configure after editing a .vpc)
cd C:\Users\neilr\ez1-quest-engine
$env:ANDROID_NDK_HOME="C:\Users\neilr\android-ndk-r20b"
$env:PATH="C:\Users\neilr\ndk-shims;C:\Users\neilr\android-ndk-r20b\toolchains\llvm\prebuilt\windows-x86_64\bin;$env:PATH"
.\waf.bat configure -T release --android=aarch64,4.9,21 --togles --disable-warns `
    --use-togl=1 --use-sdl=1 --build-games=ez2 --check-c-compiler=clang --check-cxx-compiler=clang++
.\waf.bat build -j 8          # ~19 min cold, ~20 s for a one-file change

# launcher
cd C:\Users\neilr\EZ2Launcher
$env:ANDROID_NDK_HOME="C:\Users\neilr\android-ndk-r20b"
.\gradlew.bat ":app:stageEngineLibs" "-Pez2.engineDir=../ez1-quest-engine"   # quote the -P arg in PowerShell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
adb install -r app\build\outputs\apk\debug\app-debug.apk

# device: boot the flat path with nobody wearing the headset (see "Driving it")
adb shell "pkill -f 'logcat -v threadtime'"   # a stale capture silently eats the next one
adb shell am force-stop com.ez2vr.launcher
adb shell "rm -f /sdcard/ez2cap.log; nohup logcat -v threadtime -b all -T 1 > /sdcard/ez2cap.log 2>&1 &"
adb shell am start -n com.ez2vr.launcher/.EZ2LauncherActivity --ez auto_launch true --es boot_mode flat
adb pull /sdcard/ez2cap.log .\ez2.log
Select-String -Path .\ez2.log -Pattern "SRCENG|EZQuest|EZDIAG" | % { $_.Line }

# device: what is actually on screen (traps 6 and 8)
adb shell input keyevent KEYCODE_WAKEUP
adb shell screencap -p /sdcard/s.png
adb pull /sdcard/s.png .

# device: durable capture (ring buffer rolls over; this does not)
adb shell "rm -f /sdcard/ez2cap.log; nohup logcat -v threadtime -b all > /sdcard/ez2cap.log 2>&1 &"
adb shell "grep -a 'EZQuest-VR' /sdcard/ez2cap.log | grep -a -v 'waiting for session focus'"

# device: session logs (the durable record)
adb pull /sdcard/Android/data/com.ez2vr.launcher/files/diagnostics/ ./diag
```

`stageEngineLibs` must run before any bootable `assembleDebug`: the staged `.so` files are
gitignored (`*.so`), so a clean clone or a wiped `app/build` yields a launcher-only APK —
which the engine gate refuses to start, by design.

## Traps discovered (cost real time; don't rediscover them)

1. **`scripts/waifulib/vpc_parser.py:115` does not strip comments.**
   `re.sub(r'//.*', '', f)` — the result is **discarded**. So a "commented out" `$File` is
   still parsed and compiled. Worse, the parser matches directive names as bare substrings,
   so prose merely *mentioning* `$File` is treated as a directive and crashes on the missing
   quote. Write omissions as prose; never as disabled directives.
2. **`adb push <dir>` silently under-delivers on Android 11+.** `secure_mkdirs` is denied under
   `/sdcard/Android/data/<pkg>/` and adb aborts the whole directory at the first error — 671
   files became 35. Fix: pre-create the tree with `adb shell mkdir` (which *is* allowed), then
   push **files** in batches, never directories. A file into an existing dir exits 0.
3. **PowerShell `Set-Content` writes CRLF**, and `tr '\n' '\0'` leaves a stray `\r` on every
   path — creating directories named `resource<CR>`. Symptom: device dir counts ~24 higher than
   source. Strip CR, and NUL-delimit (`xargs -0`) so paths with spaces survive
   (`entropyzero2/mapsrc/Chapter 0`).
4. **`adb shell` word-splits paths with spaces.** Do device work through a pushed script, not
   inline quoting.
5. **XML comments cannot contain `--`.** Broke the manifest build three times in one session
   (all three failed loudly). Check before building:
   ```powershell
   $m = Get-Content app\src\main\AndroidManifest.xml -Raw
   ($m -split "`n") | ? { $_ -match '--' -and $_ -notmatch '^\s*<!--' -and $_ -notmatch '-->\s*$' }
   ```
6. **PowerShell `>` corrupts binary output** (e.g. `adb exec-out screencap -p > file`).
   Screenshot via `adb shell screencap` + `adb pull`.
7. **`logcat` ring buffer rolls over** and lost the launch window twice. Use the on-device file
   capture above, or pull `diagnostics/` promptly.
8. **`screencap` returns 0 bytes when the display sleeps.** Wake first with
   `adb shell input keyevent KEYCODE_WAKEUP`.
9. The engine activity is `exported="false"`, so **`adb shell am start` cannot launch it** and
   `run-as` is refused too. Worked around with the `auto_launch` intent extra on the exported
   launcher — see "Driving it without a human on the headset".
10. In the engine wscript, **`'hl2'` must precede the `hl2mp` include dirs** — `game/server/hl2mp`
    shadows `game/server/hl2` for shared basenames, which silently cost `hl2_gamerules.cpp`
    `PhysCannonAccountableForObject`.
11. **A stale `logcat` capture silently eats the next one.** After `rm -f`, the old `nohup
    logcat` keeps writing to the unlinked inode, so `adb pull` returns the *old* content and the
    new run looks like it produced nothing. `pkill -f 'logcat -v threadtime'` first, and confirm
    the file is growing before trusting it.
12. **`SDLActivity` starts the engine on its own thread**, on `surfaceCreated`, and
    `nativeRunMain` calls `SDL_main` synchronously — so `super.onCreate()` returning does *not*
    mean the engine has not already booted. Configure the environment before `super.onCreate`.
13. **`GetBaseDirectory()` returns `getenv("VALVE_GAME_PATH")` on Android** and ignores
    `g_szBasedir` and `-basedir` entirely (`launcher/launcher.cpp:256-261`). There is no
    command-line way to move it; the env var is the only lever.
14. **Native stderr is unreachable.** `setprop log.redirect-stderr true` is denied on this
    headset, so `fprintf(stderr, ...)` and `exit(-1)` sites that do not also `Msg` are
    invisible. `Error()` and asserts *are* visible (tier0 logs to logcat as `SRCENG`), so
    "no output at all" rules those out and narrows the field a lot.

## Known-blocked / deferred engine work

Deliberately stubbed, each behind a named macro with the reason inline in the source:
`EZ2_VSCRIPT`, `EZ2_WILSON`, `EZ2_XEN_RECIPES`, `EZ2_FIRSTDRAW`, `EZ2_AISS_IGNORE_INPUT`,
`EZ2_SLAM_GRENADE`, dual-wield (accessors only; `CanDualWield()` false).

**M2 landmines for whoever continues:**
- `game/server/ez2/ez2_backplane.cpp` will collide with `item_healthkit.cpp`
  (`sk_healthvial_backpack`) and `grenade_hopwire.cpp` (`g_interactionXenGrenade*`) when those
  land. Each has a delete-when-landed comment.
- `#ifdef EZ2` adds a `virtual HandleInteraction` to `CBaseEntity`, shifting every later vtable
  slot in the ez2 hierarchy. Safe within a build; the ez2 game DLLs are **not** vtable-compatible
  with hl2/ez1 ones.
- The 4 SKIP-list files from TRANSPLANT-A §2 still need Mapbase versions, on `.cpp` **and**
  `.h` together, with the Android-era shims re-applied. The VPC comment was corrected: commits
  `3012d77e` / `d2f36b50` / `88c28d19` carry real compat shims, not "just a comment".

## Still to do on the launcher

- **Chase `CUtlRBTree overflow!`** — the current flat-path blocker, see top of file.
- Fix the content-import guidance for a self-contained EZ2 install (see above).
- `README.md` is stale — its status section still says the launcher ships launcher-only,
  and its "remaining work" list still describes the flat-SDL fallback as unported.
- `EZ2VrSettings.writeUserCfg` writes its debug snapshot into the **write** game dir, which
  the engine no longer reads now that `VALVE_GAME_PATH` is the content root. Harmless (it is
  a diagnostic artifact only, as its own javadoc says) but the two locations now differ.
- Nothing is committed in `EZ2Launcher`. The engine repo's 4 commits are **not pushed**, and
  the `EZQUEST_VR_ENABLE` change plus the `EZDIAG` breadcrumbs are uncommitted on top.

## Update (2026-10-03, later) — backtrace hook fixed and shipped

- Found and fixed a real bug in the fatal-backtrace work: EZQuestDumpBacktrace
  in launcher/android/crashhandler.cpp was a C++-mangled, hidden-visibility
  symbol, so tier0's dlsym(RTLD_DEFAULT, "EZQuestDumpBacktrace") could never
  have resolved it and every fatal would have silently skipped the backtrace.
  Now extern "C" + isibility("default"); verified T EZQuestDumpBacktrace
  in the dynamic symbol table of both the built and staged liblauncher.so.
- Engine rebuilt (full tree, because dbg.h changed), staged, launcher tests
  rerun green, pp-debug.apk rebuilt with the hook inside.
- The server.so loaded for "Half-Life 2" oddity is answered: the ez2 waf
  profile layers on server_hl2.vpc/client_hl2.vpc, so the HL2 game
  description is expected. Not a wrong-mod-dir symptom.
- Launcher hygiene items done: import guidance now covers a self-contained
  EZ2 install (pick the EntropyZero2/ folder itself), README status/remaining-
  work sections brought up to date (flat path exists, 54 tests, ez2 profile
  default), EZ2VrSettings.writeUserCfg javadoc notes it is diagnostic-only
  since VALVE_GAME_PATH is the content root.
- Device NOT connected at session end — the new-backtrace APK is not yet
  installed or exercised on the headset.