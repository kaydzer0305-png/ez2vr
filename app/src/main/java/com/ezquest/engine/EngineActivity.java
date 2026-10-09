package com.ezquest.engine;

/**
 * JNI binding shim for the OpenXR status readback. Not an Activity — see
 * {@link #nativeXrStatus()} for why it exists at all.
 *
 * <p>{@code liblauncher.so} exposes the XR loop's state through two by-name
 * symbols and nothing else — there is no JNI call that creates a session, a
 * swapchain or the frame loop; that is all C++ driven from
 * {@code ANativeActivity_onCreate}:
 *
 * <pre>
 *   launcher/android/vr_main.cpp:172  Java_com_ezquest_engine_EngineActivity_nativeXrStatus
 *   launcher/android/vr_main.cpp:178  Java_com_ezquest_engine_EngineActivity_nativeXrFailReason
 * </pre>
 *
 * <p>So the declaring class must be exactly {@code com.ezquest.engine.EngineActivity}.
 * The real EZ1 activity of that name lives in the engine repo's Java tree and is
 * not part of this APK, so this class takes its place to satisfy the lookup.
 *
 * <p>These two methods are the entire basis for the automatic VR-to-flat
 * fallback. Without them the launcher has no way to learn that the XR loop
 * failed or stalled, and the documented fallback could not exist — the Android
 * manifest comment claiming the native loop "falls back to the flat SDL engine"
 * was untrue until this shim made it true from the Java side.
 */
public final class EngineActivity {

    /** Bootstrap still in progress; the caller should keep waiting. */
    public static final int EZ_XR_STARTING = 0;
    /** Frame loop reached the runtime and is presenting. */
    public static final int EZ_XR_RUNNING = 1;
    /** Bootstrap failed; {@link #nativeXrFailReason()} says why. */
    public static final int EZ_XR_FAILED = 2;

    /**
     * Current XR loop state.
     *
     * @return one of {@link #EZ_XR_STARTING}, {@link #EZ_XR_RUNNING},
     *         {@link #EZ_XR_FAILED}
     * @throws UnsatisfiedLinkError if {@code liblauncher.so} is not loaded.
     *         Callers must treat that as {@link #EZ_XR_FAILED} and fall back —
     *         a build whose engine predates these symbols cannot present, so
     *         waiting out a timeout only delays the same outcome.
     */
    public static native int nativeXrStatus();

    /**
     * Human-readable failure reason recorded by the bootstrap, or null/empty if
     * none. Purely diagnostic; the fallback decision is driven by timing.
     */
    public static native String nativeXrFailReason();

    private EngineActivity() {}
}
