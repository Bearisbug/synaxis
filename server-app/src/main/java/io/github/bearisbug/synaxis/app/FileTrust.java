package io.github.bearisbug.synaxis.app;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Who besides its owner may have written a file the host trusts: the configuration file
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-023}) and the files of a map release
 * ({@code docs/DESIGN-M3.md} {@code API-M3-002}), one rule for both. Like {@link ConfigFile} it has no
 * logger and names nothing of the engine: it runs before the logging is set up.
 */
public final class FileTrust {

    /** The group's and the others' write bits and the sticky bit of a {@code unix:mode}. */
    private static final int GROUP_OR_OTHERS_WRITE = 0022;
    private static final int STICKY = 01000;

    private FileTrust() {
    }

    /**
     * Why someone besides the owner of the file may have written it, null when nobody may: the
     * configuration file decides the login mode, the gates and the whitelist, a map release what
     * players are sent and where they are sent to. Two things refuse the file:
     * <ul>
     *     <li>group or others may write the file itself;</li>
     *     <li>group or others may write the directory the file is in, which has no sticky bit, so they
     *     may replace the file.</li>
     * </ul>
     * Three things are not looked at:
     * <ul>
     *     <li>the owner: a file root owns and the service user only reads is fine;</li>
     *     <li>the directories above the one the file is in: {@code docs/DESIGN-PROD.md} §23 keeps the
     *     working directory the service user's alone;</li>
     *     <li>a file someone else put into the directory before it got its sticky bit.</li>
     * </ul>
     *
     * @param file      the file, on a file system with the {@code unix} attribute view
     * @param whoWrites who alone may write the file, appended after {@code ; } to the refusal of a file
     *                  that group or others may write; the refusal of its directory has its own ending
     * @return why the file is refused, or null
     * @throws IOException if the permissions cannot be read
     */
    public static @Nullable String writableByOthers(Path file, String whoWrites) throws IOException {
        final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE))
            return "writable by group or others (" + PosixFilePermissions.toString(permissions)
                    + "); " + whoWrites;
        final Path directory = file.getParent();
        final int mode = (Integer) Files.getAttribute(directory, "unix:mode");
        if ((mode & GROUP_OR_OTHERS_WRITE) != 0 && (mode & STICKY) == 0)
            return "its directory " + directory + " is writable by group or others without the sticky bit ("
                    + PosixFilePermissions.toString(Files.getPosixFilePermissions(directory))
                    + "); they may replace the file";
        return null;
    }
}
