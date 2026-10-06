package io.github.bearisbug.synaxis.game.client.farview.dh;

import com.seibel.distanthorizons.api.interfaces.override.levelHandling.IDhApiSaveStructure;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import io.github.bearisbug.synaxis.game.client.farview.FarViewClient;
import io.github.bearisbug.synaxis.game.client.farview.FarViewTestHook;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pins Distant Horizons' library to {@code synaxis/farview/dh/<view_key>/} ({@code API-M3-015} 钉目录).
 * DH asks once per client level, when it builds it; the announcement came in the configuration phase,
 * before the level, so the connection's key is known by then. A Synaxis connection without a far view
 * gets the empty {@code dh/off/}; on any other server the answer is null and DH keeps its own folder.
 */
final class DhSaveFolder implements IDhApiSaveStructure {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    @Override
    public @Nullable File overrideFilePath(File defaultFolder, IDhApiLevelWrapper level) {
        final Path library = FarViewClient.libraryDirectory();
        if (library == null) return null;
        if (!FarViewTestHook.pinOff && library.getFileName().toString().equals(FarViewClient.NO_FAR_VIEW)) return null;
        try {
            Files.createDirectories(library);
        } catch (IOException e) {
            LOGGER.warn("{}could not create {}, leaving Distant Horizons its own folder: {}", FarViewClient.PREFIX, library,
                    e.toString());
            return null;
        }
        return library.toFile();
    }
}
