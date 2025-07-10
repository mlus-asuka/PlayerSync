package vip.fubuki.playersync.util;

import java.util.HashMap;
import java.util.Map;

public class LocalJsonUtil {
    public static Map<String,String> StringToMap(String param) {
        Map<String,String> map = new HashMap<>();
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
            map.put(key, value);
        }
        return map;
    }

    public static Map<Integer,String> StringToEntryMap(String param) {
        Map<Integer,String> map = new HashMap<>();
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
            map.put(Integer.parseInt(key), value);
        }
        return map;
    }
}
