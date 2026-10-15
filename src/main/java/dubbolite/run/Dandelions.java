package dubbolite.run;

import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.util.TypeUtils;
import dubbolite.jmx.Dandelion;
import dubbolite.jmx.JmxDoc;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按 jmeter-plugins-dandelion（SgrDubbo / DubboApp）的语义构造泛化调用 */
final class Dandelions {

    private Dandelions() {
    }

    static final class Call {
        String[] types;
        Object[] values;
    }

    private static final Map<String, Class<?>> PRIMITIVES = new HashMap<>();
    private static final Map<ClassLoader, ParserConfig> CONFIGS = new java.util.WeakHashMap<>();

    static {
        for (Class<?> c : new Class<?>[]{int.class, long.class, float.class, double.class, byte.class, boolean.class, short.class, char.class}) {
            PRIMITIVES.put(c.getName(), c);
        }
    }

    /** URL 含 zookeeper 时作为注册中心地址，否则作为直连地址（与插件一致） */
    static DubboClient.Conn conn(Map<String, String> f) {
        String url = f.get("registryAddress").trim();
        if (url.isEmpty()) {
            throw new Runner.UserError("URL 为空：请填写注册中心地址（zookeeper://...）或直连地址（IP:端口）");
        }
        if (url.contains("${")) {
            throw new Runner.UserError("URL 中的变量没有定义：" + url + "\n请在测试计划或所在分组的“用户定义的变量”中设置");
        }
        DubboClient.Conn c = new DubboClient.Conn();
        if (url.contains("zookeeper")) {
            c.registryProtocol = "zookeeper";
            c.registryAddress = url;
        } else {
            c.registryProtocol = "none";
            String addr = url.replaceFirst("^[a-zA-Z]+://", "");
            int slash = addr.indexOf('/');
            c.registryAddress = slash > 0 ? addr.substring(0, slash) : addr;
        }
        c.registryGroup = "";
        c.rpcProtocol = "dubbo";
        try {
            c.timeout = Integer.parseInt(f.get("timeout").trim());
        } catch (Exception e) {
            c.timeout = 3000;
        }
        c.retries = 0;
        c.version = f.get("version").trim();
        c.group = f.get("group").trim();
        c.cluster = "";
        c.loadbalance = "";
        c.connections = 0;
        c.iface = f.get("interface").trim();
        return c;
    }

    static Call build(JmxDoc.TNode s, Functions fn, ClassLoader loader) {
        int typesIdx = Dandelion.isSgr(s) ? 9 : 6;
        List<String> types = Dandelion.parseTypes(fn.apply(Dandelion.arg(s, typesIdx)));
        List<String> raws = Dandelion.rawParams(s);
        Call call = new Call();
        call.types = types.toArray(new String[0]);
        call.values = new Object[types.size()];
        for (int i = 0; i < types.size(); i++) {
            String raw = i < raws.size() ? fn.apply(raws.get(i)) : "";
            try {
                call.values[i] = convert(types.get(i), raw, loader);
            } catch (RuntimeException e) {
                throw new Runner.UserError("第 " + (i + 1) + " 个参数（" + types.get(i) + "）格式错误：" + e.getMessage());
            }
        }
        return call;
    }

    private static Object convert(String type, String raw, ClassLoader loader) {
        String v = raw.trim();
        if ("java.lang.String".equals(type) || "String".equals(type)) {
            return v.startsWith("\"") ? JSON.parse(v) : raw;
        }
        Class<?> cls = PRIMITIVES.get(type);
        if (cls == null) {
            try {
                cls = Class.forName(type, false, loader);
            } catch (Throwable e) {
                cls = null;
            }
        }
        if (cls != null && (cls.isPrimitive() || cls.getName().startsWith("java.lang."))) {
            return TypeUtils.castToJavaBean(parseLenient(v), cls);
        }
        if (cls != null && !Collection.class.isAssignableFrom(cls) && !Map.class.isAssignableFrom(cls)
                && !cls.isInterface() && v.startsWith("{")) {
            // 扩展 jar 中有该类：像插件一样转成真实对象
            ParserConfig cfg;
            synchronized (CONFIGS) {
                cfg = CONFIGS.computeIfAbsent(loader, ParserConfig::new);
            }
            return JSON.parseObject(v, cls, cfg);
        }
        return plain(parseLenient(v));
    }

    private static Object parseLenient(String v) {
        if (v.isEmpty()) {
            return null;
        }
        try {
            return JSON.parse(v, Feature.OrderedField);
        } catch (RuntimeException e) {
            return v;
        }
    }

    /** fastjson 的 JSONObject/JSONArray 转为普通集合，避免提供者端缺少 fastjson 类 */
    private static Object plain(Object o) {
        if (o instanceof JSONObject) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((JSONObject) o).entrySet()) {
                m.put(e.getKey(), plain(e.getValue()));
            }
            return m;
        }
        if (o instanceof JSONArray) {
            List<Object> l = new ArrayList<>();
            for (Object x : (JSONArray) o) {
                l.add(plain(x));
            }
            return l;
        }
        return o;
    }

    /**
     * appidKey：JSON 时逐项作为附件；否则按 "appid,key[,x]" 生成签名附件
     * appid / secretkey / timestampmillis（算法与插件的 Sha1Utils / Sha2Utils 相同）。
     */
    static void applyAppid(String appidKey, Object[] args, ScriptLog log) {
        String a = appidKey == null ? "" : appidKey.trim();
        if (a.isEmpty()) {
            return;
        }
        RpcContext ctx = RpcContext.getContext();
        if (a.startsWith("{")) {
            JSONObject o = JSON.parseObject(a);
            for (String k : o.keySet()) {
                ctx.setAttachment(k, o.getString(k));
            }
            return;
        }
        String[] parts = a.split(",");
        ctx.setAttachment("appid", parts[0]);
        if (parts.length < 2) {
            log.system("WARN", "appidKey 只有 appid，没有 key，未生成签名");
            return;
        }
        long ts = System.currentTimeMillis();
        String secret;
        if (parts.length > 2) {
            secret = DigestUtils.sha1Hex("appId=" + parts[0] + "&appKey=" + parts[1] + "&timestampmillis=" + ts);
        } else {
            Object req = args.length > 0 ? args[0] : null;
            String body = req instanceof String ? (String) req : JSON.toJSONString(req, SerializerFeature.MapSortField);
            secret = DigestUtils.sha1Hex("req=" + body + "&appId=" + parts[0] + "&appKey=" + parts[1] + "&timestampmillis=" + ts);
        }
        ctx.setAttachment("secretkey", secret);
        ctx.setAttachment("timestampmillis", String.valueOf(ts));
    }
}
