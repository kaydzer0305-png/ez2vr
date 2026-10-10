# EZ2 VR launcher — Quest 2/3, PC-Steam content copy, single profile.
#
# WHAT THIS IS
# A clean-room launcher skeleton for Entropy: Zero 2 VR on Quest, modeled on the
# BEHAVIOR proven by SourceVR (profile -> content presence -> asset stage ->
# NativeActivity + engine env) without copying its code, blobs, or native libs.
#
# SOURCEVR TEMPLATE MAP (decompiled 0.1.27, for behavior reference only)
# - GameContentProfile.java:17,50 + ContentComponent.java:90 (EZ1 wiring)
#     -> EZ2Profile.java (id entropyzero2, AppID 1583720, dir EntropyZero2)
# - SteamCatalog.java:17-18 (EZ1 AppID 714070) -> EZ2Profile.STEAM_APP_ID
# - ContentPaths.java:164-208 (multi-profile resolve)
#     -> EZ2ContentPaths.java (single profile, shared/content/overlay/vr layout)
# - ContentPresence.java:227 (gameinfo.txt check) -> EZ2ContentPresence.java
# - ProfileAssetStage (blob manifests + native .load graph)
#     -> EZ2AssetStage.java (ships ONLY your own overlay/vr files)
# - MainActivity.java:30-65,167-213 (route-if-needed + engine env)
#     -> EZ2EngineActivity.java (EZ2_* env, -game EntropyZero2)
# - LauncherActivity + ImportActivity + VrAssetInstallActivity chain
#     -> EZ2LauncherActivity.java (pick folder -> stage -> launch)
#
# CONTENT (user-supplied from their own Steam install — never redistributed)
# In-app: launcher -> "Import from PC Steam folder" -> pick the folder that
# CONTAINS Half-Life 2/ and EntropyZero2/ (usually Steam/steamapps/common) —
# or, for a self-contained EZ2 install, the EntropyZero2/ folder itself,
# which bundles entropyzero2/, hl2/ and platform/ inside it.
# Copies over SAF; re-import picks up Steam updates (unchanged files skipped).
#   shared/EntropyZero2/gameinfo.txt  (or shared/entropyzero2/ for Linux copies)
#   shared/hl2/gameinfo.txt
#   shared/platform/
# Legacy/CLI path still works: adb push <steam>/EntropyZero2 <shared>/ where
# shared = <externalFiles>/ez2vr/v1/shared (path shown in-app).
#
# BUILD
#   cd EZ2Launcher
#   ./gradlew :app:assembleDebug          # APK -> app/build/outputs/apk/debug/
#   ./gradlew :app:testDebugUnitTest      # local JVM tests, no device needed
# Android Studio (Arctic Fox+) also works; it uses the same wrapper.
# Engine launch stays gated until native libs exist (see below) and logs
# exactly what is missing; with the staged engine libs the flat boot path
# renders on device (see HANDOFF.md for where the VR-port work stands).
#
# CI — one APK, engine included
#   .github/workflows/build-ez2-arm64.yml in ez1-quest-engine builds the whole
#   thing: waf compiles the engine for arm64, the collected .so files and the
#   native .load graph are injected into THIS Gradle project, and Gradle
#   assembles the sideloadable APK. Replaces the EZ1 pipeline's hand-rolled
#   javac/d8/aapt2/zipalign/apksigner steps (build-ez2-arm64.yml, not
#   build-android-arm64.yml — the proven EZ1 path is left runnable).
#   Requires EZ2Launcher to be published as a git repo; the workflow takes it as
#   the `launcher_repo` input and fails with an actionable message if unset.
#   `build_games` defaults to ez2: the `ez2` waf profile exists (TRANSPLANT-A
#   M1/M2), so the APK carries the EZ2 game DLLs.
#
# ENGINE ENV CONTRACT
#   setupEngineEnvironment() exports the EZ2_* names (the forward contract for
#   the Mapbase EZ2 fork) AND, via setSourceVrCompatEnv(), the APP_DATA_PATH /
#   SOURCEVR_* / VALVE_GAME_PATH names the CURRENT liblauncher.so actually
#   reads. Both are required today: the shipped engine reads no EZ2_* name, so
#   without the shim it mounts no content root and boots blind. The two sets are
#   disjoint, so the shim can be dropped once a liblauncher.so built from the
#   EZ2 fork reads EZ2_*.
#
# STATUS — what is done
# - Build works from a clean checkout (Gradle wrapper, gradle.properties,
#   local.properties for the SDK path).
# - SAF content import, resumable + cancellable, atomic .part -> final rename.
# - Content presence gate, overlay/VR asset staging with content-hash stamps.
# - VR Settings menu owning the ez2vr_* convars, applied as launch args on BOTH
#   paths (flat via setArgs, VR via the EZ2_GAME_ARGS env merge in
#   launcher/android/main.cpp — the VR NativeActivity has no setArgs).
# - Engine env + argv export; NativeActivity wired with android.app.lib_name
#   (= "launcher", i.e. liblauncher.so — matches the EZ1 Quest manifest).
# - Quest/OpenXR manifest parity with the proven EZ1 manifest: OPENXR +
#   OPENXR_SYSTEM permissions, the runtime-broker <queries> block, GLES 3.2,
#   supportedDevices, :engine process, resizeableActivity=false, and the VR
#   categories on the launch Intent (not just the manifest filter).
# - Flat-SDL boot path ported (EZ2FlatEngineActivity): boots monoscopic, loads
#   all 31 native modules, mounts the EZ2 mod, renders the Source loading
#   screen. The VR path falls back to it automatically when no session comes
#   up, and a manifest/toggle switch (adb extra: `--es boot_mode flat`) selects
#   between them.
# - Through-the-seam VR rendering (2026-10-10, on device): the engine boots on
#   the XR share-group EGL context (EZQUEST_VR_EXTERNAL_GL=1 bypasses SDL video,
#   which cannot init under a bare NativeActivity), mounts the mod with the full
#   `-game -mobile +exec +ez2vr_*` line, allocates per-eye Source RTs at full
#   swapchain resolution, and submits both eyes through DoDistortionProcessing
#   while the session holds FOCUSED. Tracking copy resolves (was hidden-visibility).
# - VR content layers ship real posture, not empty mounts: hl2vr (viewmodel/HUD/
#   headbob), hl2bodyvr (body-presence pins), hl2bodyhla (gravity-gun interaction
#   pins) — every convar verified against game/client+game/server in the engine
#   tree. The engine mounts VR_CONTENT_PATH as GAME search paths; ez2vr_boot.cfg
#   execs the three layer cfgs. Weapon layout ships as opt-in
#   vr_bindings_ez2.cfg (entity names verified against the EZ2 reference tree)
#   plus the VR action table the engine-side input work must implement.
# - Native-graph gate: the .load manifest is parsed on device and checked
#   against the installed libs, not just for existence.
# - Switch-to-flat/VR boot toggle on the launcher, plus adb auto_launch /
#   boot_mode extras for driving either path headless.
# - 59 local JVM tests over the Android-free discovery/copy logic, the native
#   graph parser, the boot-mode resolver, and the engine env builders.
# - Fail-loud diagnostics ported from the EZ1 port (diag/): session log,
#   Java crash capture, ANR watchdog, Downloads export, and
#   EZ2RecoveryActivity. Every engine refusal now shows a reason on the
#   headset instead of a silent finish, and a crash in the :engine process
#   is detected by sampling crash-report count across the launch.
#
# PROCESSES (deliberate)
#   main      launcher UI, content import, settings
#   :engine   liblauncher.so + the engine; a native fault kills only this
#   :recovery the fail-loud screen, so it can come up even when :engine is
#             wedged rather than merely crashed
#
# REFERENCE — the working EZ1 Quest port this mirrors is
#   ez1-quest-engine/android/manifest/AndroidManifest.xml  (manifest parity)
#   ez1-quest-engine/android/java/com/valvesoftware/ValveActivity2.java
#   ez1-quest-engine/android/java/com/ezquest/engine/       (19 launcher classes,
#       including the diag/ subsystem ported here)
# It has a flat-SDL boot path plus a VR path with native fallback; EZ2Launcher
# now has both — the VR path (EZ2EngineActivity) and the flat path
# (EZ2FlatEngineActivity) — selected by the com.ez2vr.launcher.BOOT_MODE
# meta-data, the launcher toggle, or the adb boot_mode extra.
#
# HARD REMAINING WORK (native — the bulk of a real port)
# NATIVE CONTRACT (shipped — see native/):
# - native/game-shared-ez2vr/ez2vr_cvars.h — split header (server vs client)
# - native/game-server-ez2vr/ez2vr_cvars_server.cpp — 6 server convars
# - native/game-client-ez2vr/ez2vr_cvars_client.cpp — 2 client convars
# - native/ez2vr_mobile_hook.patch — GameInit -mobile exec (anchored to
#   source-sdk-2013 sp gameinterface.cpp CServerGameDLL::GameInit)
# - native/ez2vr_argv_snippet.cpp — EZ2_GAME_ARGS env -> argv merge reference
#   (now INTEGRATED in ez1-quest-engine launcher/android/main.cpp: VR merges it
#   when java_args is empty; flat keeps setArgs authoritative)
# 1. ~~Build Mapbase-based Android ARM64 libs for EZ2~~ DONE — the ez1-
#    quest-engine builds the ez2 profile for aarch64 and the APK carries
#    those libs. Current blocker there is the `CUtlRBTree overflow!` engine
#    error during content load (see HANDOFF.md); a fatal-backtrace hook is
#    wired in tier0/launcher to capture its stack.
#    See TRANSPLANT-A.md for the staged milestones M0-M4.
# 2. ~~Author real overlay/vr assets under app/src/main/assets/ez2/~~ DONE —
#    overlay cfgs (ez2vr_boot.cfg, ez2vr_mobile.cfg, vr_defaults.cfg,
#    vr_bindings_ez2.cfg) plus per-layer posture (vr_core/vr_body/vr_hands.cfg
#    under ez2/vr/hl2vr, hl2bodyvr, hl2bodyhla). Staged non-empty and mounted.
#    Full body/hand MODELS + IK remain future engine work; the layers carry
#    posture + the documented contract, not placeholders that pretend.
#    Do NOT extract SourceVR's blobs.
# 3. ~~Per-game launch flags + save dir~~ DONE — flatArgv (-game abs, -mobile,
#    +execs, menu convars last) is shared by both paths via EZ2_GAME_ARGS;
#    save dir is the write game dir (SOURCEVR_WRITE_GAME_PATH). Controller
#    bindings: keyboard fallback + VR action table ship in vr_bindings_ez2.cfg;
#    engine-side XR action bindings (vr_input.cpp) are the remaining input work.
# 4. Wear-test the through-the-seam build in-headset (off-head proof holds
#    FOCUSED + submits; a human must confirm the intro map renders in stereo
#    and controllers drive).
#
# LEGAL
# - EZ2 game code is open source (entropy-zero org) — follow its license.
# - SourceVR APK contents are NOT redistributable: use the decompile for
#   understanding only; this skeleton contains no SourceVR code or assets.
# - HL2/EZ2 game content belongs to the user: launcher imports, never bundles.
