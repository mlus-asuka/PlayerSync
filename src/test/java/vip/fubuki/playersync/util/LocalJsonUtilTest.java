package vip.fubuki.playersync.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pseudo-JSON map format used to persist inventories:
 * {@code store()} writes {@code HashMap.toString()} into the database and
 * {@link LocalJsonUtil} parses it back on restore.
 */
class LocalJsonUtilTest {

    @Test
    void nullEmptyAndBracesYieldEmptyMap() {
        assertTrue(LocalJsonUtil.StringToEntryMap(null).isEmpty());
        assertTrue(LocalJsonUtil.StringToEntryMap("").isEmpty());
        assertTrue(LocalJsonUtil.StringToEntryMap("{}").isEmpty());
        assertTrue(LocalJsonUtil.StringToEntryMap("{ }").isEmpty());
    }

    @Test
    void parsesSimpleEntries() {
        Map<Integer, String> map = LocalJsonUtil.StringToEntryMap("{0=foo, 1=bar}");
        assertEquals(2, map.size());
        assertEquals("foo", map.get(0));
        assertEquals("bar", map.get(1));
    }

    @Test
    void valuesKeepTheirEqualsSigns() {
        // Base64 padding uses '='; only the first '=' may be treated as the key separator
        // or every padded inventory slot would be truncated on restore.
        Map<Integer, String> map = LocalJsonUtil.StringToEntryMap("{0=B64:e30=, 1=B64:YWJjZA==}");
        assertEquals("B64:e30=", map.get(0));
        assertEquals("B64:YWJjZA==", map.get(1));
    }

    @Test
    void entriesWithoutSeparatorAreSkipped() {
        Map<Integer, String> map = LocalJsonUtil.StringToEntryMap("{garbage, 1=ok}");
        assertEquals(1, map.size());
        assertEquals("ok", map.get(1));
    }

    @Test
    void roundTripsTheExactFormatStoreWrites() {
        // store() persists inventories as HashMap.toString(); restoring must yield the
        // identical map as long as values contain no ',' (guaranteed by both encodings,
        // see VanillaSyncSerializationTest).
        Map<Integer, String> original = new HashMap<>();
        original.put(0, "B64:e30=");
        original.put(8, "B64:eyJDb3VudCI6MWJ9");
        original.put(35, "B64:YWJjZA==");

        assertEquals(original, LocalJsonUtil.StringToEntryMap(original.toString()));
    }

    @Test
    void commasInValuesCorruptTheEntry() {
        // Documents the format's hard limitation: ',' is the entry separator and there is
        // no escaping. Both serialization formats must therefore never emit ','.
        Map<Integer, String> map = LocalJsonUtil.StringToEntryMap("{0=a,b}");
        assertEquals(1, map.size());
        assertEquals("a", map.get(0)); // "b" is silently dropped
    }

    @Test
    void stringToMapKeepsStringKeys() {
        Map<String, String> map = LocalJsonUtil.StringToMap("{uuid=abc, name=steve}");
        assertEquals("abc", map.get("uuid"));
        assertEquals("steve", map.get("name"));
    }
}
