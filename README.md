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
# CONTAINS Half-Life 2/ and EntropyZero2/ (usually Steam/steamapps/common).
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
# The launcher UI builds and runs. Engine launch stays gated until native libs
# exist (see below) and logs exactly what is missing.
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
#   `build_games` defaults to ez1 because the `ez2` waf profile does not exist
#   yet (TRANSPLANT-A M1) — until then the APK carries the EZ1 game DLLs.
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
# - VR Settings menu owning the ez2vr_* convars, applied as launch args.
# - Engine env + argv export; NativeActivity wired with android.app.lib_name
#   (= "launcher", i.e. liblauncher.so — matches the EZ1 Quest manifest).
# - Quest/OpenXR manifest parity with the proven EZ1 manifest: OPENXR +
#   OPENXR_SYSTEM permissions, the runtime-broker <queries> block, GLES 3.2,
#   supportedDevices, :engine process, resizeableActivity=false, and the VR
#   categories on the launch Intent (not just the manifest filter).
# - 19 local JVM tests over the Android-free discovery/copy logic.
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
# has the VR path only. Porting that fallback is listed under remaining work.
#
# HARD REMAINING WORK (native — the bulk of a real port)
# NATIVE CONTRACT (started — see native/):
# - native/game-shared-ez2vr/ez2vr_cvars.h — split header (server vs client)
# - native/game-server-ez2vr/ez2vr_cvars_server.cpp — 6 server convars
# - native/game-client-ez2vr/ez2vr_cvars_client.cpp — 2 client convars
# - native/ez2vr_mobile_hook.patch — GameInit -mobile exec (anchored to
#   source-sdk-2013 sp gameinterface.cpp CServerGameDLL::GameInit)
# - native/ez2vr_argv_snippet.cpp — EZ2_GAME_ARGS env -> argv merge reference
# 1. Build Mapbase-based Android ARM64 libs for EZ2 from the open
#    entropy-zero ez2/mapbase branch: liblauncher.so, libengine-adjacent set,
#    and EZ2's libserver.so/libclient.so with VR input (OpenXR) patches.
#    SourceVR's entropyzero.load blobs (32 libs, EP2-SDK based) canNOT be reused
#    for Mapbase EZ2 — expect entity/code mismatch crashes if attempted.
#    (You already have ez1-quest-engine/ + source-sdk-2013-ez1/ locally —
#    that is the right starting point for the EZ2/Mapbase engine fork.)
#    See TRANSPLANT-A.md for the staged milestones M0-M4.
# 2. Author real overlay/vr assets under app/src/main/assets/ez2/.
#    Overlay cfgs ship today (ez2vr_boot.cfg, ez2vr_mobile.cfg,
#    vr_defaults.cfg); the three VR layers (hl2bodyhla, hl2bodyvr, hl2vr) are
#    still empty and are staged as empty-but-mounted. Do NOT extract
#    SourceVR's blobs.
# 3. Per-game launch flags + save dir + controller bindings for EZ2's weapons
#    (notably the EZ2-specific arsenal vs EZ1). The ez2vr_* locomotion/turn/
#    visor keys are wired; weapon and vehicle bindings are not.
#
# LEGAL
# - EZ2 game code is open source (entropy-zero org) — follow its license.
# - SourceVR APK contents are NOT redistributable: use the decompile for
#   understanding only; this skeleton contains no SourceVR code or assets.
# - HL2/EZ2 game content belongs to the user: launcher imports, never bundles.
