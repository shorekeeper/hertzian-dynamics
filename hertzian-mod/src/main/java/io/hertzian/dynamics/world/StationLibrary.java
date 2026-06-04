package io.hertzian.dynamics.world;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Server-side registry of available .qoa station tracks. Files live in a
 * "hertzian_stations" folder beside the config directory so operators drop
 * tracks in one obvious place.
 *
 * <p>
 * Layout and identifiers. Tracks may sit directly in the stations folder
 * or inside playlist subfolders. A playlist subfolder is any directory
 * whose name starts with {@link #PLAYLIST_PREFIX}; it groups tracks for
 * display and has no other effect on playback. Each track is addressed by
 * a relative identifier that uses a forward slash separator regardless of
 * platform:
 * <ul>
 * <li>a root track is its bare file name, for example {@code song.qoa};</li>
 * <li>a playlist track is {@code folder/file}, for example
 * {@code __playlist_rock/song.qoa}.</li>
 * </ul>
 * These identifiers are what {@link #list()} returns, what travels in the
 * station packets, and what {@link #resolve(String)} maps back to a file.
 *
 * <p>
 * Display naming is a presentation convention, not a storage one. The
 * helpers {@link #isPlaylistTrack(String)}, {@link #playlistOf(String)},
 * {@link #trackNameOf(String)} and {@link #playlistDisplayName(String)} are
 * pure string operations and are safe to call from the client GUI; they do
 * not touch the file system.
 *
 * <p>
 * Resolution refuses any name that escapes the stations folder or a
 * playlist folder, so a hostile identifier from the network cannot reach
 * arbitrary files. Path separators and the dot entries are rejected in the
 * folder and track components.
 */
public final class StationLibrary {

    /** Folder name prefix that marks a directory as a playlist group. */
    public static final String PLAYLIST_PREFIX = "__playlist_";

    private static File dir;

    private StationLibrary() {}

    public static void setDirectory(File configDir) {
        dir = new File(configDir.getParentFile(), "hertzian_stations");
        if (!dir.exists()) dir.mkdirs();
    }

    /**
     * List every available track as a relative identifier. Root tracks
     * come first, sorted by name, followed by playlist tracks grouped by
     * folder, folders sorted by name and tracks within each folder sorted
     * by name. The grouping order lets the client build a grouped browser
     * by a single pass over this list.
     */
    public static List<String> list() {
        List<String> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;

        // Root tracks.
        File[] rootFiles = dir.listFiles(
            (d, name) -> name.toLowerCase()
                .endsWith(".qoa"));
        if (rootFiles != null) {
            List<String> rootNames = new ArrayList<>();
            for (File f : rootFiles) {
                if (f.isFile()) rootNames.add(f.getName());
            }
            Collections.sort(rootNames);
            out.addAll(rootNames);
        }

        // Playlist folders.
        File[] dirs = dir.listFiles(File::isDirectory);
        if (dirs != null) {
            List<File> playlists = new ArrayList<>();
            for (File folder : dirs) {
                if (isPlaylistFolder(folder.getName())) playlists.add(folder);
            }
            playlists.sort(Comparator.comparing(File::getName));
            for (File folder : playlists) {
                File[] tracks = folder.listFiles(
                    (d, name) -> name.toLowerCase()
                        .endsWith(".qoa"));
                if (tracks == null) continue;
                List<String> trackNames = new ArrayList<>();
                for (File f : tracks) {
                    if (f.isFile()) trackNames.add(f.getName());
                }
                Collections.sort(trackNames);
                for (String t : trackNames) {
                    out.add(folder.getName() + "/" + t);
                }
            }
        }
        return out;
    }

    /**
     * Map a track identifier to a file. Accepts a bare root track name or a
     * {@code folder/file} playlist identifier. Returns null when the
     * directory is unset, the identifier is empty, the components are
     * unsafe, the playlist folder prefix is missing, or the file does not
     * exist. The parent checks reject any attempt to escape the stations
     * folder or the playlist folder.
     */
    public static File resolve(String identifier) {
        if (dir == null || identifier == null || identifier.isEmpty()) return null;
        int slash = identifier.indexOf('/');
        if (slash < 0) {
            if (!isSafeName(identifier)) return null;
            File f = new File(dir, identifier);
            if (!dir.equals(f.getParentFile())) return null;
            return f.isFile() ? f : null;
        }
        String folder = identifier.substring(0, slash);
        String track = identifier.substring(slash + 1);
        if (!isPlaylistFolder(folder) || !isSafeName(track)) return null;
        File folderFile = new File(dir, folder);
        if (!dir.equals(folderFile.getParentFile()) || !folderFile.isDirectory()) return null;
        File f = new File(folderFile, track);
        if (!folderFile.equals(f.getParentFile())) return null;
        return f.isFile() ? f : null;
    }

    /** True if the identifier names a track inside a playlist folder. */
    public static boolean isPlaylistTrack(String identifier) {
        return identifier != null && identifier.indexOf('/') >= 0;
    }

    /** Folder component of a playlist identifier, or null for a root track. */
    public static String playlistOf(String identifier) {
        if (identifier == null) return null;
        int slash = identifier.indexOf('/');
        return slash < 0 ? null : identifier.substring(0, slash);
    }

    /** File-name component of an identifier, without the playlist folder. */
    public static String trackNameOf(String identifier) {
        if (identifier == null) return null;
        int slash = identifier.indexOf('/');
        return slash < 0 ? identifier : identifier.substring(slash + 1);
    }

    /**
     * Human readable name for a playlist folder. Strips the
     * {@link #PLAYLIST_PREFIX}, splits the remainder on underscores and
     * whitespace, and upper-cases the first letter of each word. For
     * example {@code __playlist_my_cool_songs} becomes
     * {@code "My Cool Songs"}. Returns the folder name unchanged if it does
     * not carry the prefix.
     */
    public static String playlistDisplayName(String folder) {
        if (folder == null || !folder.startsWith(PLAYLIST_PREFIX)) return folder;
        String raw = folder.substring(PLAYLIST_PREFIX.length());
        String[] words = raw.split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) sb.append(w.substring(1));
        }
        return sb.length() == 0 ? raw : sb.toString();
    }

    private static boolean isPlaylistFolder(String name) {
        return isSafeName(name) && name.startsWith(PLAYLIST_PREFIX) && name.length() > PLAYLIST_PREFIX.length();
    }

    /** Reject empty, dot, and any name carrying a path separator. */
    private static boolean isSafeName(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.equals(".") || s.equals("..")) return false;
        return s.indexOf('/') < 0 && s.indexOf('\\') < 0;
    }
}
