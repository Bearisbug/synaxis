package net.minestom.server;

import net.minestom.server.utils.MajorMinorVersion;

/**
 * This class contains all the generated constants for {@link MinecraftServer}
 * <br>
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
sealed interface MinecraftConstants permits MinecraftServer {
    String VERSION_NAME = "26.2";

    int PROTOCOL_VERSION = 776;

    int DATA_VERSION = 4903;

    MajorMinorVersion RESOURCE_PACK_VERSION = new MajorMinorVersion(88, 0);

    MajorMinorVersion DATA_PACK_VERSION = new MajorMinorVersion(107, 1);
}
