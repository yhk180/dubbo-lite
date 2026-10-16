package dubbolite.run;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ZMS 消息发送（与 JMeter 插件 com.zto.zmsjmeter.ZMSSampler 的行为一致）。
 * <p>
 * ZMS 客户端 jar 自带 Kafka/RocketMQ/ZooKeeper/Guava/Netty 等依赖，和 Dubbo 的依赖版本冲突，
 * 因此放在 lib-zms 目录、用独立的类加载器加载；每个 ZK 地址一个类加载器（ZMS 只在初始化时读取 zms_zk）。
 */
final class ZmsClient {

    static final class Result {
        int code;
        String msgId;
        String msg;
        String topic;
        long offset;
        int queue;
        boolean oneway;
    }

    private static final Map<String, ClassLoader> LOADERS = new LinkedHashMap<>();
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "zms-send");
        t.setDaemon(true);
        return t;
    });

    private ZmsClient() {
    }

    static List<File> jars() {
        List<File> dirs = new ArrayList<>();
        String sys = System.getProperty("dubbolite.zms.dir");
        if (sys != null) {
            dirs.add(new File(sys));
        }
        try {
            File self = new File(ZmsClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // 发行包：dubbo-lite.jar 同级的 lib-zms；开发时：build/classes → 项目根目录的 lib-zms
            dirs.add(new File(self.getParentFile(), "lib-zms"));
            dirs.add(new File(self.getParentFile().getParentFile(), "lib-zms"));
        } catch (Exception ignore) {
            // ignore
        }
        dirs.add(new File(System.getProperty("user.home"), ".dubbo-lite/lib-zms"));
        for (File d : dirs) {
            File[] js = d.listFiles((x, n) -> n.endsWith(".jar"));
            if (js != null && js.length > 0) {
                List<File> out = new ArrayList<>();
                for (File j : js) {
                    out.add(j);
                }
                return out;
            }
        }
        return new ArrayList<>();
    }

    private static synchronized ClassLoader loader(String zk) throws Exception {
        ClassLoader cl = LOADERS.get(zk);
        if (cl == null) {
            List<File> jars = jars();
            if (jars.isEmpty()) {
                throw new Runner.UserError("没有找到 ZMS 客户端：请把 jmeter-plugins-zmsclient-*-jar-with-dependencies.jar 放到程序目录的 lib-zms 文件夹（或 ~/.dubbo-lite/lib-zms）");
            }
            URL[] urls = new URL[jars.size()];
            for (int i = 0; i < urls.length; i++) {
                urls[i] = jars.get(i).toURI().toURL();
            }
            // 父加载器只用到 JDK 自身的类，与 Dubbo 等依赖完全隔离
            cl = new URLClassLoader(urls, ClassLoader.getSystemClassLoader().getParent());
            LOADERS.put(zk, cl);
        }
        return cl;
    }

    static Result send(String zk, String topic, String key, String tag, String body, String mode, int timeoutSec) throws Exception {
        if (zk.trim().isEmpty()) {
            throw new Runner.UserError("请填写 ZMS 的 ZK 地址（zmsZKAddr）");
        }
        if (topic.trim().isEmpty()) {
            throw new Runner.UserError("请填写 topic");
        }
        boolean oneway = "sendOneWay".equals(mode);
        if (!oneway && !"syncSend".equals(mode)) {
            throw new Runner.UserError("不支持的发送方式：" + mode + "（可选 syncSend / sendOneWay）");
        }
        ClassLoader cl = loader(zk.trim());
        Future<Result> f = POOL.submit(() -> {
            Thread th = Thread.currentThread();
            ClassLoader old = th.getContextClassLoader();
            th.setContextClassLoader(cl);
            try {
                synchronized (ZmsClient.class) {
                    // ZMS 客户端通过系统属性 zms_zk 找到注册中心（插件同样如此）
                    System.setProperty("zms_zk", zk.trim());
                    return doSend(cl, topic.trim(), key, tag, body, oneway);
                }
            } finally {
                th.setContextClassLoader(old);
            }
        });
        try {
            return f.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new IllegalStateException("发送超过 " + timeoutSec + " 秒没有结果：请检查 ZK 地址与网络");
        } catch (InterruptedException e) {
            // 用户主动中断
            f.cancel(true);
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            while ((c instanceof InvocationTargetException || c instanceof java.lang.reflect.UndeclaredThrowableException) && c.getCause() != null) {
                c = c.getCause();
            }
            if (c instanceof Exception) {
                throw (Exception) c;
            }
            throw e;
        }
    }

    private static Result doSend(ClassLoader cl, String topic, String key, String tag, String body, boolean oneway) throws Exception {
        // 先查 topic 元数据：topic 不存在时 ZMS 客户端只会抛出 NullPointerException
        Object metadata;
        try {
            Object zk = cl.loadClass("com.zto.zookeeper.RouterManager").getMethod("getZkInstance").invoke(null);
            metadata = zk.getClass().getMethod("readTopicMetadata", String.class).invoke(zk, topic);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("读取 topic 元数据失败（请检查 ZK 地址与网络）：" + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            metadata = "unknown";
        }
        if (metadata == null) {
            throw new Runner.UserError("ZMS 中不存在 topic：" + topic + "\n请检查 topic 名称，以及 ZK 地址是否对应正确的环境");
        }
        Class<?> builderCls = cl.loadClass("com.zto.common.SimpleMessageBuilder");
        Class<?> msgCls = cl.loadClass("com.zto.common.SimpleMessage");
        Class<?> zms = cl.loadClass("com.zto.Zms");
        Object b = builderCls.getMethod("newInstance").invoke(null);
        b = builderCls.getMethod("buildPaylod", byte[].class).invoke(b, (Object) body.getBytes(StandardCharsets.UTF_8));
        if (!tag.isEmpty()) {
            b = builderCls.getMethod("buildTags", String.class).invoke(b, tag);
        }
        if (!key.isEmpty()) {
            b = builderCls.getMethod("buildKey", String.class).invoke(b, key);
        }
        Object msg = builderCls.getMethod("build").invoke(b);
        Result r = new Result();
        r.topic = topic;
        if (oneway) {
            zms.getMethod("sendOneway", String.class, msgCls).invoke(null, topic, msg);
            r.oneway = true;
            r.code = 200;
            return r;
        }
        Object resp = zms.getMethod("send", String.class, msgCls).invoke(null, topic, msg);
        if (resp == null) {
            throw new IllegalStateException("ZMS 没有返回结果");
        }
        r.code = (Integer) call(resp, "getCode");
        r.msgId = (String) call(resp, "getMsgId");
        r.msg = (String) call(resp, "getMsg");
        Object off = call(resp, "getOffset");
        r.offset = off instanceof Long ? (Long) off : 0;
        Object q = call(resp, "getQueueOrPartition");
        r.queue = q instanceof Integer ? (Integer) q : 0;
        Object t = call(resp, "getTopic");
        if (t != null) {
            r.topic = String.valueOf(t);
        }
        return r;
    }

    private static Object call(Object o, String name) {
        try {
            Method m = o.getClass().getMethod(name);
            return m.invoke(o);
        } catch (Exception e) {
            return null;
        }
    }
}
