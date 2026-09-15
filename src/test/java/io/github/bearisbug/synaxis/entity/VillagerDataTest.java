package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VillagerDataTest {
    @Test
    void levelNetworkSerialization() {
        NetworkBuffer buffer = NetworkBuffer.builder(5).build();
        VillagerData.Level.NETWORK_TYPE.write(buffer, VillagerData.Level.NOVICE);

        int expected = VillagerData.Level.NOVICE.ordinal() + 1;  // Network representation is ordinal + 1
        int readValue = buffer.read(NetworkBuffer.VAR_INT);
        assertEquals(expected, readValue);
    }

    @Test
    void levelNetworkDeserialization() {
        int networkValue = VillagerData.Level.NOVICE.ordinal() + 1;  // Simulate network value for NOVICE
        NetworkBuffer buffer = NetworkBuffer.builder(5).build();
        buffer.write(NetworkBuffer.VAR_INT, networkValue);

        VillagerData.Level level = VillagerData.Level.NETWORK_TYPE.read(buffer);
        assertEquals(VillagerData.Level.NOVICE, level);
    }
}
