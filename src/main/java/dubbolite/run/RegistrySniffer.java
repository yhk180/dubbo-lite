package dubbolite.run;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.KeeperException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 从 ZooKeeper 注册中心嗅探服务：/{group}/ 下的接口列表，以及 /{group}/{接口}/providers 下提供者 URL 中的方法、版本、分组。
 * ZK 连接按地址复用；结果短时间缓存，refresh 时重新读取。
 */
public final class RegistrySniffer {

    private static final long SERVICES_TTL = TimeUnit.MINUTES.toMillis(2);
    private static final long PROVIDERS_TTL = TimeUnit.SECONDS.toMillis(30);

    private final Map<String, CuratorFramework> clients = new ConcurrentHashMap<>();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private static final class Cached {
        final long at = System.currentTimeMillis();
        final JsonObject value;

        Cached(JsonObject value) {
            this.value = value;
        }
    }

    /** {services: [接口名...]} */
    public JsonObject services(String protocol, String address, String group, boolean refresh) throws Exception {
        String cs = connectString(protocol, address);
        String root = root(group);
        return cached("s|" + cs + "|" + root, SERVICES_TTL, refresh, () -> {
            List<String> names;
            try {
                names = client(cs).getChildren().forPath(root);
            } catch (KeeperException.NoNodeException e) {
                throw new Runner.UserError("注册中心上没有 " + root + " 节点（注册中心分组是否正确？）");
            }
            TreeSet<String> sorted = new TreeSet<>();
            for (String n : names) {
                // 2.7 的 /dubbo/config、/dubbo/metadata 等不是服务；“接口:版本”形式的节点不是接口名
                if (n.contains(".") && n.indexOf(':') < 0) {
                    sorted.add(n);
                }
            }
            JsonArray arr = new JsonArray();
            sorted.forEach(arr::add);
            JsonObject o = new JsonObject();
            o.add("services", arr);
            return o;
        });
    }

    /** {providers: [{address, application, version, group, methods: [...], dubbo}]} */
    public JsonObject providers(String protocol, String address, String group, String iface, boolean refresh) throws Exception {
        String cs = connectString(protocol, address);
        String path = root(group) + "/" + iface.trim() + "/providers";
        return cached("p|" + cs + "|" + path, PROVIDERS_TTL, refresh, () -> {
            List<String> urls;
            try {
                urls = client(cs).getChildren().forPath(path);
            } catch (KeeperException.NoNodeException e) {
                urls = Collections.emptyList();
            }
            JsonArray arr = new JsonArray();
            for (String raw : urls) {
                URL u;
                try {
                    u = URL.valueOf(URL.decode(raw));
                } catch (RuntimeException e) {
                    continue;
                }
                JsonObject p = new JsonObject();
                p.addProperty("address", u.getAddress());
                p.addProperty("protocol", u.getProtocol());
                p.addProperty("application", u.getParameter("application", ""));
                p.addProperty("version", u.getParameter("version", ""));
                p.addProperty("group", u.getParameter("group", ""));
                p.addProperty("dubbo", u.getParameter("release", u.getParameter("dubbo", "")));
                JsonArray methods = new JsonArray();
                for (String m : new TreeSet<>(splitMethods(u.getParameter("methods", "")))) {
                    methods.add(m);
                }
                p.add("methods", methods);
                arr.add(p);
            }
            JsonObject o = new JsonObject();
            o.add("providers", arr);
            return o;
        });
    }

    /** 断开所有 ZK 连接并清空缓存 */
    public void reset() {
        cache.clear();
        for (CuratorFramework c : clients.values()) {
            try {
                c.close();
            } catch (Throwable ignore) {
                // ignore
            }
        }
        clients.clear();
    }

    private interface Loader {
        JsonObject load() throws Exception;
    }

    private JsonObject cached(String key, long ttl, boolean refresh, Loader loader) throws Exception {
        Cached c = cache.get(key);
        if (!refresh && c != null && System.currentTimeMillis() - c.at < ttl) {
            return withAge(c);
        }
        Cached fresh = new Cached(loader.load());
        cache.put(key, fresh);
        return withAge(fresh);
    }

    private static JsonObject withAge(Cached c) {
        JsonObject o = c.value.deepCopy();
        o.addProperty("fetchedAt", c.at);
        return o;
    }

    private CuratorFramework client(String cs) throws InterruptedException {
        CuratorFramework c = clients.get(cs);
        if (c != null && c.getZookeeperClient().isConnected()) {
            return c;
        }
        if (c != null) {
            clients.remove(cs);
            c.close();
        }
        c = CuratorFrameworkFactory.builder().connectString(cs)
                .sessionTimeoutMs(30000).connectionTimeoutMs(5000)
                .retryPolicy(new RetryNTimes(1, 500)).build();
        c.start();
        if (!c.blockUntilConnected(6, TimeUnit.SECONDS)) {
            c.close();
            throw new Runner.UserError("连接注册中心超时：" + cs + "（本机能否访问该 ZooKeeper？）");
        }
        clients.put(cs, c);
        return c;
    }

    /** 与发送时一致地解析注册中心地址（支持 zookeeper://、?backup=、逗号分隔、| 分隔的多个注册中心取第一个） */
    static String connectString(String protocol, String address) {
        String addr = address == null ? "" : address.trim();
        String proto = protocol == null || protocol.trim().isEmpty() ? "zookeeper" : protocol.trim().toLowerCase();
        if (addr.isEmpty()) {
            throw new Runner.UserError("未配置注册中心地址");
        }
        if (addr.contains("${")) {
            throw new Runner.UserError("注册中心地址中有未定义的变量：" + addr);
        }
        Map<String, String> defaults = new java.util.HashMap<>();
        defaults.put("protocol", proto);
        List<URL> urls = UrlUtils.parseURLs(addr, defaults);
        if (urls == null || urls.isEmpty()) {
            throw new Runner.UserError("无法解析注册中心地址：" + addr);
        }
        URL u = urls.get(0);
        if (!"zookeeper".equalsIgnoreCase(u.getProtocol())) {
            throw new Runner.UserError("只支持嗅探 zookeeper 注册中心（当前为 " + u.getProtocol() + "）");
        }
        return u.getBackupAddress();
    }

    private static String root(String group) {
        String g = group == null || group.trim().isEmpty() ? "dubbo" : group.trim();
        return g.startsWith("/") ? g : "/" + g;
    }

    private static List<String> splitMethods(String s) {
        List<String> out = new ArrayList<>();
        for (String m : s.split(",")) {
            if (!m.trim().isEmpty()) {
                out.add(m.trim());
            }
        }
        return out;
    }
}
