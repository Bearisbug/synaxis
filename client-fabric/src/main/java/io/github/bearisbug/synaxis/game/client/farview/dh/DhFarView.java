package io.github.bearisbug.synaxis.game.client.farview.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.override.levelHandling.IDhApiSaveStructure;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import io.github.bearisbug.synaxis.game.client.farview.FarViewClient;
import io.github.bearisbug.synaxis.game.client.farview.FarViewDh;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distant Horizons 3.3.2 for the far view ({@code docs/DESIGN-M3.md} {@code API-M3-015}): the version
 * lock and the probe of the DH core members the bake relies on ({@link #SURFACE}, the 否 rows of the
 * DH 接入面 table), and, once both hold, the save folder override and the five events. Every class of
 * this mod that names a DH type is in this package; {@link FarViewClient} loads this one by name.
 */
public final class DhFarView implements FarViewDh {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    private static final String CORE = "com.seibel.distanthorizons.core.";
    private static final String FULL_DATA_SOURCE = CORE + "dataObjects.fullData.sources.FullDataSourceV2";
    private static final String LEVEL_WRAPPER = CORE + "wrapperInterfaces.world.ILevelWrapper";
    private static final String DH_LEVEL = CORE + "level.IDhLevel";
    private static final String PROVIDER = CORE + "file.fullDatafile.V2.FullDataSourceProviderV2";
    private static final String REPO = CORE + "sql.repo.FullDataSourceV2Repo";

    /** How a member of the surface is looked up. */
    public enum Kind {
        /** A public method, static or not, by name and parameter types, with its return type. */
        METHOD,
        /** A public static method. */
        STATIC_METHOD,
        /** A public field, with its type. */
        FIELD,
        /** {@code type} is a supertype of the owner. */
        SUPERTYPE
    }

    /**
     * One row of the surface.
     *
     * @param owner      the class
     * @param kind       what is looked up
     * @param name       the method or field, empty for a supertype
     * @param parameters the parameter types of a method
     * @param type       the return type, the field type or the supertype
     */
    public record Member(String owner, Kind kind, String name, List<String> parameters, String type) {
        @Override
        public String toString() {
            return switch (kind) {
                case SUPERTYPE -> owner + " is a " + type;
                case FIELD -> owner + "#" + name + ": " + type;
                default -> owner + "#" + name + "(" + String.join(", ", parameters) + "): " + type;
            };
        }
    }

    /**
     * The DH core members the far view uses, none of them public API: the bake builds data sources
     * and hands them to the level, the write check counts rows on DH's own connection, and
     * {@code FullDataSourceProviderV2#get} serves only the V1 sampling of the tests. Checked by
     * reflection at start and by {@code DhSurfaceTest} against the jar this mod compiles against.
     */
    public static final List<Member> SURFACE = List.of(
            new Member(FULL_DATA_SOURCE, Kind.STATIC_METHOD, "createEmpty", List.of("long"), FULL_DATA_SOURCE),
            new Member(FULL_DATA_SOURCE, Kind.METHOD, "close", List.of(), "void"),
            new Member(FULL_DATA_SOURCE, Kind.FIELD, "applyToParent", List.of(), "java.lang.Boolean"),
            new Member(FULL_DATA_SOURCE, Kind.SUPERTYPE, "", List.of(),
                    "com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource"),
            new Member(CORE + "pos.DhSectionPos", Kind.STATIC_METHOD, "encode", List.of("byte", "int", "int"), "long"),
            new Member(LEVEL_WRAPPER, Kind.SUPERTYPE, "", List.of(),
                    "com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper"),
            new Member(LEVEL_WRAPPER, Kind.METHOD, "getDhLevel", List.of(), DH_LEVEL),
            new Member(DH_LEVEL, Kind.METHOD, "updateDataSourcesAsync", List.of(FULL_DATA_SOURCE),
                    "java.util.concurrent.CompletableFuture"),
            new Member(DH_LEVEL, Kind.METHOD, "getFullDataProvider", List.of(), PROVIDER),
            new Member(PROVIDER, Kind.FIELD, "repo", List.of(), REPO),
            new Member(REPO, Kind.METHOD, "queryDictionaryFirst", List.of("java.lang.String"), "java.util.Map"),
            new Member(PROVIDER, Kind.METHOD, "get", List.of("long"), FULL_DATA_SOURCE));

    private static volatile @Nullable IDhApiLevelWrapper level;

    /** Fabric loads this by name, once DH is known to be there. */
    public DhFarView() {
    }

    @Override
    public String modVersion() {
        return DhApi.getModVersion();
    }

    @Override
    public int apiMajorVersion() {
        return DhApi.getApiMajorVersion();
    }

    @Override
    public List<String> surfaceProblems() {
        return surfaceProblems(SURFACE, DhFarView.class.getClassLoader());
    }

    /**
     * The members a class loader does not have as listed.
     *
     * @param members the members to look up
     * @param loader  where to look them up; nothing is initialized
     * @return one line per member missing or of another signature
     */
    public static List<String> surfaceProblems(List<Member> members, ClassLoader loader) {
        final List<String> problems = new ArrayList<>();
        for (Member member : members) {
            try {
                final Class<?> owner = Class.forName(member.owner(), false, loader);
                final String found = switch (member.kind()) {
                    case SUPERTYPE -> Class.forName(member.type(), false, loader).isAssignableFrom(owner) ? member.type()
                            : "not a " + member.type();
                    case FIELD -> {
                        final Field field = owner.getField(member.name());
                        yield Modifier.isStatic(field.getModifiers()) ? "static " + field.getType().getName()
                                : field.getType().getName();
                    }
                    case METHOD, STATIC_METHOD -> {
                        final Class<?>[] parameters = new Class<?>[member.parameters().size()];
                        for (int i = 0; i < parameters.length; i++) parameters[i] = type(member.parameters().get(i), loader);
                        final Method method = owner.getMethod(member.name(), parameters);
                        final boolean isStatic = Modifier.isStatic(method.getModifiers());
                        yield isStatic != (member.kind() == Kind.STATIC_METHOD) ? (isStatic ? "static" : "not static")
                                : method.getReturnType().getName();
                    }
                };
                if (!found.equals(member.type())) problems.add(member + " is " + found);
            } catch (ReflectiveOperationException | LinkageError e) {
                problems.add(member + " is missing: " + e);
            }
        }
        return problems;
    }

    private static Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
        return switch (name) {
            case "byte" -> byte.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "boolean" -> boolean.class;
            default -> Class.forName(name, false, loader);
        };
    }

    @Override
    public void install() {
        DhApi.events.bind(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
            @Override
            public void afterDistantHorizonsInit(DhApiEventParam<Void> event) {
                DhApi.overrides.bind(IDhApiSaveStructure.class, new DhSaveFolder());
            }
        });
        DhApi.events.bind(DhApiWorldLoadEvent.class, new DhApiWorldLoadEvent() {
            @Override
            public void onWorldLoad(DhApiEventParam<DhApiWorldLoadEvent.EventParam> event) {
                DhSession.worldLoaded();
            }
        });
        DhApi.events.bind(DhApiWorldUnloadEvent.class, new DhApiWorldUnloadEvent() {
            @Override
            public void onWorldUnload(DhApiEventParam<DhApiWorldUnloadEvent.EventParam> event) {
                level = null;
                FarViewClient.unloaded("Distant Horizons unloaded the world");
                DhSession.worldUnloaded();
            }
        });
        DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<DhApiLevelLoadEvent.EventParam> event) {
                if (!FarViewClient.synaxisConnection()) return;
                final IDhApiLevelWrapper loaded = event.value.levelWrapper;
                level = loaded;
                FarViewClient.levelLoaded(loaded, DhLibraryBaker::start);
            }
        });
        DhApi.events.bind(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent() {
            @Override
            public void onLevelUnload(DhApiEventParam<DhApiLevelUnloadEvent.EventParam> event) {
                if (event.value.levelWrapper == level) level = null;
                FarViewClient.unloaded("Distant Horizons unloaded the level");
            }
        });
        LOGGER.info("{}Distant Horizons {} (API {}) found, far view events bound", FarViewClient.PREFIX, DhApi.getModVersion(),
                DhApi.getApiMajorVersion());
    }

    /**
     * The level DH loaded last on a Synaxis connection, for the test hook's queries.
     *
     * @return the level wrapper, null when none is loaded
     */
    static @Nullable IDhApiLevelWrapper level() {
        return level;
    }
}
