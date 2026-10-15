package dubbolite.run;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 与 JMeter 的 JMeterVariables 方法签名保持一致，脚本中的 vars.xxx 可直接沿用 */
public class Vars {

    private final Map<String, Object> map = new LinkedHashMap<>();

    public synchronized String get(String key) {
        Object v = map.get(key);
        return v == null ? null : (v instanceof String ? (String) v : String.valueOf(v));
    }

    public synchronized void put(String key, String value) {
        map.put(key, value);
    }

    public synchronized void putObject(String key, Object value) {
        map.put(key, value);
    }

    public synchronized Object getObject(String key) {
        return map.get(key);
    }

    public synchronized Object remove(String key) {
        return map.remove(key);
    }

    public synchronized void putAll(Map<String, ?> vars) {
        map.putAll(vars);
    }

    public synchronized Set<Map.Entry<String, Object>> entrySet() {
        return new LinkedHashMap<>(map).entrySet();
    }

    public Iterator<Map.Entry<String, Object>> getIterator() {
        return entrySet().iterator();
    }

    public int getIteration() {
        return 1;
    }

    public String getThreadName() {
        return "Dubbo Lite 1-1";
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized boolean isEmpty() {
        return map.isEmpty();
    }

    public synchronized Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            m.put(e.getKey(), e.getValue() == null ? "null" : String.valueOf(e.getValue()));
        }
        return m;
    }
}
