package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The write {@link WorldFlagsImpl#set} refuses before any SQL ({@code docs/DESIGN-M3.md} {@code API-M3-013}
 * 写条): a value nesting arrays and objects deeper than Gson reads back. No database is needed: the data
 * source is one every connection to fails, so a write that got as far as SQL counts a try.
 */
@Timeout(60)
class WorldFlagsWriteTest {

    private static final String DEEP = "quest:deep";

    /** {@code depth} arrays, one inside the other, around a number. */
    private static JsonElement nested(int depth) {
        JsonElement value = new JsonPrimitive(0);
        for (int level = 0; level < depth; level++) {
            final JsonArray array = new JsonArray();
            array.add(value);
            value = array;
        }
        return value;
    }

    @Test
    void aValueNestedPast255LevelsFailsBeforeAnySql() {
        final WorldFlagStoreDbTest.Unreachable down = new WorldFlagStoreDbTest.Unreachable();
        final WorldFlagsImpl flags = new WorldFlagsImpl(down, RedisLink.disabled("r0-c1"), MapFixture.MAP_ID, "r0",
                MapFixture.BOUNDS);
        try {
            final FlagWrite.Failed refused = new FlagWrite.Failed("the value of world flag " + DEEP
                    + " nests arrays and objects deeper than 255 levels, which a read cannot parse back");
            assertEquals(refused, flags.set(DEEP, nested(256), 0).join(), "256 arrays");
            final JsonObject inObject = new JsonObject();
            inObject.add("gate", nested(255));
            assertEquals(refused, flags.set(DEEP, inObject, 3).join(), "an object around 255 arrays");
            assertEquals(0, down.connections.get(), "refused before any SQL");

            final JsonElement deepest = nested(255);
            assertEquals(deepest, JsonParser.parseString(deepest.toString()), "a read takes 255 levels");
            assertInstanceOf(FlagWrite.Failed.class, flags.set(DEEP, deepest, 0).join());
            assertEquals(1, down.connections.get(), "255 levels go on to the database");
        } finally {
            flags.close();
        }
    }
}
