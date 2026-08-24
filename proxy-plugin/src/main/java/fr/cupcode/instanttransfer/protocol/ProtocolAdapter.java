package fr.cupcode.instanttransfer.protocol;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version-sensitive constants, selected once per player. One immutable instance per protocol
 * era (see spec section 4.1). Nothing here talks to packets — callers feed in values they
 * already decoded, keeping this class unit-testable without a packetevents object graph.
 */
public final class ProtocolAdapter {

    public enum Era { LEGACY_NUMERIC, LEGACY_NAME, CONFIG_PHASE_NAME, MODERN }

    private static final Map<Era, ProtocolAdapter> CACHE = new ConcurrentHashMap<>();

    private final Era era;
    private final int effectColorIndex;
    private final int effectAmbientIndex;
    private final boolean particlesAreList;
    private final boolean metadataResetSupported;

    private ProtocolAdapter(Era era, int colorIdx, int ambientIdx, boolean list, boolean supported) {
        this.era = era;
        this.effectColorIndex = colorIdx;
        this.effectAmbientIndex = ambientIdx;
        this.particlesAreList = list;
        this.metadataResetSupported = supported;
    }

    static {
        // Verified against the running codebase for these eras (SeamlessPacketListener pre-refactor).
        register(new EraDef(Era.MODERN,             10, 11, true,  true));   // 1.20.5+: idx10 PARTICLES list
        register(new EraDef(Era.CONFIG_PHASE_NAME,  10, 11, false, true));   // 1.20.2–1.20.4: idx10 color INT
        // Eras below 1.20.2: verified historical LivingEntity indices are NOT uniform within
        // either era (1.13.x → 8/9, 1.14–1.16 → 9/10, 1.17+ → 10/11; wiki.vg archives), so no
        // single pair is safe per era. An era left with supported=false degrades gracefully
        // (swirl-reset skipped, removal packet still sent).
        register(new EraDef(Era.LEGACY_NAME,       -1, -1, false, false));  // 1.16 → 9/10 but 1.17–1.20.1 → 10/11
        register(new EraDef(Era.LEGACY_NUMERIC,    -1, -1, false, false));  // 1.13.x → 8/9 but 1.14–1.15 → 9/10
    }

    private record EraDef(Era era, int colorIdx, int ambientIdx, boolean list, boolean supported) {}

    private static void register(EraDef d) {
        CACHE.put(d.era(), new ProtocolAdapter(d.era(), d.colorIdx(), d.ambientIdx(),
                d.list(), d.supported()));
    }

    public static ProtocolAdapter forClientVersion(ClientVersion v) {
        if (v.isNewerThanOrEquals(ClientVersion.V_1_20_5)) return of(Era.MODERN);
        if (v.isNewerThanOrEquals(ClientVersion.V_1_20_2)) return of(Era.CONFIG_PHASE_NAME);
        if (v.isNewerThanOrEquals(ClientVersion.V_1_16))   return of(Era.LEGACY_NAME);
        return of(Era.LEGACY_NUMERIC);
    }

    private static ProtocolAdapter of(Era e) {
        return CACHE.computeIfAbsent(e, x -> { throw new IllegalStateException("unregistered era"); });
    }

    public Era era() { return era; }
    public int effectColorIndex() { return effectColorIndex; }
    public int effectAmbientIndex() { return effectAmbientIndex; }
    public boolean effectParticlesAreList() { return particlesAreList; }
    public boolean effectMetadataResetSupported() { return metadataResetSupported; }

    /**
     * Stable comparison key for a dimension type. Name-form wins wherever the era supports
     * names; numeric-only eras hash-prefix the id; id-form is "#"-prefixed everywhere so it can
     * never collide with a name.
     */
    public static String dimensionKey(String registryIdentifier, Integer numericId, Era era) {
        if (registryIdentifier != null && !registryIdentifier.isEmpty()
                && era != Era.LEGACY_NUMERIC) {
            return registryIdentifier;
        }
        if (numericId != null) {
            return "#" + numericId;
        }
        return null;
    }
}
