package io.github.bearisbug.synaxis.game.client.farview;

import java.util.List;

/**
 * The Distant Horizons side of the far view, as this package sees it: nothing here names a DH type,
 * so the mod loads and runs without DH. The one implementation is {@code farview.dh.DhFarView},
 * loaded by name and only once Fabric says DH is there ({@link FarViewClient#gate}).
 */
public interface FarViewDh {

    /**
     * DH's own version, {@code DhApi.getModVersion()}.
     *
     * @return e.g. {@code 3.3.2}
     */
    String modVersion();

    /**
     * DH's API major version, {@code DhApi.getApiMajorVersion()}.
     *
     * @return e.g. 7
     */
    int apiMajorVersion();

    /**
     * The members of the DH surface table ({@code API-M3-015} DH 接入面) that the running DH lacks
     * or has with another signature. Found by reflection; no DH method is called.
     *
     * @return one line per member that does not match, empty when all do
     */
    List<String> surfaceProblems();

    /**
     * Binds the save folder override and the DH events this mod listens to. Called once, after the
     * version and the surface have been checked.
     */
    void install();
}
