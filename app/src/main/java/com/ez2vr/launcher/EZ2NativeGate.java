package com.ez2vr.launcher;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Gate that proves this APK actually carries the engine it claims to.
 *
 * <p>Extracted from {@link EZ2EngineActivity} so the flat path applies exactly
 * the same check. A gate that only one boot path runs is a gate that will
 * eventually disagree with the other one.
 *
 * <p>Why this exists at all: a {@code .load} manifest with no ARM64 libraries
 * beside it is a half-finished native drop, and the framework surfaces that as an
 * {@code UnsatisfiedLinkError} crash dialog, which tells a Quest user nothing
 * about what to fix. Checking only {@code liblauncher.so} catches a launcher-only
 * build but not a partial one, and the engine {@code dlopen()}s
 * {@code libengine.so}, {@code libinputsystem.so} and the rest by name — a
 * missing one dies in {@code dlopen} with no diagnostic at all. So the whole
 * declared set is verified.
 */
public final class EZ2NativeGate {

    /**
     * Engine entry point. {@code NativeActivity} loads this by way of the
     * {@code android.app.lib_name} meta-data; the flat activity loads the same
     * file explicitly, because it is an {@code SDLActivity} with no such
     * meta-data.
     */
    public static final String ENGINE_LIB_SO = "liblauncher.so";

    private EZ2NativeGate() {}

    /**
     * Name of the bundled runtime graph asset, or null when there is none.
     *
     * <p>There is exactly one graph by design ({@code ez2vr.load}); the search
     * exists because {@link EZ2AssetStage#stageNativeGraph} stages whatever
     * {@code *.load} it finds, so a future second graph cannot silently become
     * the one that gets checked.
     */
    public static String nativeGraphAssetName(Context ctx) {
        try {
            String[] files = ctx.getAssets().list("ez2/native");
            if (files == null) {
                return null;
            }
            for (String f : files) {
                if (f.endsWith(".load")) {
                    return f;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static boolean hasNativeGraph(Context ctx) {
        return nativeGraphAssetName(ctx) != null;
    }

    /**
     * Checks every library the bundled graph declares against the ones actually
     * installed in this APK.
     *
     * @return null when the set is complete, otherwise a message naming what is
     *         missing and what to do about it
     */
    public static String verify(Context ctx) {
        String asset = nativeGraphAssetName(ctx);
        if (asset == null) {
            return "No engine runtime graph is bundled (expected "
                    + "assets/ez2/native/*.load).";
        }

        List<String> declared;
        try {
            InputStream in = ctx.getAssets().open("ez2/native/" + asset);
            try {
                declared = EZ2NativeGraph.parse(in);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "Engine runtime graph " + asset + " is unreadable: "
                    + describe(e);
        }

        if (declared.isEmpty()) {
            return "Engine runtime graph " + asset + " declares no libraries.";
        }

        // useLegacyPackaging extracts the libs, so nativeLibraryDir is where the
        // engine will dlopen() them from. Reading it is also the honest check: it
        // is the directory the package manager actually populated, so it proves
        // the libs shipped in THIS apk rather than merely existing in the source
        // tree.
        File nativeDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        List<String> installed = new ArrayList<>();
        String[] kids = nativeDir.list();
        if (kids != null) {
            for (String k : kids) {
                if (k.endsWith(".so")) {
                    installed.add(k);
                }
            }
        }

        List<String> missing = EZ2NativeGraph.missingFrom(declared, installed);
        if (!missing.isEmpty()) {
            return "Engine runtime graph declares " + declared.size() + " librar"
                    + (declared.size() == 1 ? "y" : "ies") + " but " + missing.size()
                    + " are absent from " + nativeDir + ": " + join(missing)
                    + ". Stage them with :app:stageEngineLibs.";
        }
        if (!installed.contains(ENGINE_LIB_SO)) {
            return "Every declared library is present except that " + ENGINE_LIB_SO
                    + " is not among them; the engine will not load. Check "
                    + "assets/ez2/native/" + asset + ".";
        }
        return null;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty()
                ? e.getClass().getSimpleName() : message;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(i == items.size() - 1 ? " and " : ", ");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
