package dubbolite.jmx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * jmeter-plugins-dandelion 的 Dubbo 请求：JavaSampler + mm.jmeter.duser.SgrDubbo / DubboApp。
 * <p>
 * 插件按“参数位置”读取 JavaSampler 的 Arguments（名称无关）：
 * <pre>
 * SgrDubbo: URL, 接口名, group, version, timeout, orJar, format, appidKey, 方法名, 方法参数类型数组, 请求参数1..N
 * DubboApp: URL, 接口名, group, version, appidKey, 方法名, 方法参数类型数组, 请求参数1..N   （timeout 固定 3000）
 * </pre>
 */
public final class Dandelion {

    public static final String SGR = "mm.jmeter.duser.SgrDubbo";
    public static final String APP = "mm.jmeter.duser.DubboApp";

    private Dandelion() {
    }

    public static String classname(JmxDoc.TNode t) {
        Xml.Node p = JmxDoc.propNode(t.el, "classname");
        return p == null ? "" : p.text().trim();
    }

    static boolean matches(JmxDoc.TNode t) {
        String c = classname(t);
        return SGR.equals(c) || APP.equals(c);
    }

    public static boolean isSgr(JmxDoc.TNode t) {
        return SGR.equals(classname(t));
    }

    /** 通用字段名 → 参数位置 */
    static Map<String, Integer> layout(JmxDoc.TNode t) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("registryAddress", 0);
        m.put("interface", 1);
        m.put("group", 2);
        m.put("version", 3);
        if (isSgr(t)) {
            m.put("timeout", 4);
            m.put("orJar", 5);
            m.put("format", 6);
            m.put("appidKey", 7);
            m.put("method", 8);
            m.put("types", 9);
        } else {
            m.put("appidKey", 4);
            m.put("method", 5);
            m.put("types", 6);
        }
        return m;
    }

    private static Xml.Node collection(JmxDoc.TNode t) {
        Xml.Node args = JmxDoc.propNode(t.el, "arguments");
        return args == null ? null : JmxDoc.propNode(args, "Arguments.arguments");
    }

    static List<Xml.Node> args(JmxDoc.TNode t) {
        Xml.Node c = collection(t);
        return c == null ? new ArrayList<>() : c.children;
    }

    public static String arg(JmxDoc.TNode t, int i) {
        List<Xml.Node> a = args(t);
        if (i >= a.size()) {
            return "";
        }
        Xml.Node v = JmxDoc.propNode(a.get(i), "Argument.value");
        return v == null ? "" : v.text();
    }

    private static void setArg(JmxDoc.TNode t, int i, String value) {
        List<Xml.Node> a = args(t);
        while (a.size() <= i) {
            Xml.Node c = collection(t);
            if (c == null) {
                throw new IllegalArgumentException("JavaSampler 缺少 arguments，无法写入");
            }
            String name = "请求参数" + (a.size() - layout(t).get("types"));
            c.append(Xml.parseFragment("<elementProp name=\"" + Xml.escape(name) + "\" elementType=\"Argument\">\n"
                    + "  <stringProp name=\"Argument.name\">" + Xml.escape(name) + "</stringProp>\n"
                    + "  <stringProp name=\"Argument.value\"></stringProp>\n"
                    + "  <stringProp name=\"Argument.metadata\">=</stringProp>\n"
                    + "</elementProp>"));
            a = args(t);
        }
        JmxDoc.setProp(a.get(i), "Argument.value", value, "stringProp");
    }

    /** 解析 "['a.B','int']" / "[\"a.B\"]" 这类类型数组 */
    public static List<String> parseTypes(String s) {
        List<String> out = new ArrayList<>();
        String x = s == null ? "" : s.trim();
        if (x.startsWith("[")) {
            x = x.substring(1);
        }
        if (x.endsWith("]")) {
            x = x.substring(0, x.length() - 1);
        }
        for (String p : x.split(",")) {
            String v = p.trim();
            if (v.length() >= 2 && (v.charAt(0) == '\'' || v.charAt(0) == '"') && v.charAt(v.length() - 1) == v.charAt(0)) {
                v = v.substring(1, v.length() - 1).trim();
            }
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    static Map<String, String> fields(JmxDoc.TNode t) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : JmxDoc.CONN_FIELDS) {
            m.put(k, "");
        }
        Map<String, Integer> l = layout(t);
        for (Map.Entry<String, Integer> e : l.entrySet()) {
            if (!"types".equals(e.getKey())) {
                m.put(e.getKey(), arg(t, e.getValue()));
            }
        }
        if (!isSgr(t)) {
            m.put("timeout", "3000");
        }
        m.put("params", paramsText(t));
        return m;
    }

    /** 每个请求参数的原始文本（与类型一一对应） */
    public static List<String> rawParams(JmxDoc.TNode t) {
        int base = layout(t).get("types") + 1;
        List<String> types = parseTypes(arg(t, base - 1));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            out.add(arg(t, base + i));
        }
        return out;
    }

    /** 编辑器中的展示：[{"类型": 原始值}]，原始值保持原样（不是 JSON 时加引号） */
    static String paramsText(JmxDoc.TNode t) {
        int base = layout(t).get("types") + 1;
        List<String> types = parseTypes(arg(t, base - 1));
        if (types.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < types.size(); i++) {
            sb.append("  {").append(quote(types.get(i))).append(": ").append(display(arg(t, base + i))).append("}");
            sb.append(i < types.size() - 1 ? ",\n" : "\n");
        }
        return sb.append("]").toString();
    }

    static String display(String raw) {
        String v = raw.trim();
        if (v.isEmpty()) {
            return "\"\"";
        }
        char c = v.charAt(0);
        boolean jsonish = c == '{' || c == '[' || c == '"' || c == '-' || Character.isDigit(c)
                || v.equals("true") || v.equals("false") || v.equals("null") || v.startsWith("${");
        return jsonish ? v : quote(v);
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static void update(JmxDoc.TNode t, String key, String value) {
        Map<String, Integer> l = layout(t);
        if ("params".equals(key)) {
            List<String[]> items = parseTyped(value);
            int typesIdx = l.get("types");
            List<String> oldTypes = parseTypes(arg(t, typesIdx));
            List<String> newTypes = new ArrayList<>();
            for (String[] it : items) {
                newTypes.add(it[0]);
            }
            if (!newTypes.equals(oldTypes)) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < newTypes.size(); i++) {
                    sb.append(i > 0 ? "," : "").append('\'').append(newTypes.get(i)).append('\'');
                }
                setArg(t, typesIdx, sb.append(']').toString());
            }
            for (int i = 0; i < items.size(); i++) {
                String old = arg(t, typesIdx + 1 + i);
                if (!display(old).equals(items.get(i)[1])) {
                    setArg(t, typesIdx + 1 + i, items.get(i)[1]);
                }
            }
        } else if (l.containsKey(key) && !"types".equals(key)) {
            setArg(t, l.get(key), value);
        }
    }

    /** 解析编辑器文本 [{类型: 值}, ...]，值保留原始文本 */
    static List<String[]> parseTyped(String text) {
        String s = text == null ? "" : text;
        List<String[]> out = new ArrayList<>();
        int[] p = {0};
        skip(s, p);
        if (p[0] >= s.length()) {
            return out;
        }
        expect(s, p, '[');
        while (true) {
            skip(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ']') {
                return out;
            }
            expect(s, p, '{');
            skip(s, p);
            String key;
            if (p[0] < s.length() && (s.charAt(p[0]) == '"' || s.charAt(p[0]) == '\'')) {
                int start = p[0];
                p[0] = endOfString(s, start);
                key = s.substring(start + 1, p[0] - 1);
            } else {
                int start = p[0];
                while (p[0] < s.length() && s.charAt(p[0]) != ':') {
                    p[0]++;
                }
                key = s.substring(start, p[0]).trim();
            }
            skip(s, p);
            expect(s, p, ':');
            int vs = p[0];
            int depth = 0;
            while (p[0] < s.length()) {
                char c = s.charAt(p[0]);
                if (c == '"') {
                    p[0] = endOfString(s, p[0]);
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == ']' || c == '}') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                }
                p[0]++;
            }
            String raw = s.substring(vs, p[0]).trim();
            if (key.isEmpty() || raw.isEmpty()) {
                throw new IllegalArgumentException("第 " + (out.size() + 1) + " 个参数格式应为 {\"类型\": 值}");
            }
            out.add(new String[]{key, raw});
            expect(s, p, '}');
            skip(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ',') {
                p[0]++;
            }
        }
    }

    private static int endOfString(String s, int start) {
        char q = s.charAt(start);
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == q) {
                return i + 1;
            }
            i++;
        }
        throw new IllegalArgumentException("字符串没有结束引号");
    }

    private static void skip(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) {
            p[0]++;
        }
    }

    private static void expect(String s, int[] p, char c) {
        skip(s, p);
        if (p[0] >= s.length() || s.charAt(p[0]) != c) {
            throw new IllegalArgumentException("参数格式应为 [{\"类型\": 值}, ...]，在第 " + (p[0] + 1) + " 个字符处需要 " + c);
        }
        p[0]++;
    }
}
