package dev.gameengine.entity;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;

public record VillagerData(
        VillagerType type,
        VillagerProfession profession,
        Level level
) {
    public static final VillagerData DEFAULT = new VillagerData(VillagerType.DESERT, VillagerProfession.NONE, Level.NOVICE);

    public static final NetworkBuffer.Type<VillagerData> NETWORK_TYPE = NetworkBufferTemplate.template(
            VillagerType.NETWORK_TYPE, VillagerData::type,
            VillagerProfession.NETWORK_TYPE, VillagerData::profession,
            Level.NETWORK_TYPE, VillagerData::level,
            VillagerData::new);

    public VillagerData withType(VillagerType type) {
        return new VillagerData(type, this.profession, this.level);
    }

    public VillagerData withProfession(VillagerProfession profession) {
        return new VillagerData(this.type, profession, this.level);
    }

    public VillagerData withLevel(Level level) {
        return new VillagerData(this.type, this.profession, level);
    }

    public enum Level {
        NOVICE,
        APPRENTICE,
        JOURNEYMAN,
        EXPERT,
        MASTER;

        private static final Level[] VALUES = values();

        private int toProtocolId() {
            return this.ordinal() + 1;  // Villager levels are 1-indexed
        }

        private static Level fromProtocolId(int value) {
            return VALUES[value - 1];
        }

        public static final NetworkBuffer.Type<Level> NETWORK_TYPE = NetworkBuffer.VAR_INT.transform(Level::fromProtocolId, Level::toProtocolId);
    }
}
