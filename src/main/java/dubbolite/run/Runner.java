package dubbolite.run;

import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.service.GenericException;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yajad.jmeter.dto.DubboParamDto;
import com.yajad.jmeter.parse.YamlParamParser;
import com.yajad.jmeter.util.JsonUtils;
import dubbolite.jmx.JmxDoc;
import dubbolite.jmx.JmxDoc.TNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次 "发送" 的完整流程，语义对齐 JMeter 单线程执行一个 sampler：
 * 用户变量 → CSV → 合并 Dubbo Defaults → 前置处理器 → 变量/函数替换 → 解析参数(yajad) → 泛化调用 → 后置处理器
 */
public final class Runner {

    /** 每个打开的文档一个会话：变量在多次发送之间保留（便于后置处理器提取的值被后续请求使用） */
    public static final class Session {
        public final Vars vars = new Vars();
        final Csv csv = new Csv();
        ScriptApi.Result last = new ScriptApi.Result();
        String udvSignature;

        public synchronized void clear() {
            vars.clear();
            csv.reset();
            udvSignature = null;
            last = new ScriptApi.Result();
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls()
            .setDateFormat("yyyy-MM-dd HH:mm:ss").create();

    private final DubboClient client;
    private final Properties props;
    private final Supplier<ClassLoader> loader;

    public Runner(DubboClient client, Properties props, Supplier<ClassLoader> loader) {
        this.client = client;
        this.props = props;
        this.loader = loader;
    }

    public JsonObject send(JmxDoc doc, int nodeId, Session session) {
        synchronized (session) {
            return doSend(doc, nodeId, session);
        }
    }

    private JsonObject doSend(JmxDoc doc, int nodeId, Session session) {
        TNode s = doc.node(nodeId);
        if (!"dubbo".equals(s.kind) && !"zms".equals(s.kind)) {
            throw new IllegalArgumentException("只能发送 Dubbo / ZMS 请求");
        }
        ScriptLog log = new ScriptLog();
        JsonObject out = new JsonObject();
        JsonObject request = new JsonObject();
        out.add("request", request);

        // 作用域：从测试计划到父节点的每一层的直接子元素 + sampler 自身的子元素
        List<TNode> chain = new ArrayList<>();
        for (TNode p = s.parent; p != null; p = p.parent) {
            chain.add(0, p);
        }
        List<TNode> scope = new ArrayList<>();
        for (TNode p : chain) {
            for (TNode c : p.children) {
                if (c != s && c.enabled() && !"dubbo".equals(c.kind) && !"zms".equals(c.kind) && !"sampler".equals(c.kind)
                        && !"group".equals(c.kind) && !"controller".equals(c.kind)) {
                    scope.add(c);
                }
            }
        }
        for (TNode c : s.children) {
            if (c.enabled()) {
                scope.add(c);
            }
        }
        String groupName = "";
        for (TNode p : chain) {
            if ("group".equals(p.kind)) {
                groupName = p.name();
            }
        }
        Path baseDir = doc.path == null ? null : doc.path.getParent();
        Vars vars = session.vars;
        Functions fn = new Functions(vars, props, log, s.name(), groupName,
                (lang, code) -> {
                    Object r = Scripts.run(lang, code, null, "", bindings(vars, session.last, null, log), loader.get(), log);
                    return r == null ? null : String.valueOf(r);
                });

        // 1. 用户定义的变量（定义变化时重新应用，避免覆盖脚本运行中产生的变量）
        List<String[]> udvs = new ArrayList<>();
        udvs.addAll(JmxDoc.variables(chain.get(0)));
        for (TNode c : scope) {
            if ("vars".equals(c.kind)) {
                udvs.addAll(JmxDoc.variables(c));
            }
        }
        StringBuilder sig = new StringBuilder();
        for (String[] kv : udvs) {
            sig.append(kv[0]).append('=').append(kv[1]).append('\n');
        }
        if (!sig.toString().equals(session.udvSignature)) {
            for (String[] kv : udvs) {
                vars.put(kv[0], fn.apply(kv[1]));
            }
            session.udvSignature = sig.toString();
        }

        // 2. CSV 数据文件
        for (TNode c : scope) {
            if (!"csv".equals(c.kind)) {
                continue;
            }
            String f = fn.apply(c.prop("filename"));
            log.setSource(c.name());
            try {
                Path p = resolve(baseDir, f);
                if (p == null || !p.toFile().isFile()) {
                    log.system("WARN", "CSV 文件不存在，已跳过: " + f);
                    continue;
                }
                String info = session.csv.next(p, c.prop("fileEncoding"), c.prop("variableNames"), c.prop("delimiter"),
                        Boolean.parseBoolean(c.prop("ignoreFirstLine")), Boolean.parseBoolean(c.prop("quotedData")),
                        !"false".equals(c.prop("recycle")), vars);
                log.system("INFO", "CSV " + p.getFileName() + " " + info);
            } catch (Exception e) {
                log.system("ERROR", "读取 CSV 失败 " + f + " : " + e);
            }
        }

        if ("zms".equals(s.kind)) {
            return sendZms(s, scope, baseDir, fn, vars, session, log, out, request);
        }

        // 3. 字段 + Dubbo Defaults（最近的配置优先，仅填充空字段）
        Map<String, String> fields = JmxDoc.dubboFields(s);
        JsonObject inherited = new JsonObject();
        List<TNode> defaults = new ArrayList<>();
        for (int i = scope.size() - 1; i >= 0; i--) {
            if ("defaults".equals(scope.get(i).kind)) {
                defaults.add(scope.get(i));
            }
        }
        boolean dandelion = "dandelion".equals(s.flavor);
        for (String k : JmxDoc.CONN_FIELDS) {
            // dandelion(JavaSampler) 插件不读取 Dubbo Defaults，与 JMeter 行为保持一致
            if (dandelion || !fields.get(k).isEmpty()) {
                continue;
            }
            for (TNode d : defaults) {
                String v = JmxDoc.dubboFields(d).get(k);
                if (v != null && !v.isEmpty()) {
                    fields.put(k, v);
                    inherited.addProperty(k, d.name());
                    break;
                }
            }
        }
        // 供脚本 sampler.getPropertyAsString("Yajad.xxx") 使用的原始属性视图
        Map<String, String> mapping = "ningyu".equals(s.flavor) ? JmxDoc.NINGYU : JmxDoc.YAJAD;
        if (dandelion) {
            mapping = new LinkedHashMap<>();
            for (String k : fields.keySet()) {
                mapping.put(k, k);
            }
        }
        Map<String, String> rawProps = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            rawProps.put(e.getValue(), fields.get(e.getKey()));
        }
        ScriptApi.Sampler sampler = new ScriptApi.Sampler(s.name(), rawProps);

        // 4. 前置处理器（同一线程执行，RpcContext 附件可生效）
        RpcContext.removeContext();
        for (TNode c : scope) {
            if ("pre".equals(c.kind)) {
                runScript(c, baseDir, fn, bindings(vars, session.last, sampler, log), log);
            }
        }
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            fields.put(e.getKey(), rawProps.get(e.getValue()));
        }

        // 5. 变量/函数替换
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            resolved.put(e.getKey(), fn.apply(e.getValue() == null ? "" : e.getValue()));
        }
        DubboClient.Conn conn = dandelion ? Dandelions.conn(resolved) : toConn(resolved);
        request.addProperty("registry", conn.direct() ? "直连 " + conn.directUrl()
                : (conn.registryAddress.contains("://") ? "" : conn.registryProtocol + "://") + conn.registryAddress
                + (conn.registryGroup.isEmpty() ? "" : " (group=" + conn.registryGroup + ")"));
        request.addProperty("interface", conn.iface);
        request.addProperty("method", resolved.get("method"));
        JsonObject options = new JsonObject();
        options.addProperty("rpcProtocol", conn.rpcProtocol);
        options.addProperty("version", conn.version);
        options.addProperty("group", conn.group);
        options.addProperty("timeout", conn.timeout);
        options.addProperty("retries", conn.retries);
        options.addProperty("cluster", conn.cluster);
        options.addProperty("loadbalance", conn.loadbalance);
        options.addProperty("connections", conn.connections);
        request.add("options", options);
        request.add("inherited", inherited);
        request.addProperty("params", resolved.get("params"));

        ScriptApi.Result result = new ScriptApi.Result();
        result.setSampleLabel(s.name());
        result.setSamplerData(resolved.get("interface") + "#" + resolved.get("method") + "\n" + resolved.get("params"));
        boolean ok;
        String response;
        long elapsed = 0;
        long connectMs = 0;
        long sendStart = System.currentTimeMillis();
        try {
            if (conn.iface.isEmpty() || resolved.get("method").trim().isEmpty()) {
                throw new UserError("请填写接口和方法名");
            }
            if (!conn.direct() && conn.registryAddress.isEmpty()) {
                throw new UserError("未配置注册中心地址：请在请求的“连接”中填写，或在所在分组添加 Dubbo Defaults");
            }
            String[] types;
            Object[] values;
            if (dandelion) {
                Dandelions.Call call = Dandelions.build(s, fn, loader.get());
                types = call.types;
                values = call.values;
                Dandelions.applyAppid(resolved.get("appidKey"), values, log);
            } else {
                DubboParamDto params;
                try {
                    params = YamlParamParser.parseParameter(resolved.get("params"));
                } catch (Exception e) {
                    Throwable root = e.getCause() != null ? e.getCause() : e;
                    throw new UserError("参数解析错误：" + e.getMessage() + (root != e ? "\n" + root.getMessage() : ""));
                }
                types = params.getTypes().toArray(new String[0]);
                values = params.getValues().toArray();
            }
            request.add("parameterTypes", GSON.toJsonTree(types));
            JsonArray vals = new JsonArray();
            for (Object v : values) {
                vals.add(safeTree(v));
            }
            request.add("parameterValues", vals);
            Map<String, String> attachments = new HashMap<>(RpcContext.getContext().getAttachments());
            request.add("attachments", GSON.toJsonTree(attachments));

            long t0 = System.currentTimeMillis();
            GenericService[] holder = new GenericService[1];
            boolean created;
            try {
                created = client.prepare(conn, holder);
            } catch (RuntimeException e) {
                throw new ConnectError(e);
            }
            connectMs = created ? System.currentTimeMillis() - t0 : 0;
            // 准备连接可能清理了线程上下文，重新放回附件
            if (!attachments.isEmpty()) {
                RpcContext.getContext().setAttachments(attachments);
            }
            long t1 = System.nanoTime();
            Object r;
            try {
                r = holder[0].$invoke(resolved.get("method").trim(), types, values);
            } finally {
                elapsed = (System.nanoTime() - t1) / 1_000_000;
            }
            response = JsonUtils.toJson(r);
            ok = true;
        } catch (Throwable e) {
            ok = false;
            response = "";
            out.add("error", errorJson(e, ConnectFailures.since(sendStart - 50)));
        } finally {
            RpcContext.removeContext();
        }
        result.setSuccessful(ok);
        result.setTime(elapsed);
        result.setResponseData(ok ? response : out.getAsJsonObject("error").get("message").getAsString());
        result.setResponseCode(ok ? "200" : "500");
        result.setResponseMessage(ok ? "OK" : "ERROR");

        // 6. 后置处理器
        for (TNode c : scope) {
            if ("post".equals(c.kind)) {
                runScript(c, baseDir, fn, bindings(vars, result, sampler, log), log);
            }
        }
        session.last = result;
        return finish(out, result, response, elapsed, connectMs, log, vars);
    }

    /** ZMS 消息：与插件 com.zto.zmsjmeter.ZMSSampler 一致（syncSend 返回码 200 为成功；sendOneWay 无响应） */
    private JsonObject sendZms(TNode s, List<TNode> scope, Path baseDir, Functions fn, Vars vars, Session session,
                               ScriptLog log, JsonObject out, JsonObject request) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (String k : JmxDoc.ZMS_FIELDS) {
            raw.put(k, s.prop(k));
        }
        ScriptApi.Sampler sampler = new ScriptApi.Sampler(s.name(), raw);
        for (TNode c : scope) {
            if ("pre".equals(c.kind)) {
                runScript(c, baseDir, fn, bindings(vars, session.last, sampler, log), log);
            }
        }
        Map<String, String> f = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            f.put(e.getKey(), fn.apply(e.getValue() == null ? "" : e.getValue()));
        }
        String mode = f.get("messageSendMod").trim().isEmpty() ? "syncSend" : f.get("messageSendMod").trim();
        request.addProperty("type", "zms");
        request.addProperty("zk", f.get("zmsZKAddr"));
        request.addProperty("topic", f.get("topic"));
        request.addProperty("tag", f.get("messageTag"));
        request.addProperty("key", f.get("messageKey"));
        request.addProperty("mode", mode);
        request.addProperty("body", f.get("messageBody"));

        ScriptApi.Result result = new ScriptApi.Result();
        result.setSampleLabel(s.name());
        result.setSamplerData("msgBody:" + f.get("messageBody") + "; key:" + f.get("messageKey") + "; tag:" + f.get("messageTag"));
        boolean ok = false;
        String response = "";
        long elapsed = 0;
        try {
            long t0 = System.nanoTime();
            ZmsClient.Result r;
            try {
                r = ZmsClient.send(f.get("zmsZKAddr"), f.get("topic"), f.get("messageKey"), f.get("messageTag"),
                        f.get("messageBody"), mode, 60);
            } finally {
                elapsed = (System.nanoTime() - t0) / 1_000_000;
            }
            JsonObject o = new JsonObject();
            if (r.oneway) {
                o.addProperty("result", "this is sendOneWay,no response");
            } else {
                o.addProperty("code", r.code);
                o.addProperty("msgId", r.msgId);
                o.addProperty("topic", r.topic);
                o.addProperty("queueOrPartition", r.queue);
                o.addProperty("offset", r.offset);
                o.addProperty("msg", r.msg);
            }
            response = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(o);
            ok = r.code == 200;
            if (!ok) {
                JsonObject err = new JsonObject();
                err.addProperty("kind", "ZMS 返回失败（code=" + r.code + "）");
                err.addProperty("message", r.msg == null ? "" : r.msg);
                out.add("error", err);
            }
        } catch (Throwable e) {
            JsonObject err = new JsonObject();
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            err.addProperty("kind", e instanceof UserError ? "请求配置有误" : "ZMS 发送失败");
            err.addProperty("message", e instanceof UserError ? e.getMessage()
                    : (root.getClass().getSimpleName() + ": " + root.getMessage()));
            if (!(e instanceof UserError)) {
                err.addProperty("detail", String.valueOf(e));
                err.addProperty("stack", ScriptLog.stack(e));
            }
            out.add("error", err);
        }
        result.setSuccessful(ok);
        result.setTime(elapsed);
        result.setResponseData(ok ? response : out.getAsJsonObject("error").get("message").getAsString());
        result.setResponseCode(ok ? "200" : "500");
        result.setResponseMessage(ok ? "OK" : "ERROR");
        for (TNode c : scope) {
            if ("post".equals(c.kind)) {
                runScript(c, baseDir, fn, bindings(vars, result, sampler, log), log);
            }
        }
        session.last = result;
        return finish(out, result, response, elapsed, 0, log, vars);
    }

    private JsonObject finish(JsonObject out, ScriptApi.Result result, String response, long elapsed, long connectMs,
                              ScriptLog log, Vars vars) {
        out.addProperty("ok", result.isSuccessful());
        out.addProperty("elapsed", elapsed);
        out.addProperty("connectMs", connectMs);
        out.addProperty("response", response);
        out.addProperty("size", response.getBytes(StandardCharsets.UTF_8).length);
        out.addProperty("time", System.currentTimeMillis());
        JsonArray logs = new JsonArray();
        for (ScriptLog.Line l : log.lines()) {
            JsonObject o = new JsonObject();
            o.addProperty("level", l.level);
            o.addProperty("source", l.source);
            o.addProperty("message", l.message);
            o.addProperty("time", l.time);
            logs.add(o);
        }
        out.add("logs", logs);
        out.add("vars", GSON.toJsonTree(vars.snapshot()));
        return out;
    }

    private void runScript(TNode c, Path baseDir, Functions fn, Map<String, Object> b, ScriptLog log) {
        log.setSource(c.name());
        String lang = c.testclass().startsWith("JSR223") ? (c.prop("scriptLanguage").isEmpty() ? "groovy" : c.prop("scriptLanguage")) : "beanshell";
        String file = fn.apply(c.prop("filename"));
        try {
            Path p = file.trim().isEmpty() ? null : resolve(baseDir, file.trim());
            Scripts.run(lang, c.prop("script"), p, fn.apply(c.prop("parameters")), b, loader.get(), log);
        } catch (Throwable e) {
            log.system("ERROR", "[" + c.name() + "] 脚本执行出错（与 JMeter 一致：记录错误后继续发送）: " + e.getMessage()
                    + (e.getCause() != null ? "\n" + ScriptLog.stack(e.getCause()) : ""));
        }
        log.setSource("");
    }

    private Map<String, Object> bindings(Vars vars, ScriptApi.Result prev, ScriptApi.Sampler sampler, ScriptLog log) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("vars", vars);
        b.put("props", props);
        b.put("log", log);
        b.put("prev", prev);
        b.put("sampler", sampler);
        b.put("ctx", new ScriptApi.Ctx(vars, props, prev, sampler));
        b.put("Label", sampler == null ? "" : sampler.getName());
        return b;
    }

    private static DubboClient.Conn toConn(Map<String, String> f) {
        DubboClient.Conn c = new DubboClient.Conn();
        c.registryProtocol = f.get("registryProtocol").trim();
        c.registryAddress = f.get("registryAddress").trim();
        if (c.registryProtocol.isEmpty()) {
            int i = c.registryAddress.indexOf("://");
            c.registryProtocol = i > 0 ? c.registryAddress.substring(0, i) : "zookeeper";
        }
        c.registryGroup = f.get("registryGroup").trim();
        String rpc = f.get("rpcProtocol").trim();
        if (rpc.endsWith("://")) {
            rpc = rpc.substring(0, rpc.length() - 3);
        }
        c.rpcProtocol = rpc.isEmpty() ? "dubbo" : rpc;
        c.timeout = toInt(f.get("timeout"), 120000);
        c.retries = toInt(f.get("retries"), 0);
        c.version = f.get("version").trim();
        c.cluster = f.get("cluster").trim();
        c.group = f.get("group").trim();
        c.connections = toInt(f.get("connections"), 100);
        c.loadbalance = f.get("loadbalance").trim();
        c.iface = f.get("interface").trim();
        if (c.direct()) {
            String addr = c.registryAddress;
            int i = addr.indexOf("://");
            c.registryAddress = i >= 0 ? addr.substring(i + 3) : addr;
        }
        return c;
    }

    private static int toInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static Path resolve(Path base, String f) {
        if (f == null || f.trim().isEmpty()) {
            return null;
        }
        Path p = java.nio.file.Paths.get(f.trim());
        if (!p.isAbsolute() && base != null) {
            p = base.resolve(p);
        }
        return p;
    }

    private static com.google.gson.JsonElement safeTree(Object v) {
        try {
            return com.google.gson.JsonParser.parseString(JsonUtils.toJson(v));
        } catch (Throwable e) {
            return new com.google.gson.JsonPrimitive(String.valueOf(v));
        }
    }

    static final class UserError extends RuntimeException {
        UserError(String m) {
            super(m);
        }
    }

    static final class ConnectError extends RuntimeException {
        ConnectError(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    private static final Pattern CAUSED_BY = Pattern.compile("Caused by: ([^\\r\\n]+)");
    private static final Pattern PROVIDER = Pattern.compile("dubbo://([\\w.-]+:\\d+)/");

    /** 把 Dubbo 冗长的异常整理成：类别 + 一句话原因 + 提供者地址，完整信息放在 detail/stack 中 */
    private static JsonObject errorJson(Throwable e, Map<String, String> connectFailures) {
        JsonObject o = new JsonObject();
        if (e instanceof UserError) {
            o.addProperty("kind", "请求配置有误");
            o.addProperty("message", e.getMessage());
            return o;
        }
        Throwable real = e instanceof ConnectError ? e.getCause() : e;
        StringBuilder all = new StringBuilder();
        Throwable root = real;
        for (Throwable c = real; c != null; c = c.getCause()) {
            all.append(c.getMessage()).append('\n');
            root = c;
        }
        String text = all.toString();
        String rootLine = null;
        Matcher m = CAUSED_BY.matcher(text);
        while (m.find()) {
            rootLine = m.group(1).trim();
        }
        String kind;
        String message;
        if (e instanceof GenericException) {
            GenericException g = (GenericException) e;
            kind = "服务端异常";
            message = g.getExceptionClass() + ": " + g.getExceptionMessage();
        } else if (text.contains("No provider available") && !connectFailures.isEmpty()) {
            kind = "无法连接服务提供者";
            message = "注册中心里找到了该服务的提供者，但本机连不上：\n  "
                    + String.join("\n  ", ConnectFailures.describe(connectFailures))
                    + "\n请检查本机网络 / VPN 能否访问这些地址（可在终端用 nc -z IP 端口 验证）";
        } else if (text.contains("No provider available")) {
            kind = "没有可用的服务提供者";
            message = firstLine(text.substring(text.indexOf("No provider available")));
            int cut = message.indexOf(" from the url");
            message = (cut > 0 ? message.substring(0, cut) : message)
                    + "\n请检查：接口名 / 版本 / 服务分组是否与提供者一致，注册中心地址是否正确，服务是否已启动";
        } else if (e instanceof ConnectError) {
            kind = text.contains("zookeeper not connected") ? "无法连接注册中心" : "引用服务失败";
            message = firstLine(String.valueOf(real.getMessage()));
        } else if (e instanceof com.alibaba.dubbo.rpc.RpcException) {
            com.alibaba.dubbo.rpc.RpcException r = (com.alibaba.dubbo.rpc.RpcException) e;
            boolean remote = text.contains("\tat ") && text.contains("RemotingException");
            if (r.isTimeout()) {
                kind = "调用超时";
                message = text.contains("Waiting server-side response timeout")
                        ? "在超时时间内服务端没有返回（服务端处理慢或卡住）" : "连接或等待响应超时";
            } else if (remote || rootLine != null) {
                kind = "服务端异常";
                message = rootLine != null ? rootLine : firstLine(root.getMessage());
            } else if (r.isSerialization()) {
                kind = "序列化异常";
                message = afterCause(text);
            } else if (r.isNetwork()) {
                kind = "网络异常";
                message = afterCause(text);
            } else {
                kind = "调用失败";
                message = afterCause(text);
            }
        } else {
            kind = real.getClass().getSimpleName();
            message = String.valueOf(real.getMessage());
        }
        Matcher pm = PROVIDER.matcher(text);
        if (pm.find()) {
            o.addProperty("provider", pm.group(1));
        }
        o.addProperty("kind", kind);
        o.addProperty("message", message);
        o.addProperty("detail", String.valueOf(real.getMessage()));
        o.addProperty("stack", ScriptLog.stack(real));
        return o;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        String l = (i < 0 ? s : s.substring(0, i)).trim();
        return l.length() > 600 ? l.substring(0, 600) + "…" : l;
    }

    private static String afterCause(String text) {
        int i = text.lastIndexOf(" cause: ");
        return firstLine(i >= 0 ? text.substring(i + 8) : text);
    }
}
