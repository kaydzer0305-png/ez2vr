package com.valvesoftware;

/**
 * JNI binding shim for the flat boot path. Not an Activity — see
 * {@link #setArgs(String)} for why it exists at all.
 *
 * <p>{@code liblauncher.so} resolves these two methods by fully-qualified name,
 * not by dynamic registration:
 *
 * <pre>
 *   launcher/android/main.cpp:34  Java_com_valvesoftware_ValveActivity2_setenv
 *   launcher/android/main.cpp:45  Java_com_valvesoftware_ValveActivity2_setArgs
 * </pre>
 *
 * <p>So the declaring class has to be exactly {@code com.valvesoftware.ValveActivity2}
 * or the call dies with {@code UnsatisfiedLinkError}. That is a frozen contract
 * in a binary we do not rebuild here, and it is why this unremarkable-looking
 * package name appears in an EZ2 app. Renaming this class, or moving these
 * declarations onto another class, breaks the flat boot with a link error that
 * names neither this file nor the C++ side.
 *
 * <p>The alternative — {@code RegisterNatives} from C++ — means editing and
 * rebuilding the engine to accommodate the launcher, which is strictly worse for
 * a shim whose only job is to satisfy a name lookup.
 *
 * <p>{@link com.ez2vr.launcher.EZ2FlatEngineActivity} owns all the real logic and
 * calls in here; it must have loaded {@code liblauncher.so} first.
 */
public final class ValveActivity2 {

    /**
     * {@code setenv(3)}, exposed so the flat activity can use the same env
     * mechanism the engine's own Java layer does. The engine side also logs
     * every call, which makes it a convenient trace point.
     *
     * <p>Currently unused — {@link com.ez2vr.launcher.EZ2EngineEnv#export} goes
     * through {@code Os.setenv} — but kept because the native side binds it by
     * name and a missing declaration is a latent link failure.
     */
    public static native int setenv(String env, String value, int overwrite);

    /**
     * Seeds the engine's argv.
     *
     * <p>Lands in the {@code java_args} buffer that
     * {@code LauncherMainAndroid -> SetLauncherArgs()} tokenises. This is how
     * the flat path supplies {@code -game} and the {@code +ez2vr_*} overrides:
     * the VR path is a {@code NativeActivity} and never calls this, so its
     * {@code java_args} is empty and the engine falls back to synthesizing
     * {@code -game} from {@code VALVE_GAME_PATH} + {@code SOURCEVR_GAME}, which
     * in the EZ2 layout points at the write dir rather than the content dir.
     *
     * <p>Call before {@code super.onCreate()}: {@code SDLActivity} starts the
     * engine on its own thread as soon as the surface exists
     * ({@code SDLActivity.java:589}), and anything set afterwards races it.
     */
    public static native void setArgs(String args);

    private ValveActivity2() {}
}
