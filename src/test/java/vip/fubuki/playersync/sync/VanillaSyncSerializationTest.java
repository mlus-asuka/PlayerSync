package vip.fubuki.playersync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the database string encoding in {@link VanillaSync}.
 *
 * These run in a plain JVM without the game bootstrapped, so they may only touch
 * logic that does not reach registries, DataFixers or Forge config values
 * (hence {@code serialize(String, boolean)} instead of the config-reading overload).
 */
class VanillaSyncSerializationTest {

    /** A realistic item NBT string containing every character the legacy format mangles. */
    private static final String ITEM_NBT =
            "{Count:1b,id:\"minecraft:diamond_sword\",tag:{Damage:0,display:{Name:'{\"text\":\"Excalibur\"}'}}}";

    // --- Base64 format (current default) ---

    @Test
    void base64SerializationIsPrefixedAndRoundTrips() {
        String encoded = VanillaSync.serialize(ITEM_NBT, false);
        assertTrue(encoded.startsWith("B64:"), "new format must carry the B64: marker");
        assertEquals(ITEM_NBT, VanillaSync.deserializeString(encoded));
    }

    @Test
    void base64RoundTripsUnicode() {
        String nbt = "{display:{Name:'{\"text\":\"göldene Axt 你好 §6\"}'}}";
        assertEquals(nbt, VanillaSync.deserializeString(VanillaSync.serialize(nbt, false)));
    }

    @Test
    void emptyCompoundEncodesToTheEmptySentinel() {
        // deserializeAndCreatePlaceholderIfNeeded treats exactly "B64:e30=" as the empty slot.
        // If the encoding of "{}" ever changes, empty-slot detection silently breaks.
        assertEquals("B64:e30=", VanillaSync.serialize("{}", false));
    }

    @Test
    void base64OutputContainsNoMapSeparators() {
        // store() persists inventories as HashMap.toString() ("{0=..., 1=...}") and
        // LocalJsonUtil parses them back by splitting on ','. The encoded values must
        // therefore never contain ',' or this round trip corrupts whole inventories.
        String encoded = VanillaSync.serialize(ITEM_NBT, false);
        assertTrue(!encoded.contains(","), "encoded values must not contain the map separator ','");
    }

    // --- Legacy replacement format ---

    @Test
    void legacySerializationRoundTrips() {
        String encoded = VanillaSync.serialize(ITEM_NBT, true);
        assertEquals(ITEM_NBT, VanillaSync.deserializeString(encoded));
    }

    @Test
    void legacySerializationReplacesAllMapSeparators() {
        String encoded = VanillaSync.serialize(ITEM_NBT, true);
        assertTrue(!encoded.contains(",") && !encoded.contains("{") && !encoded.contains("}")
                && !encoded.contains("\"") && !encoded.contains("'"),
                "legacy encoding must remove every character the DB/map format is sensitive to, got: " + encoded);
    }

    @Test
    void legacyDecodingOfStoredData() {
        // Format produced by versions before the B64 marker was introduced.
        assertEquals("{Count:1b,id:\"minecraft:stone\"}",
                VanillaSync.deserializeString("<Count:1b|id:^minecraft:stone^>"));
    }

    @Test
    void legacyFormatIsLossyForReplacementCharacters() {
        // Documents the known defect that motivated the B64 format: a literal '|' in the
        // input (e.g. in an item name) is turned into ',' by decoding. If this test ever
        // fails, the legacy format learned escaping — re-check deserializeString's fallback.
        String nbt = "{display:{Name:'a|b'}}";
        assertNotEquals(nbt, VanillaSync.deserializeString(VanillaSync.serialize(nbt, true)));
    }

    // --- Fallback behavior ---

    @Test
    void corruptBase64FallsBackToLegacyDecoding() {
        // Illegal Base64 after the marker must not throw; deserializeString falls back to
        // legacy decoding of the *entire* string, marker included.
        assertEquals("B64:{a\"b}", VanillaSync.deserializeString("B64:<a^b>"));
    }

    @Test
    void plainStringWithoutMarkerIsLegacyDecoded() {
        assertEquals("plain", VanillaSync.deserializeString("plain"));
        assertEquals("", VanillaSync.deserializeString(""));
    }
}
