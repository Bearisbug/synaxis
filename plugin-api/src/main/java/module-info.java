module io.github.bearisbug.synaxis.plugin {
    requires transitive io.github.bearisbug.synaxis;
    requires transitive io.github.bearisbug.synaxis.game.protocol;
    requires transitive org.slf4j;

    exports io.github.bearisbug.synaxis.plugin;
    exports io.github.bearisbug.synaxis.plugin.instance;
    exports io.github.bearisbug.synaxis.plugin.play;
}
