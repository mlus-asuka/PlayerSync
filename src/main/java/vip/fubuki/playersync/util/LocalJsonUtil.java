package vip.fubuki.playersync.util;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class LocalJsonUtil {
    private static <K> Map<K, String> stringToGenericMap(String param, Function<String, K> keyParser) {
        Map<K, String> map = new HashMap<>();

        // check if string is at least minimal json
        if (param == null || param.length() < 2 || param.equals("{}")) {
            return map;
        }

        // extract string within outermost json brackets {}
        String s1 = param.substring(param.indexOf('{')+1, param.lastIndexOf('}')).trim();
        if (s1.isEmpty()) {
            return map;
        }

        // split all json elements
        for (String split : s1.split(",")) {
            String trim = split.trim();

            // only check for the first "=" as the values also contain additional "="
            int equalIndex = trim.indexOf('=');
            if (equalIndex < 0)
                continue;

            String key = trim.substring(0, equalIndex);
            String value = trim.substring(equalIndex + 1);
            map.put(keyParser.apply(key), value);
        }
        return map;
    }

    public static Map<String, String> StringToMap(String param) {
        return stringToGenericMap(param, Function.identity());
    }

    public static Map<Integer, String> StringToEntryMap(String param) {
        return stringToGenericMap(param, Integer::parseInt);
    }
}
