package vip.fubuki.playersync.util;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class LocalJsonUtil {
    private static <K> Map<K, String> stringToGenericMap(String param, Function<String, K> keyParser) {
        Map<K, String> map = new HashMap<>();
        String s1 = param.substring(1,param.length()-1);
        String s2 = s1.trim();
        String[] split = s2.split(",");
        for (int i = split.length - 1; i >= 0; i--) {
            String trim = split[i].trim();

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
