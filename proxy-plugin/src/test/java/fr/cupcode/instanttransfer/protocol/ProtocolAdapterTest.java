package fr.cupcode.instanttransfer.protocol;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.junit.jupiter.api.Test;

import static fr.cupcode.instanttransfer.protocol.ProtocolAdapter.dimensionKey;
import static fr.cupcode.instanttransfer.protocol.ProtocolAdapter.Era.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolAdapterTest {

    @Test
    void eraSelectionBoundaries() {
        assertEquals(MODERN,           ProtocolAdapter.forClientVersion(ClientVersion.V_1_20_5).era());
        assertEquals(CONFIG_PHASE_NAME,ProtocolAdapter.forClientVersion(ClientVersion.V_1_20_2).era());
        assertEquals(LEGACY_NAME,      ProtocolAdapter.forClientVersion(ClientVersion.V_1_16).era());
        assertEquals(LEGACY_NUMERIC,   ProtocolAdapter.forClientVersion(ClientVersion.V_1_13).era());
        assertEquals(MODERN,           ProtocolAdapter.forClientVersion(ClientVersion.getLatest()).era());
    }

    @Test
    void modernEraUsesParticlesList() {
        ProtocolAdapter a = ProtocolAdapter.forClientVersion(ClientVersion.V_1_21);
        assertTrue(a.effectParticlesAreList());
        assertTrue(a.effectMetadataResetSupported());
        assertEquals(11, a.effectAmbientIndex());
    }

    @Test
    void configEraUsesColorSlot() {
        ProtocolAdapter a = ProtocolAdapter.forClientVersion(ClientVersion.V_1_20_2);
        assertFalse(a.effectParticlesAreList());
        assertTrue(a.effectMetadataResetSupported());
    }

    @Test
    void dimensionKeyPrefersRegistryNameWhenEraSupportsIt() {
        assertEquals("minecraft:overworld",
                dimensionKey("minecraft:overworld", null, LEGACY_NAME));
        assertEquals("minecraft:overworld",
                dimensionKey("minecraft:overworld", 0, MODERN));
    }

    @Test
    void dimensionKeyIdFormIsHashPrefixed() {
        assertEquals("#3", dimensionKey(null, 3, MODERN));
        assertEquals("#-1", dimensionKey("", -1, LEGACY_NUMERIC));
    }

    @Test
    void dimensionKeyNumericOnlyEraIgnoresNames() {
        assertEquals("#0", dimensionKey("minecraft:overworld", 0, LEGACY_NUMERIC));
    }

    @Test
    void dimensionKeyNullInputsYieldNull() {
        assertNull(dimensionKey(null, null, MODERN));
    }

    @Test
    void adaptersAreCachedInstances() {
        assertSame(ProtocolAdapter.forClientVersion(ClientVersion.V_1_20_5),
                   ProtocolAdapter.forClientVersion(ClientVersion.V_1_21_4));
    }
}
