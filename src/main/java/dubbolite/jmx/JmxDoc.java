package dubbolite.jmx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个已打开的 JMX 文件：在保真 XML 树之上建立 JMeter 的 "测试元素 + hashTree" 结构。
 */
public final class JmxDoc {

    public static final String YAJAD_SAMPLER = "com.yajad.jmeter.sampler.DubboSampler";
    public static final String NINGYU_SAMPLER = "io.github.ningyu.jmeter.plugin.dubbo.sample.DubboSample";
    public static final String YAJAD_DEFAULTS_GUI = "com.yajad.jmeter.config.DubboDefaultsGui";
    public static final String ZMS_SAMPLER = "com.zto.zmsjmeter.ZMSSampler";
    /** ZMS 消息的属性（与插件属性名一致） */
    public static final String[] ZMS_FIELDS = {"zmsZKAddr", "topic", "messageKey", "messageTag", "messageBody", "messageSendMod"};

    /** 通用字段 -> yajad 属性名 */
    public static final LinkedHashMap<String, String> YAJAD = new LinkedHashMap<>();
    /** 通用字段 -> ningyu 属性名 */
    public static final LinkedHashMap<String, String> NINGYU = new LinkedHashMap<>();
    /** 可以启用/停用的配置元素（请求本身、分组等不提供启用/停用） */
    public static final java.util.Set<String> CONFIG_KINDS = new java.util.HashSet<>(
            java.util.Arrays.asList("defaults", "pre", "post", "csv", "vars"));
    /** 可以从 Dubbo Defaults 继承的连接字段 */
    public static final String[] CONN_FIELDS = {"registryProtocol", "registryAddress", "registryGroup", "rpcProtocol",
            "timeout", "retries", "version", "cluster", "group", "connections", "loadbalance"};
    /**
     * 请求的 RPCContext 附件（本工具扩展的属性，结构同“用户定义的变量”）：JMeter 能正常加载并原样保留，
     * 但 yajad/ningyu 插件不会读取它，JMeter 中需要用前置脚本 RpcContext.getContext().setAttachment(...) 实现
     */
    public static final String RPC_CONTEXT = "DubboLite.rpcContext";

    static {
        String[][] y = {{"registryProtocol", "Yajad.registryProtocol"}, {"registryAddress", "Yajad.registryAddress"},
                {"registryGroup", "Yajad.registryGroup"}, {"rpcProtocol", "Yajad.rpcProtocol"},
                {"timeout", "Yajad.serviceTimeout"}, {"retries", "Yajad.serviceRetries"},
                {"version", "Yajad.serviceVersion"}, {"cluster", "Yajad.serviceCluster"},
                {"group", "Yajad.serviceGroup"}, {"connections", "Yajad.serviceConnections"},
                {"loadbalance", "Yajad.serviceLoadBalance"}, {"interface", "Yajad.serviceInterface"},
                {"method", "Yajad.serviceMethod"}, {"params", "Yajad.serviceParameter"}};
        for (String[] kv : y) {
            YAJAD.put(kv[0], kv[1]);
        }
        String[][] n = {{"registryProtocol", "FIELD_DUBBO_REGISTRY_PROTOCOL"}, {"registryAddress", "FIELD_DUBBO_ADDRESS"},
                {"registryGroup", "FIELD_DUBBO_REGISTRY_GROUP"}, {"rpcProtocol", "FIELD_DUBBO_RPC_PROTOCOL"},
                {"timeout", "FIELD_DUBBO_TIMEOUT"}, {"retries", "FIELD_DUBBO_RETRIES"},
                {"version", "FIELD_DUBBO_VERSION"}, {"cluster", "FIELD_DUBBO_CLUSTER"},
                {"group", "FIELD_DUBBO_GROUP"}, {"connections", "FIELD_DUBBO_CONNECTIONS"},
                {"loadbalance", "FIELD_DUBBO_LOADBALANCE"}, {"interface", "FIELD_DUBBO_INTERFACE"},
                {"method", "FIELD_DUBBO_METHOD"}};
        for (String[] kv : n) {
            NINGYU.put(kv[0], kv[1]);
        }
    }

    private static final AtomicInteger DOC_SEQ = new AtomicInteger();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    public final String id = "d" + DOC_SEQ.incrementAndGet();
    public Path path;
    /** 未保存过的文档（拖入/新建）显示的名字 */
    public String displayName;
    public boolean dirty;

    private final Xml.Document xml;
    public final TNode root;
    private final Map<Integer, TNode> byId = new LinkedHashMap<>();
    private int seq;

    public static final class TNode {
        public int id;
        public Xml.Node el;
        /** 紧随其后的 hashTree */
        public Xml.Node tree;
        public TNode parent;
        public final List<TNode> children = new ArrayList<>();
        public String kind;
        public String flavor;

        public String name() {
            String n = el.attr("testname");
            return n == null ? el.name : n;
        }

        public boolean enabled() {
            return !"false".equals(el.attr("enabled"));
        }

        public String testclass() {
            String t = el.attr("testclass");
            return t == null ? el.name : t;
        }

        public String prop(String name) {
            Xml.Node p = propNode(el, name);
            return p == null ? "" : p.text();
        }
    }

    // ------------------------------------------------------------------ load

    public JmxDoc(String content) {
        this.xml = Xml.parse(content);
        Xml.Node top = xml.root.child("hashTree");
        if (!"jmeterTestPlan".equals(xml.root.name) || top == null) {
            throw new IllegalArgumentException("不是 JMeter 测试计划文件(jmx)");
        }
        List<TNode> roots = buildChildren(top, null);
        if (roots.isEmpty() || !"plan".equals(roots.get(0).kind)) {
            throw new IllegalArgumentException("JMX 中没有 TestPlan");
        }
        this.root = roots.get(0);
    }

    public static JmxDoc create() {
        return Templates.newPlan();
    }

    public static JmxDoc load(Path path) throws IOException {
        JmxDoc d = new JmxDoc(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        d.path = path.toAbsolutePath();
        return d;
    }

    private List<TNode> buildChildren(Xml.Node hashTree, TNode parent) {
        List<TNode> list = new ArrayList<>();
        List<Xml.Node> cs = hashTree.children;
        for (int i = 0; i < cs.size(); i++) {
            Xml.Node el = cs.get(i);
            if ("hashTree".equals(el.name)) {
                continue;
            }
            TNode t = new TNode();
            t.id = ++seq;
            t.el = el;
            t.parent = parent;
            if (i + 1 < cs.size() && "hashTree".equals(cs.get(i + 1).name)) {
                t.tree = cs.get(i + 1);
            } else {
                // 极少数手写文件缺少 hashTree，补一个
                t.tree = Xml.parseFragment("<hashTree/>");
                hashTree.insert(i + 1, t.tree);
            }
            classify(t);
            byId.put(t.id, t);
            t.children.addAll(buildChildren(t.tree, t));
            list.add(t);
        }
        return list;
    }

    private static void classify(TNode t) {
        String tc = t.testclass();
        String gui = t.el.attr("guiclass");
        if ("TestPlan".equals(tc)) {
            t.kind = "plan";
        } else if (tc.endsWith("ThreadGroup")) {
            t.kind = "group";
        } else if (YAJAD_SAMPLER.equals(tc)) {
            t.kind = "dubbo";
            t.flavor = "yajad";
        } else if (NINGYU_SAMPLER.equals(tc)) {
            t.kind = "dubbo";
            t.flavor = "ningyu";
        } else if (ZMS_SAMPLER.equals(tc)) {
            t.kind = "zms";
        } else if ("JavaSampler".equals(tc) && Dandelion.matches(t)) {
            t.kind = "dubbo";
            t.flavor = "dandelion";
        } else if ("ConfigTestElement".equals(tc) && YAJAD_DEFAULTS_GUI.equals(gui)) {
            t.kind = "defaults";
        } else if ("BeanShellPreProcessor".equals(tc) || "JSR223PreProcessor".equals(tc)) {
            t.kind = "pre";
        } else if ("BeanShellPostProcessor".equals(tc) || "JSR223PostProcessor".equals(tc)) {
            t.kind = "post";
        } else if ("CSVDataSet".equals(tc)) {
            t.kind = "csv";
        } else if ("Arguments".equals(tc)) {
            t.kind = "vars";
        } else if (tc.endsWith("Controller")) {
            t.kind = "controller";
        } else if ("ResultCollector".equals(tc) || tc.endsWith("Visualizer") || "BackendListener".equals(tc)) {
            t.kind = "listener";
        } else if (tc.endsWith("Sampler") || tc.endsWith("SamplerProxy") || tc.endsWith("Sample")) {
            // 其他类型的请求（HTTP、ZMS、Java 等）：在树中显示，但不支持发送
            t.kind = "sampler";
        } else {
            t.kind = "other";
        }
    }

    // ------------------------------------------------------------------ query

    public TNode node(int id) {
        TNode t = byId.get(id);
        if (t == null) {
            throw new IllegalArgumentException("节点不存在: " + id);
        }
        return t;
    }

    public String fileName() {
        if (path != null) {
            return path.getFileName().toString();
        }
        return displayName == null ? "未命名.jmx" : displayName;
    }

    static Xml.Node propNode(Xml.Node el, String name) {
        for (Xml.Node c : el.children) {
            if (name.equals(c.attr("name")) && c.name.endsWith("Prop")) {
                return c;
            }
        }
        return null;
    }

    /** 通用 Dubbo 字段（sampler / defaults） */
    public static Map<String, String> dubboFields(TNode t) {
        if ("dandelion".equals(t.flavor)) {
            return Dandelion.fields(t);
        }
        Map<String, String> m = new LinkedHashMap<>();
        if ("ningyu".equals(t.flavor)) {
            for (Map.Entry<String, String> e : NINGYU.entrySet()) {
                m.put(e.getKey(), t.prop(e.getValue()));
            }
            m.put("params", ningyuArgsToText(t));
        } else {
            for (Map.Entry<String, String> e : YAJAD.entrySet()) {
                if ("defaults".equals(t.kind) && isRequestField(e.getKey())) {
                    continue;
                }
                m.put(e.getKey(), t.prop(e.getValue()));
            }
        }
        return m;
    }

    private static boolean isRequestField(String k) {
        return "interface".equals(k) || "method".equals(k) || "params".equals(k);
    }

    static String ningyuArgsToText(TNode t) {
        int size;
        try {
            size = Integer.parseInt(t.prop("FIELD_DUBBO_METHOD_ARGS_SIZE").trim());
        } catch (NumberFormatException e) {
            size = 0;
        }
        JsonArray arr = new JsonArray();
        for (int i = 1; i <= size; i++) {
            String type = t.prop("FIELD_DUBBO_METHOD_ARGS_PARAM_TYPE" + i);
            String value = t.prop("FIELD_DUBBO_METHOD_ARGS_PARAM_VALUE" + i);
            JsonElement v;
            try {
                v = JsonParser.parseString(value);
                if (v.isJsonNull() && !"null".equals(value.trim())) {
                    v = new com.google.gson.JsonPrimitive(value);
                }
            } catch (Exception e) {
                v = new com.google.gson.JsonPrimitive(value);
            }
            JsonObject o = new JsonObject();
            o.add(type, v);
            arr.add(o);
        }
        return size == 0 ? "[]" : PRETTY.toJson(arr);
    }

    private static void ningyuTextToArgs(TNode t, String text) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text == null || text.trim().isEmpty() ? "[]" : text);
        } catch (Exception e) {
            throw new IllegalArgumentException("该请求来自 ningyu Dubbo 插件，参数需为合法 JSON 数组: [{\"类型\": 值}]");
        }
        if (!parsed.isJsonArray()) {
            throw new IllegalArgumentException("参数必须是 JSON 数组: [{\"类型\": 值}]");
        }
        JsonArray arr = parsed.getAsJsonArray();
        int old;
        try {
            old = Integer.parseInt(t.prop("FIELD_DUBBO_METHOD_ARGS_SIZE").trim());
        } catch (NumberFormatException e) {
            old = 0;
        }
        for (int i = 1; i <= Math.max(old, arr.size()); i++) {
            if (i <= arr.size()) {
                JsonElement item = arr.get(i - 1);
                if (!item.isJsonObject() || item.getAsJsonObject().size() != 1) {
                    throw new IllegalArgumentException("第 " + i + " 个参数应为 {\"类型\": 值}");
                }
                Map.Entry<String, JsonElement> e = item.getAsJsonObject().entrySet().iterator().next();
                JsonElement v = e.getValue();
                String value = v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : v.toString();
                setProp(t.el, "FIELD_DUBBO_METHOD_ARGS_PARAM_TYPE" + i, e.getKey(), "stringProp");
                setProp(t.el, "FIELD_DUBBO_METHOD_ARGS_PARAM_VALUE" + i, value, "stringProp");
            } else {
                removeProp(t.el, "FIELD_DUBBO_METHOD_ARGS_PARAM_TYPE" + i);
                removeProp(t.el, "FIELD_DUBBO_METHOD_ARGS_PARAM_VALUE" + i);
            }
        }
        setProp(t.el, "FIELD_DUBBO_METHOD_ARGS_SIZE", String.valueOf(arr.size()), "intProp");
    }

    public static List<String[]> variables(TNode t) {
        return arguments(argumentsCollection(t, false));
    }

    /** 请求上配置的 RPCContext 附件 [名称, 值] */
    public static List<String[]> rpcContext(TNode t) {
        Xml.Node holder = propNode(t.el, RPC_CONTEXT);
        return arguments(holder == null ? null : propNode(holder, "Arguments.arguments"));
    }

    private static void setRpcContext(TNode t, JsonArray rows) {
        Xml.Node holder = propNode(t.el, RPC_CONTEXT);
        boolean empty = true;
        for (JsonElement r : rows) {
            JsonObject o = r.getAsJsonObject();
            if (o.has("name") && !o.get("name").getAsString().trim().isEmpty()) {
                empty = false;
            }
        }
        if (empty) {
            // 没有附件时不留空属性，未使用此功能的文件保存后保持原样
            if (holder != null) {
                t.el.remove(holder);
            }
            return;
        }
        if (holder == null) {
            holder = Xml.parseFragment("<elementProp name=\"" + RPC_CONTEXT + "\" elementType=\"Arguments\" "
                    + "guiclass=\"ArgumentsPanel\" testclass=\"Arguments\" testname=\"RPCContext\">\n"
                    + "  <collectionProp name=\"Arguments.arguments\"/>\n</elementProp>");
            t.el.append(holder);
        }
        setArguments(propNode(holder, "Arguments.arguments"), rows);
    }

    private static List<String[]> arguments(Xml.Node coll) {
        List<String[]> list = new ArrayList<>();
        if (coll == null) {
            return list;
        }
        for (Xml.Node arg : coll.children) {
            Xml.Node n = propNode(arg, "Argument.name");
            Xml.Node v = propNode(arg, "Argument.value");
            list.add(new String[]{n == null ? arg.attr("name") : n.text(), v == null ? "" : v.text()});
        }
        return list;
    }

    private static Xml.Node argumentsCollection(TNode t, boolean create) {
        Xml.Node holder = t.el;
        if ("plan".equals(t.kind)) {
            holder = propNode(t.el, "TestPlan.user_defined_variables");
            if (holder == null) {
                if (!create) {
                    return null;
                }
                holder = Xml.parseFragment("<elementProp name=\"TestPlan.user_defined_variables\" elementType=\"Arguments\" "
                        + "guiclass=\"ArgumentsPanel\" testclass=\"Arguments\" testname=\"用户定义的变量\">\n"
                        + "  <collectionProp name=\"Arguments.arguments\"/>\n</elementProp>");
                t.el.insert(0, holder);
            }
        }
        Xml.Node coll = propNode(holder, "Arguments.arguments");
        if (coll == null && create) {
            coll = Xml.parseFragment("<collectionProp name=\"Arguments.arguments\"/>");
            holder.append(coll);
        }
        return coll;
    }

    private static void setVariables(TNode t, JsonArray rows) {
        setArguments(argumentsCollection(t, true), rows);
    }

    private static void setArguments(Xml.Node coll, JsonArray rows) {
        List<Xml.Node> old = new ArrayList<>(coll.children);
        int i = 0;
        for (JsonElement r : rows) {
            JsonObject o = r.getAsJsonObject();
            String name = o.has("name") ? o.get("name").getAsString() : "";
            String value = o.has("value") ? o.get("value").getAsString() : "";
            if (name.trim().isEmpty()) {
                continue;
            }
            if (i < old.size()) {
                Xml.Node arg = old.get(i);
                arg.setAttr("name", name);
                setProp(arg, "Argument.name", name, "stringProp");
                setProp(arg, "Argument.value", value, "stringProp");
            } else {
                coll.append(Xml.parseFragment("<elementProp name=\"" + Xml.escape(name) + "\" elementType=\"Argument\">\n"
                        + "  <stringProp name=\"Argument.name\">" + Xml.escape(name) + "</stringProp>\n"
                        + "  <stringProp name=\"Argument.value\">" + Xml.escape(value) + "</stringProp>\n"
                        + "  <stringProp name=\"Argument.metadata\">=</stringProp>\n"
                        + "</elementProp>"));
            }
            i++;
        }
        for (int j = i; j < old.size(); j++) {
            coll.remove(old.get(j));
        }
        if (coll.children.isEmpty()) {
            coll.selfClosing = true;
            coll.tail = "";
        }
    }

    static void setProp(Xml.Node el, String name, String value, String tag) {
        Xml.Node p = propNode(el, name);
        if (p == null) {
            p = Xml.parseFragment("<" + tag + " name=\"" + Xml.escape(name) + "\"></" + tag + ">");
            p.setText(value);
            el.append(p);
        } else {
            p.setText(value);
        }
    }

    private static void removeProp(Xml.Node el, String name) {
        Xml.Node p = propNode(el, name);
        if (p != null) {
            el.remove(p);
        }
    }

    // ------------------------------------------------------------------ json for UI

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", fileName());
        o.addProperty("path", path == null ? null : path.toString());
        o.addProperty("dirty", dirty);
        o.add("root", nodeJson(root));
        return o;
    }

    public JsonObject nodeJson(TNode t) {
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id);
        o.addProperty("kind", t.kind);
        if (t.flavor != null) {
            o.addProperty("flavor", t.flavor);
        }
        o.addProperty("name", t.name());
        o.addProperty("enabled", t.enabled());
        o.addProperty("testclass", t.testclass());
        JsonObject props = new JsonObject();
        switch (t.kind) {
            case "dubbo":
            case "defaults":
                for (Map.Entry<String, String> e : dubboFields(t).entrySet()) {
                    props.addProperty(e.getKey(), e.getValue());
                }
                if ("dubbo".equals(t.kind)) {
                    props.add("rpcContext", rowsJson(rpcContext(t)));
                }
                break;
            case "pre":
            case "post":
                props.addProperty("script", t.prop("script"));
                props.addProperty("filename", t.prop("filename"));
                props.addProperty("parameters", t.prop("parameters"));
                props.addProperty("language", t.testclass().startsWith("JSR223")
                        ? (t.prop("scriptLanguage").isEmpty() ? "groovy" : t.prop("scriptLanguage")) : "beanshell");
                break;
            case "csv":
                for (String k : new String[]{"filename", "fileEncoding", "variableNames", "delimiter",
                        "ignoreFirstLine", "quotedData", "recycle"}) {
                    props.addProperty(k, t.prop(k));
                }
                break;
            case "plan":
            case "vars":
                props.add("variables", rowsJson(variables(t)));
                if ("plan".equals(t.kind)) {
                    props.addProperty("comments", t.prop("TestPlan.comments"));
                }
                break;
            case "zms":
                for (String k : ZMS_FIELDS) {
                    props.addProperty(k, t.prop(k));
                }
                break;
            case "sampler": {
                JsonArray fs = new JsonArray();
                for (Xml.Node c : t.el.children) {
                    String n = c.attr("name");
                    if (n != null && c.name.endsWith("Prop") && c.children.isEmpty() && !c.text().isEmpty()) {
                        JsonArray kv = new JsonArray();
                        kv.add(n);
                        kv.add(c.text());
                        fs.add(kv);
                    }
                }
                Xml.Node args = propNode(t.el, "arguments");
                Xml.Node coll = args == null ? null : propNode(args, "Arguments.arguments");
                if (coll != null) {
                    for (Xml.Node a : coll.children) {
                        Xml.Node n = propNode(a, "Argument.name");
                        Xml.Node v = propNode(a, "Argument.value");
                        JsonArray kv = new JsonArray();
                        kv.add(n == null ? a.attr("name") : n.text());
                        kv.add(v == null ? "" : v.text());
                        fs.add(kv);
                    }
                }
                props.add("fields", fs);
                break;
            }
            case "other":
                if ("ConstantTimer".equals(t.testclass())) {
                    props.addProperty("delay", t.prop("ConstantTimer.delay"));
                }
                break;
            default:
        }
        o.add("props", props);
        JsonArray cs = new JsonArray();
        for (TNode c : t.children) {
            cs.add(nodeJson(c));
        }
        o.add("children", cs);
        return o;
    }

    private static JsonArray rowsJson(List<String[]> rows) {
        JsonArray arr = new JsonArray();
        for (String[] kv : rows) {
            JsonObject v = new JsonObject();
            v.addProperty("name", kv[0]);
            v.addProperty("value", kv[1]);
            arr.add(v);
        }
        return arr;
    }

    // ------------------------------------------------------------------ mutate

    public void update(int nodeId, JsonObject patch) {
        TNode t = node(nodeId);
        if (patch.has("name")) {
            t.el.setAttr("testname", patch.get("name").getAsString());
        }
        if (patch.has("enabled")) {
            if (!CONFIG_KINDS.contains(t.kind)) {
                throw new IllegalArgumentException("只有配置元素可以启用/停用");
            }
            t.el.setAttr("enabled", String.valueOf(patch.get("enabled").getAsBoolean()));
        }
        if (patch.has("props")) {
            JsonObject props = patch.getAsJsonObject("props");
            for (Map.Entry<String, JsonElement> e : props.entrySet()) {
                String key = e.getKey();
                switch (t.kind) {
                    case "dubbo":
                    case "defaults":
                        if ("rpcContext".equals(key)) {
                            if ("dubbo".equals(t.kind)) {
                                setRpcContext(t, e.getValue().getAsJsonArray());
                            }
                            break;
                        }
                        String value = e.getValue().getAsString();
                        if ("dandelion".equals(t.flavor)) {
                            Dandelion.update(t, key, value);
                        } else if ("ningyu".equals(t.flavor)) {
                            if ("params".equals(key)) {
                                ningyuTextToArgs(t, value);
                            } else if (NINGYU.containsKey(key)) {
                                setProp(t.el, NINGYU.get(key), value, "stringProp");
                            }
                        } else if (YAJAD.containsKey(key)) {
                            setProp(t.el, YAJAD.get(key), value, "stringProp");
                        }
                        break;
                    case "zms":
                        for (String k : ZMS_FIELDS) {
                            if (k.equals(key)) {
                                setProp(t.el, key, e.getValue().getAsString(), "stringProp");
                            }
                        }
                        break;
                    case "pre":
                    case "post":
                        if ("script".equals(key) || "filename".equals(key) || "parameters".equals(key)) {
                            setProp(t.el, key, e.getValue().getAsString(), "stringProp");
                        } else if ("language".equals(key) && t.testclass().startsWith("JSR223")) {
                            setProp(t.el, "scriptLanguage", e.getValue().getAsString(), "stringProp");
                        }
                        break;
                    case "csv":
                        if (key.equals("ignoreFirstLine") || key.equals("quotedData") || key.equals("recycle")) {
                            setProp(t.el, key, e.getValue().getAsString(), "boolProp");
                        } else {
                            setProp(t.el, key, e.getValue().getAsString(), "stringProp");
                        }
                        break;
                    case "plan":
                    case "vars":
                        if ("variables".equals(key)) {
                            setVariables(t, e.getValue().getAsJsonArray());
                        } else if ("comments".equals(key) && "plan".equals(t.kind)) {
                            setProp(t.el, "TestPlan.comments", e.getValue().getAsString(), "stringProp");
                        }
                        break;
                    default:
                        if ("delay".equals(key) && "ConstantTimer".equals(t.testclass())) {
                            setProp(t.el, "ConstantTimer.delay", e.getValue().getAsString(), "stringProp");
                        }
                }
            }
        }
        dirty = true;
    }

    /** 新建元素并插入到 parent 的第 index 个子节点位置（index<0 表示末尾） */
    public TNode add(int parentId, int index, String kind) {
        TNode parent = node(parentId);
        Xml.Node el = Xml.parseFragment(Templates.element(kind));
        return insert(parent, index, el, Xml.parseFragment("<hashTree/>"));
    }

    public TNode duplicate(int nodeId) {
        TNode src = node(nodeId);
        if (src.parent == null) {
            throw new IllegalArgumentException("测试计划不能复制");
        }
        Xml.Node el = src.el.deepCopy();
        el.setAttr("testname", src.name() + " 副本");
        int idx = src.parent.children.indexOf(src) + 1;
        return insert(src.parent, idx, el, src.tree.deepCopy());
    }

    public void delete(int nodeId) {
        TNode t = node(nodeId);
        if (t.parent == null) {
            throw new IllegalArgumentException("测试计划不能删除");
        }
        t.parent.tree.remove(t.el);
        t.parent.tree.remove(t.tree);
        t.parent.children.remove(t);
        forget(t);
        dirty = true;
    }

    /** 移动节点到 newParent 的 index 位置 */
    public void move(int nodeId, int newParentId, int index) {
        TNode t = node(nodeId);
        TNode np = node(newParentId);
        if (t.parent == null) {
            throw new IllegalArgumentException("测试计划不能移动");
        }
        for (TNode p = np; p != null; p = p.parent) {
            if (p == t) {
                throw new IllegalArgumentException("不能移动到自身内部");
            }
        }
        int oldIdx = t.parent.children.indexOf(t);
        if (t.parent == np && index > oldIdx) {
            index--;
        }
        t.parent.tree.remove(t.el);
        t.parent.tree.remove(t.tree);
        t.parent.children.remove(t);
        placeXml(np, index, t.el, t.tree);
        np.children.add(index < 0 || index > np.children.size() ? np.children.size() : index, t);
        t.parent = np;
        dirty = true;
    }

    private TNode insert(TNode parent, int index, Xml.Node el, Xml.Node tree) {
        if (index < 0 || index > parent.children.size()) {
            index = parent.children.size();
        }
        placeXml(parent, index, el, tree);
        TNode t = new TNode();
        t.id = ++seq;
        t.el = el;
        t.tree = tree;
        t.parent = parent;
        classify(t);
        byId.put(t.id, t);
        t.children.addAll(buildChildren(tree, t));
        parent.children.add(index, t);
        dirty = true;
        return t;
    }

    private static void placeXml(TNode parent, int index, Xml.Node el, Xml.Node tree) {
        int xmlIdx;
        if (index < 0 || index >= parent.children.size()) {
            xmlIdx = parent.tree.children.size();
        } else {
            xmlIdx = parent.tree.children.indexOf(parent.children.get(index).el);
        }
        parent.tree.insert(xmlIdx, el);
        parent.tree.insert(xmlIdx + 1, tree);
    }

    private void forget(TNode t) {
        byId.remove(t.id);
        for (TNode c : t.children) {
            forget(c);
        }
    }

    public String serialize() {
        return Xml.write(xml);
    }

    public void save(Path target) throws IOException {
        Path p = target.toAbsolutePath();
        Path tmp = p.resolveSibling("." + p.getFileName() + ".tmp");
        Files.write(tmp, serialize().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        path = p;
        dirty = false;
    }
}
