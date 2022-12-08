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
            String[] split1 = trim.split("=");
            map.put(split1[0],split1[1]);
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
            String[] split1 = trim.split("=");
            map.put(Integer.parseInt(split1[0]),split1[1]);
        }
        return map;
    }
}
