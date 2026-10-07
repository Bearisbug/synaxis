package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.app.FileTrust;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Who may have written the files of a map release ({@code docs/DESIGN-M3.md} {@code API-M3-002} 文件信任):
 * the map maker is trusted as the operator is, so a file of the release is only taken from its owner.
 * On top of {@link FileTrust}, whose rule it does not change, it checks the real path, a symbolic link
 * by where it points and in the directory it really is in, and the owner: root, or the owner of
 * {@code synaxis.map.root}. A file system without the {@code unix} attribute view leaves both out.
 * <p>
 * A path that does not exist is passed over: the step that reads it reports it as missing.
 */
final class MapFileTrust {
    /** How the refusal of a file of the release group or others may write ends. */
    static final String WHO_WRITES = "a map release is written by its owner alone";

    /** What is read of a real path: its owner. */
    @FunctionalInterface
    interface Owners {
        /**
         * The owner of a real path.
         *
         * @param real a real path
         * @return its {@code unix:uid}, null when its file system has no {@code unix} attribute view
         * @throws IOException if it cannot be read
         */
        @Nullable Integer uid(Path real) throws IOException;
    }

    private final Owners owners;
    private final Path root;
    private final @Nullable Integer rootUid;

    /**
     * @param mapRoot {@code synaxis.map.root}, whose owner the files may have besides root
     * @param owners  how the owner of a file is read, {@link #unixUid} but in the tests
     * @throws IllegalArgumentException for a refused start, the map root cannot be read
     */
    MapFileTrust(Path mapRoot, Owners owners) {
        this.owners = owners;
        try {
            this.root = mapRoot.toRealPath();
            this.rootUid = owners.uid(root);
        } catch (IOException e) {
            throw new IllegalArgumentException(mapRoot.toAbsolutePath() + ": cannot be read: " + e, e);
        }
    }

    /** Whether the permissions and owners are checked at all: the file system of the map root has POSIX permissions. */
    boolean checked() {
        return rootUid != null;
    }

    /**
     * Checks one path of the release: its real path may be written by its owner alone, the directory it
     * really is in replaced only by them, and its owner is root or the owner of the map root.
     *
     * @param path a file or directory of the release
     * @throws IllegalArgumentException for a refused start, which starts with the real path
     */
    void check(Path path) {
        final Path real;
        try {
            real = path.toRealPath();
        } catch (NoSuchFileException _) {
            return;
        } catch (IOException e) {
            throw new IllegalArgumentException(path.toAbsolutePath() + ": cannot be read: " + e, e);
        }
        try {
            final Integer uid = owners.uid(real);
            final Integer allowed = rootUid;
            if (uid == null || allowed == null) return;
            final String writable = FileTrust.writableByOthers(real, WHO_WRITES);
            if (writable != null) throw new IllegalArgumentException(real + ": " + writable);
            if (uid != 0 && !uid.equals(allowed)) {
                throw new IllegalArgumentException(real + ": owned by uid " + uid + ", neither root nor uid " + allowed
                        + " that owns synaxis.map.root " + root);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(real + ": cannot be read: " + e, e);
        }
    }

    /** The owners as the file system has them. */
    static @Nullable Integer unixUid(Path real) throws IOException {
        // The JDK's unix view, which has the sticky bit FileTrust reads, comes with POSIX permissions
        if (!real.getFileSystem().supportedFileAttributeViews().contains("unix")) return null;
        return (Integer) Files.getAttribute(real, "unix:uid");
    }
}
