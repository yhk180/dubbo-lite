package dubbolite.run;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.rpc.service.GenericService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 泛化调用客户端。引用配置与 yajad 插件 (DubboElement/OldDubboInvoker) 保持一致，
 * 按连接参数缓存 ReferenceConfig，初始化失败时销毁，避免 dubbo 2.6 "初始化失败后 get() 返回 null" 的问题。
 */
public final class DubboClient {

    public static final class Conn {
        public String registryProtocol;
        public String registryAddress;
        public String registryGroup;
        public String rpcProtocol;
        public int timeout;
        public int retries;
        public String version;
        public String cluster;
        public String group;
        public int connections;
        public String loadbalance;
        public String iface;

        public boolean direct() {
            return "none".equalsIgnoreCase(registryProtocol);
        }

        public String directUrl() {
            return rpcProtocol + "://" + registryAddress + "/" + iface;
        }

        String key() {
            return String.join("|", registryProtocol, registryAddress, registryGroup, rpcProtocol,
                    String.valueOf(timeout), String.valueOf(retries), version, cluster, group,
                    loadbalance, iface);
        }
    }

    private final Map<String, ReferenceConfig<GenericService>> refs = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final ApplicationConfig application;

    public DubboClient() {
        ConnectFailures.install();
        application = new ApplicationConfig("Yajad-Dubbo-Sample");
        application.setQosEnable(false);
    }

    /** @return true 表示本次新建了连接（首次调用会包含注册中心连接与订阅耗时） */
    public boolean prepare(Conn c, GenericService[] holder) {
        String key = c.key();
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            ReferenceConfig<GenericService> ref = refs.get(key);
            if (ref != null) {
                holder[0] = ref.get();
                return false;
            }
            ref = build(c);
            try {
                GenericService s = ref.get();
                if (s == null) {
                    throw new IllegalStateException("创建服务引用失败: " + c.iface);
                }
                refs.put(key, ref);
                holder[0] = s;
                return true;
            } catch (RuntimeException | Error e) {
                try {
                    ref.destroy();
                } catch (Throwable ignore) {
                    // ignore
                }
                throw e;
            }
        }
    }

    private ReferenceConfig<GenericService> build(Conn c) {
        ReferenceConfig<GenericService> ref = new ReferenceConfig<>();
        ref.setApplication(application);
        if (c.direct()) {
            ref.setUrl(c.directUrl());
        } else {
            RegistryConfig registry = new RegistryConfig();
            registry.setProtocol(c.registryProtocol);
            registry.setGroup(c.registryGroup);
            registry.setAddress(c.registryAddress);
            ref.setRegistry(registry);
        }
        ref.setProtocol(c.rpcProtocol);
        ref.setTimeout(c.timeout);
        ref.setRetries(c.retries);
        ref.setVersion(c.version);
        ref.setCluster(c.cluster);
        ref.setGroup(c.group);
        // connections 只对压测有意义（每个提供者建立 N 条长连接，首连可能耗时十几秒）；单次请求使用 Dubbo 共享连接
        ref.setLoadbalance(c.loadbalance);
        ref.setGeneric(true);
        ref.setInterface(c.iface);
        return ref;
    }

    /** 断开所有服务引用（注册中心连接会在 dubbo 内部按需重建） */
    public int reset() {
        int n = refs.size();
        for (ReferenceConfig<GenericService> ref : refs.values()) {
            try {
                ref.destroy();
            } catch (Throwable ignore) {
                // ignore
            }
        }
        refs.clear();
        return n;
    }
}
