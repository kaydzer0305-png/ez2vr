package com.ez2vr.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Imports a PC Steam copy of Entropy: Zero 2 into the on-device shared depot.
 *
 * <p>Replaces the v0.1 "push it with adb" instruction. The user grants read
 * access to a folder once via SAF ({@code ACTION_OPEN_DOCUMENT_TREE}) and the
 * permission is persisted, so later content updates need no re-pick and no
 * developer tools.
 *
 * <p>Six depots are copied: the mod, hl2/, platform/, and the mapbase/,
 * episodic/ and ep2/ content EZ2's gameinfo.txt mounts from
 * |all_source_engine_paths|. A self-contained EZ2 install bundles all six
 * beside each other inside the EntropyZero2/ install folder.
 *
 * <p>Pure Java by design: it talks to {@link EZ2SourceNode}, never to SAF, so
 * the directory heuristics below are unit-testable off-device.
 *
 * <p><b>What the user is asked to pick.</b> Either the folder that CONTAINS the
 * game directories — normally {@code Steam/steamapps/common}, holding
 * {@code EntropyZero2/} and {@code Half-Life 2/} — or, for a self-contained
 * EZ2 install, the {@code EntropyZero2/} folder itself, which bundles
 * {@code entropyzero2/}, {@code hl2/} and {@code platform/} inside it.
 * Discovery also looks one level down, so picking {@code steamapps/} or a
 * hand-made folder of symlinks works too. Directory names are matched loosely because Steam's own naming is
 * inconsistent across platforms and libraries:
 *
 * <ul>
 *   <li>mod: normalized name starting {@code entropyzero2}, else a
 *       {@code gameinfo.txt} whose name/title reads "Entropy: Zero 2". The
 *       prefix test deliberately does NOT match EZ1's {@code EntropyZero}, so
 *       an EZ1-only install is reported missing rather than half-imported.
 *   <li>HL2: normalized name containing {@code hl2} or {@code halflife2},
 *       covering "Half-Life 2", "HL2 (win32)" and friends.
 *   <li>platform: {@code platform/} beside the HL2 dir, or inside it.
 * </ul>
 *
 * <p><b>Resumability.</b> A file whose destination already has the same
 * non-zero length is skipped, so an import interrupted by a cancelled copy or
 * a flat battery resumes instead of restarting, and re-importing after a
 * Steam update copies only what changed. Files land as {@code .part} and are
 * renamed into place, so a half-written file is never visible at its final
 * name and cannot be mistaken for a complete one on the next pass.
 */
public final class EZ2ContentImporter {

    /** Depot directory names. Must match what {@link EZ2ContentPresence} checks. */
    static final String DEPOT_HL2 = "hl2";
    static final String DEPOT_PLATFORM = "platform";

    /**
     * Mapbase shared content, Episode One and Episode Two. EZ2's gameinfo.txt
     * mounts all three from |all_source_engine_paths| (shared_content_v7_0.vpk,
     * ep1_pak.vpk, ep2_pak.vpk); a self-contained EZ2 install bundles them
     * beside the mod dir. Without them the game boots, but Mapbase materials
     * and Episodic scenes/sounds/models are missing.
     */
    static final String DEPOT_MAPBASE = "mapbase";
    static final String DEPOT_EPISODIC = "episodic";
    static final String DEPOT_EP2 = "ep2";

    private static final int BUF = 64 * 1024;
    private static final int GAMEINFO_PROBE_BYTES = 4096;

    /** "Entropy: Zero 2" but not "Entropy: Zero" — the trailing 2 is the tell. */
    private static final Pattern GAMEINFO_IS_EZ2 =
            Pattern.compile("zero[^a-z0-9]{0,3}2");
    private static final Pattern GAMEINFO_IS_HL2 =
            Pattern.compile("(half[^a-z0-9]{0,3}life[^a-z0-9]{0,3}2)|hl2");

    private EZ2ContentImporter() {}

    // ---- discovery --------------------------------------------------------

    /** What was found under the picked folder, and what is still missing. */
    public static final class Plan {
        /** The container discovery settled on — reported so the user can see
         * which folder was actually read. */
        public final EZ2SourceNode base;
        public final EZ2SourceNode mod;
        public final EZ2SourceNode hl2;
        public final EZ2SourceNode platform;
        public final EZ2SourceNode mapbase;
        public final EZ2SourceNode episodic;
        public final EZ2SourceNode ep2;
        /** True when platform/ lives inside the HL2 dir rather than beside it. */
        public final boolean platformInsideHl2;
        public final List<String> problems;

        Plan(EZ2SourceNode base, EZ2SourceNode mod, EZ2SourceNode hl2,
             EZ2SourceNode platform, EZ2SourceNode mapbase, EZ2SourceNode episodic,
             EZ2SourceNode ep2, boolean platformInsideHl2, List<String> problems) {
            this.base = base;
            this.mod = mod;
            this.hl2 = hl2;
            this.platform = platform;
            this.mapbase = mapbase;
            this.episodic = episodic;
            this.ep2 = ep2;
            this.platformInsideHl2 = platformInsideHl2;
            this.problems = problems;
        }

        public boolean isComplete() {
            return problems.isEmpty() && mod != null && hl2 != null && platform != null
                    && mapbase != null && episodic != null && ep2 != null;
        }

        int score() {
            int n = 0;
            if (mod != null) n += 2;
            if (hl2 != null) n += 2;
            if (platform != null) n += 1;
            if (mapbase != null) n += 1;
            if (episodic != null) n += 1;
            if (ep2 != null) n += 1;
            return n;
        }

        /** Human-readable list of what is missing, for the launcher status line. */
        public String describeProblems() {
            StringBuilder sb = new StringBuilder();
            for (String p : problems) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(p);
            }
            return sb.toString();
        }
    }

    /**
     * Inspects the picked tree and decides what to copy where. Performs I/O
     * (reads gameinfo.txt headers), so call it off the UI thread.
     */
    public static Plan plan(EZ2SourceNode root) {
        if (root == null || !root.isDirectory()) {
            return new Plan(null, null, null, null, null, null, null, false,
                    listOf("That folder could not be read. Pick it again."));
        }
        Plan best = null;
        // The picked node, then each child dir one level down. Ordered so a
        // direct hit always beats a deeper one; ties keep the shallower.
        List<EZ2SourceNode> bases = new ArrayList<>();
        bases.add(root);
        for (EZ2SourceNode child : root.children()) {
            if (child.isDirectory()) {
                bases.add(child);
            }
        }
        for (EZ2SourceNode base : bases) {
            Plan candidate = planIn(base);
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }
        if (best.score() == 0) {
            return new Plan(root, null, null, null, null, null, null, false, listOf(
                    "No Half-Life 2 or Entropy: Zero 2 folder under:\n" + root.name()
                            + "\n\nPick the folder that CONTAINS them — on a PC that is\n"
                            + "usually Steam/steamapps/common — or the EntropyZero2 folder\n"
                            + "itself on a self-contained install."));
        }
        return best;
    }

    private static Plan planIn(EZ2SourceNode base) {
        List<EZ2SourceNode> dirs = new ArrayList<>();
        for (EZ2SourceNode child : base.children()) {
            if (child.isDirectory() && hasGameInfo(child)) {
                dirs.add(child);
            }
        }

        EZ2SourceNode mod = null;
        EZ2SourceNode hl2 = null;

        // Pointed straight at a game dir: it is the mod as far as we can tell,
        // though its HL2 sibling is out of reach (SAF hands us no parent).
        if (hasGameInfo(base)) {
            mod = base;
        }

        // Name match first — unambiguous, and it keeps EZ1's "EntropyZero" out.
        for (EZ2SourceNode dir : dirs) {
            String norm = normalize(dir.name());
            if (mod == null && norm.startsWith("entropyzero2")) {
                mod = dir;
            }
            if (hl2 == null && (norm.contains("hl2") || norm.contains("halflife2"))) {
                hl2 = dir;
            }
        }
        // Content match as a fallback for a renamed or unusual directory.
        if (mod == null) {
            mod = findByGameInfo(dirs, GAMEINFO_IS_EZ2);
        }
        if (hl2 == null) {
            hl2 = findByGameInfo(dirs, GAMEINFO_IS_HL2);
        }

        EZ2SourceNode platform = null;
        boolean insideHl2 = false;
        if (hl2 != null) {
            EZ2SourceNode inner = dirNamed(hl2, DEPOT_PLATFORM);
            if (inner != null) {
                platform = inner;
                insideHl2 = true;
            }
        }
        if (platform == null) {
            platform = dirNamed(base, DEPOT_PLATFORM);
        }

        // Mapbase/Episode depots sit beside the mod dir inside a self-contained
        // EZ2 install (mapbase has no gameinfo.txt, so plain name lookup).
        EZ2SourceNode mapbase = dirNamed(base, DEPOT_MAPBASE);
        EZ2SourceNode episodic = dirNamed(base, DEPOT_EPISODIC);
        EZ2SourceNode ep2 = dirNamed(base, DEPOT_EP2);

        List<String> problems = new ArrayList<>();
        if (mod == null) {
            problems.add("Entropy: Zero 2 folder not found (expected one named "
                    + EZ2Profile.GAME_DIR_WINDOWS + " containing gameinfo.txt).");
        }
        if (hl2 == null) {
            problems.add("Half-Life 2 folder not found (expected \"Half-Life 2\" "
                    + "or \"hl2\" containing gameinfo.txt).");
        }
        if (platform == null) {
            problems.add("platform/ folder not found beside or inside the Half-Life 2 folder.");
        }
        if (mapbase == null) {
            problems.add("mapbase/ folder not found — it ships inside the Entropy: Zero 2 "
                    + "install and EZ2 cannot render its materials without it.");
        }
        if (episodic == null) {
            problems.add("episodic/ folder not found — it ships inside the Entropy: Zero 2 install.");
        }
        if (ep2 == null) {
            problems.add("ep2/ folder not found — it ships inside the Entropy: Zero 2 install.");
        }
        if (mod == base && hl2 == null) {
            problems.add("You picked the EntropyZero2 folder itself. Pick its PARENT "
                    + "instead, so Half-Life 2 can be found next to it.");
        }
        return new Plan(base, mod, hl2, platform, mapbase, episodic, ep2, insideHl2, problems);
    }

    private static EZ2SourceNode findByGameInfo(List<EZ2SourceNode> dirs, Pattern needle) {
        for (EZ2SourceNode dir : dirs) {
            if (needle.matcher(readGameInfoHead(dir)).find()) {
                return dir;
            }
        }
        return null;
    }

    private static boolean hasGameInfo(EZ2SourceNode dir) {
        return dir.hasChildFile("gameinfo.txt");
    }

    private static EZ2SourceNode dirNamed(EZ2SourceNode parent, String name) {
        EZ2SourceNode child = parent.child(name);
        return (child != null && child.isDirectory()) ? child : null;
    }

    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        String lower = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Lowercased first few KB of gameinfo.txt, for the fallback match. */
    private static String readGameInfoHead(EZ2SourceNode gameDir) {
        EZ2SourceNode info = gameDir.child("gameinfo.txt");
        if (info == null || info.isDirectory()) {
            return "";
        }
        try (InputStream in = info.open()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[BUF];
            int total = 0;
            int n;
            while (total < GAMEINFO_PROBE_BYTES && (n = in.read(buf, 0, Math.min(buf.length, GAMEINFO_PROBE_BYTES - total))) != -1) {
                bos.write(buf, 0, n);
                total += n;
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            return "";
        }
    }

    // ---- copying ----------------------------------------------------------

    public interface ProgressListener {
        void onScanning();

        /**
         * @param bytesDone  bytes copied so far (skipped files included).
         * @param bytesTotal denominator from the scan pass, or 0 if unknown.
         *                   Passed in rather than derived so the caller can size
         *                   a progress bar without re-walking the tree.
         */
        void onCopying(int filesDone, int filesTotal, long bytesDone, long bytesTotal,
                       String currentName);
    }

    public interface CancelSignal {
        boolean isCancelled();
    }

    public static final class CopyStats {
        public final int filesCopied;
        public final int filesSkipped;
        public final int filesTotal;
        public final long bytesCopied;
        public final long bytesTotal;
        public final boolean cancelled;
        public final long elapsedMs;

        CopyStats(int filesCopied, int filesSkipped, int filesTotal,
                  long bytesCopied, long bytesTotal, boolean cancelled, long elapsedMs) {
            this.filesCopied = filesCopied;
            this.filesSkipped = filesSkipped;
            this.filesTotal = filesTotal;
            this.bytesCopied = bytesCopied;
            this.bytesTotal = bytesTotal;
            this.cancelled = cancelled;
            this.elapsedMs = elapsedMs;
        }

        public boolean isComplete() {
            return !cancelled;
        }
    }

    /** One source subtree and the depot directory it expands into. */
    private static final class Job {
        final EZ2SourceNode src;
        final String destName;

        Job(EZ2SourceNode src, String destName) {
            this.src = src;
            this.destName = destName;
        }
    }

    private static final class Pending {
        final EZ2SourceNode node;
        final File dest;

        Pending(EZ2SourceNode node, File dest) {
            this.node = node;
            this.dest = dest;
        }
    }

    private static final class Counters {
        int filesDone;
        int filesCopied;
        int filesSkipped;
        long bytesDone;
        long bytesCopied;
        boolean cancelled;
    }

    /**
     * Copies a completed {@link Plan} into {@code depotRoot}. Blocking; call it
     * off the UI thread. Throws {@link IOException} on any unrecoverable
     * problem — a partially imported depot is safe, because
     * {@link EZ2ContentPresence} still gates the launch on complete content.
     */
    public static CopyStats copy(Plan plan, File depotRoot, ProgressListener listener,
                                 CancelSignal cancel) throws IOException {
        if (plan == null || !plan.isComplete()) {
            throw new IOException("import plan is incomplete");
        }
        long started = System.currentTimeMillis();
        if (listener != null) {
            listener.onScanning();
        }

        List<Job> jobs = new ArrayList<>();
        jobs.add(new Job(plan.mod, EZ2Profile.GAME_DIR_WINDOWS));
        jobs.add(new Job(plan.hl2, DEPOT_HL2));
        jobs.add(new Job(plan.mapbase, DEPOT_MAPBASE));
        jobs.add(new Job(plan.episodic, DEPOT_EPISODIC));
        jobs.add(new Job(plan.ep2, DEPOT_EP2));
        if (plan.platform != null) {
            // When platform/ sits INSIDE the HL2 dir it is already covered by
            // the hl2 job, and is also wanted at the depot root. Copying it
            // twice is intended; the scan below then counts those bytes twice
            // in the progress total, which errs high rather than jumping past
            // 100%. Rare layout — Steam ships platform/ as a sibling.
            jobs.add(new Job(plan.platform, DEPOT_PLATFORM));
        }

        Counters c = new Counters();
        // Scan first so the progress bar has a denominator. Metadata only.
        for (Job job : jobs) {
            scan(job.src, c);
        }
        int filesTotal = c.filesDone;
        long bytesTotal = c.bytesDone;
        c.filesDone = 0;
        c.bytesDone = 0;

        for (Job job : jobs) {
            if (cancelled(c, cancel)) {
                break;
            }
            copyJob(job, new File(depotRoot, job.destName), c, listener, filesTotal, bytesTotal, cancel);
        }
        return new CopyStats(c.filesCopied, c.filesSkipped, filesTotal,
                c.bytesCopied, bytesTotal, c.cancelled, System.currentTimeMillis() - started);
    }

    private static boolean cancelled(Counters c, CancelSignal cancel) {
        if (c.cancelled) {
            return true;
        }
        if (cancel != null && cancel.isCancelled()) {
            c.cancelled = true;
        }
        return c.cancelled;
    }

    private static void scan(EZ2SourceNode root, Counters c) {
        Deque<EZ2SourceNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            EZ2SourceNode node = stack.pop();
            if (!node.isDirectory()) {
                c.filesDone++;
                c.bytesDone += Math.max(0L, node.size());
                continue;
            }
            for (EZ2SourceNode child : node.children()) {
                stack.push(child);
            }
        }
    }

    private static void copyJob(Job job, File destRoot, Counters c, ProgressListener listener,
                                int filesTotal, long bytesTotal, CancelSignal cancel)
            throws IOException {
        Deque<Pending> stack = new ArrayDeque<>();
        stack.push(new Pending(job.src, destRoot));
        while (!stack.isEmpty()) {
            if (cancelled(c, cancel)) {
                return;
            }
            Pending p = stack.pop();
            if (p.node.isDirectory()) {
                if (!p.dest.isDirectory() && !p.dest.mkdirs() && !p.dest.isDirectory()) {
                    throw new IOException("cannot create " + p.dest);
                }
                // Reversed push order keeps listing order stable for the user.
                List<EZ2SourceNode> kids = p.node.children();
                for (int i = kids.size() - 1; i >= 0; i--) {
                    EZ2SourceNode kid = kids.get(i);
                    if (!isSafeName(kid.name())) {
                        continue;
                    }
                    stack.push(new Pending(kid, new File(p.dest, kid.name())));
                }
            } else {
                if (listener != null) {
                    listener.onCopying(c.filesDone, filesTotal, c.bytesDone, bytesTotal,
                            p.dest.getName());
                }
                copyOne(p.node, p.dest, c);
                if (listener != null) {
                    listener.onCopying(c.filesDone, filesTotal, c.bytesDone, bytesTotal,
                            p.dest.getName());
                }
            }
        }
    }

    private static void copyOne(EZ2SourceNode node, File dest, Counters c) throws IOException {
        long size = node.size();
        // Skip only on a KNOWN non-zero length: some SAF providers report 0 for
        // a real file, and treating that as "already there" would skip content
        // the depot is actually missing.
        if (size > 0 && dest.isFile() && dest.length() == size) {
            c.filesSkipped++;
            c.filesDone++;
            c.bytesDone += size;
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(dest.getAbsolutePath() + ".part");
        try (InputStream in = node.open(); FileOutputStream out = new FileOutputStream(tmp, false)) {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                c.bytesDone += n;
                c.bytesCopied += n;
            }
            out.flush();
            out.getFD().sync();
        }
        if (dest.exists() && !dest.delete()) {
            throw new IOException("cannot replace " + dest);
        }
        if (!tmp.renameTo(dest)) {
            throw new IOException("cannot rename " + tmp + " -> " + dest);
        }
        c.filesCopied++;
        c.filesDone++;
    }

    /** Rejects names that would escape the destination directory. */
    private static boolean isSafeName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (name.equals(".") || name.equals("..")) {
            return false;
        }
        return name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }

    // ---- misc -------------------------------------------------------------

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    private static List<String> listOf(String... items) {
        List<String> out = new ArrayList<>(items.length);
        for (String s : items) {
            out.add(s);
        }
        return out;
    }
}
