package dubbolite;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dubbolite.jmx.JmxDoc;
import dubbolite.run.DubboClient;
import dubbolite.run.ParamCheck;
import dubbolite.run.RegistrySniffer;
import dubbolite.run.Runner;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** 前端调用的全部接口。所有方法入参/出参都是 JSON。 */
final class Api {

    private final Settings settings;
    private final DubboClient client = new DubboClient();
    private final RegistrySniffer sniffer = new RegistrySniffer();
    private final Properties props = new Properties();
    private final Runner runner;
    private final Map<String, JmxDoc> docs = new ConcurrentHashMap<>();
    private final Map<String, Runner.Session> sessions = new ConcurrentHashMap<>();

    Api(Settings settings) {
        this.settings = settings;
        this.runner = new Runner(client, props, settings::scriptLoader);
    }

    JsonElement handle(String action, JsonObject in) throws Exception {
        switch (action) {
            case "state": return state();
            case "open": return open(Paths.get(str(in, "path")));
            case "openContent": return openContent(str(in, "name"), str(in, "content"));
            case "new": return register(JmxDoc.create(), null);
            case "doc": return doc(in).toJson();
            case "close": return close(in);
            case "update": {
                JmxDoc d = doc(in);
                synchronized (d) {
                    d.update(in.get("nodeId").getAsInt(), in.getAsJsonObject("patch"));
                }
                JsonObject o = new JsonObject();
                o.addProperty("dirty", d.dirty);
                return o;
            }
            case "add": {
                JmxDoc d = doc(in);
                synchronized (d) {
                    JmxDoc.TNode t = d.add(in.get("parentId").getAsInt(), in.has("index") ? in.get("index").getAsInt() : -1, str(in, "kind"));
                    return withNew(d, t.id);
                }
            }
            case "duplicate": {
                JmxDoc d = doc(in);
                synchronized (d) {
                    return withNew(d, d.duplicate(in.get("nodeId").getAsInt()).id);
                }
            }
            case "delete": {
                JmxDoc d = doc(in);
                synchronized (d) {
                    d.delete(in.get("nodeId").getAsInt());
                    return d.toJson();
                }
            }
            case "move": {
                JmxDoc d = doc(in);
                synchronized (d) {
                    d.move(in.get("nodeId").getAsInt(), in.get("parentId").getAsInt(), in.get("index").getAsInt());
                    return d.toJson();
                }
            }
            case "save": return save(in);
            case "pick": return pick(str(in, "mode"), str(in, "name"), str(in, "dir"));
            case "send": {
                JmxDoc d = doc(in);
                return runner.send(d, in.get("nodeId").getAsInt(), session(d));
            }
            case "sniffServices":
                return sniffer.services(str(in, "protocol"), str(in, "address"), str(in, "group"), bool(in, "refresh"));
            case "sniffProviders":
                return sniffer.providers(str(in, "protocol"), str(in, "address"), str(in, "group"), str(in, "iface"), bool(in, "refresh"));
            case "checkParams": {
                return ParamCheck.check(str(in, "text"), bool(in, "dandelion"));
            }
            case "cancel": {
                JsonObject o = new JsonObject();
                o.addProperty("cancelled", runner.cancel(doc(in), in.get("nodeId").getAsInt()));
                return o;
            }
            case "vars": {
                JsonObject o = new JsonObject();
                o.add("vars", Json.GSON.toJsonTree(session(doc(in)).vars.snapshot()));
                return o;
            }
            case "clearVars": {
                session(doc(in)).clear();
                return new JsonObject();
            }
            case "reset": {
                JsonObject o = new JsonObject();
                o.addProperty("count", client.reset());
                sniffer.reset();
                return o;
            }
            case "settings": {
                if (in.has("extPaths")) {
                    List<String> l = new ArrayList<>();
                    for (JsonElement e : in.getAsJsonArray("extPaths")) {
                        if (!e.getAsString().trim().isEmpty()) {
                            l.add(e.getAsString().trim());
                        }
                    }
                    settings.setExtPaths(l);
                }
                return state();
            }
            case "removeRecent":
                settings.removeRecent(str(in, "path"));
                return state();
            case "reveal": {
                String p = str(in, "path");
                if (isMac()) {
                    new ProcessBuilder("open", "-R", p).start();
                } else if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    new ProcessBuilder("explorer.exe", "/select," + p).start();
                }
                return new JsonObject();
            }
            default:
                throw new IllegalArgumentException("未知操作: " + action);
        }
    }

    private JsonObject state() {
        JsonObject o = new JsonObject();
        o.add("recent", Json.GSON.toJsonTree(settings.recent()));
        o.add("extPaths", Json.GSON.toJsonTree(settings.extPaths()));
        o.addProperty("home", Settings.HOME.toString());
        o.addProperty("version", Main.VERSION);
        o.addProperty("java", System.getProperty("java.version"));
        JsonArray open = new JsonArray();
        for (JmxDoc d : docs.values()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", d.id);
            x.addProperty("name", d.fileName());
            x.addProperty("path", d.path == null ? null : d.path.toString());
            x.addProperty("dirty", d.dirty);
            open.add(x);
        }
        o.add("docs", open);
        return o;
    }

    private JsonObject open(Path path) throws Exception {
        Path abs = path.toAbsolutePath().normalize();
        for (JmxDoc d : docs.values()) {
            if (abs.equals(d.path)) {
                settings.touchRecent(abs);
                return d.toJson();
            }
        }
        if (!Files.isRegularFile(abs)) {
            settings.removeRecent(abs.toString());
            throw new IllegalArgumentException("文件不存在: " + abs);
        }
        JmxDoc d = JmxDoc.load(abs);
        settings.touchRecent(abs);
        return register(d, null);
    }

    private JsonObject openContent(String name, String content) {
        JmxDoc d = new JmxDoc(content);
        return register(d, name);
    }

    private JsonObject register(JmxDoc d, String displayName) {
        d.displayName = displayName;
        docs.put(d.id, d);
        return d.toJson();
    }

    private JsonObject close(JsonObject in) {
        JmxDoc d = docs.remove(str(in, "docId"));
        if (d != null) {
            sessions.remove(d.id);
        }
        return new JsonObject();
    }

    private JsonObject save(JsonObject in) throws Exception {
        JmxDoc d = doc(in);
        String p = str(in, "path");
        Path target = p == null || p.isEmpty() ? d.path : Paths.get(p);
        if (target == null) {
            throw new IllegalArgumentException("请选择保存位置");
        }
        if (!target.getFileName().toString().toLowerCase().endsWith(".jmx")) {
            target = target.resolveSibling(target.getFileName() + ".jmx");
        }
        synchronized (d) {
            d.save(target);
        }
        settings.touchRecent(d.path);
        return d.toJson();
    }

    private JsonObject withNew(JmxDoc d, int id) {
        JsonObject o = d.toJson();
        o.addProperty("newId", id);
        return o;
    }

    private JmxDoc doc(JsonObject in) {
        JmxDoc d = docs.get(str(in, "docId"));
        if (d == null) {
            throw new IllegalArgumentException("文档已关闭，请重新打开");
        }
        return d;
    }

    private Runner.Session session(JmxDoc d) {
        return sessions.computeIfAbsent(d.id, k -> new Runner.Session());
    }

    private static boolean bool(JsonObject in, String k) {
        JsonElement e = in.get(k);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    private static String str(JsonObject in, String k) {
        JsonElement e = in.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    // ------------------------------------------------------------------ 原生文件对话框（浏览器模式下使用）

    private JsonObject pick(String mode, String name, String dir) throws Exception {
        String path = isMac() ? pickMac(mode, name, dir) : pickSwing(mode, name, dir);
        JsonObject o = new JsonObject();
        o.add("path", path == null ? null : new JsonPrimitive(path));
        return o;
    }

    private static String osaString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String pickMac(String mode, String name, String dir) throws Exception {
        String loc = dir != null && new File(dir).isDirectory() ? " default location (POSIX file " + osaString(dir) + ")" : "";
        String script = "save".equals(mode)
                ? "POSIX path of (choose file name with prompt \"保存测试计划\" default name "
                + osaString(name == null || name.isEmpty() ? "未命名.jmx" : name) + loc + ")"
                : "POSIX path of (choose file with prompt \"打开 JMeter 测试计划 (.jmx)\"" + loc + ")";
        Process p = new ProcessBuilder("osascript", "-e", script).redirectErrorStream(false).start();
        String out = read(p.getInputStream()).trim();
        int code = p.waitFor();
        return code == 0 && !out.isEmpty() ? out : null;
    }

    private static String pickSwing(String mode, String name, String dir) throws Exception {
        final String[] res = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignore) {
                // 使用默认外观
            }
            // 置顶的隐形父窗口，避免对话框被浏览器窗口挡住
            javax.swing.JFrame owner = new javax.swing.JFrame();
            owner.setUndecorated(true);
            owner.setAlwaysOnTop(true);
            owner.setLocationRelativeTo(null);
            owner.setVisible(true);
            try {
                JFileChooser fc = new JFileChooser(dir);
                fc.setFileFilter(new FileNameExtensionFilter("JMeter 测试计划 (*.jmx)", "jmx"));
                if (name != null) {
                    fc.setSelectedFile(new File(dir == null ? "." : dir, name));
                }
                int r = "save".equals(mode) ? fc.showSaveDialog(owner) : fc.showOpenDialog(owner);
                if (r == JFileChooser.APPROVE_OPTION) {
                    res[0] = fc.getSelectedFile().getAbsolutePath();
                }
            } finally {
                owner.dispose();
            }
        });
        return res[0];
    }

    static String read(InputStream in) throws java.io.IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    static String readSafe(InputStream in) throws java.io.IOException {
        return read(in);
    }

    static byte[] readBytes(InputStream in) throws java.io.IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        }
    }
}
