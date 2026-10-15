package dubbolite.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记录 Dubbo 连接提供者失败的日志。check=true 时 Dubbo 只报 "No provider available"，
 * 实际原因往往是注册中心里有提供者、但本机连不上，这里把真实原因找回来。
 */
final class ConnectFailures {

    private static final Pattern ADDR = Pattern.compile("connect to the server /?([\\w.\\-]+:\\d+)");
    private static final Pattern REASON = Pattern.compile("error message is:([^\\n]+?)(?:, dubbo version|$)", Pattern.MULTILINE);
    private static final ConcurrentLinkedDeque<Object[]> RECENT = new ConcurrentLinkedDeque<>();
    private static volatile boolean installed;

    private ConnectFailures() {
    }

    static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        // Dubbo 使用 JDK 日志。这里在代码中设置一次（它会重新加载 logging.properties 并清空已注册的 handler）；
        // 不能用系统属性 dubbo.application.logger，否则每次创建服务引用时都会再次重置日志、丢掉下面的 handler
        com.alibaba.dubbo.common.logger.LoggerFactory.setLoggerAdapter("jdk");
        Logger.getLogger("").addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel().intValue() < Level.WARNING.intValue() || r.getMessage() == null) {
                    return;
                }
                String m = r.getMessage();
                if (!m.contains("Failed to start NettyClient") && !m.contains("failed to connect to server")) {
                    return;
                }
                Matcher a = ADDR.matcher(m);
                if (!a.find()) {
                    return;
                }
                String reason = "";
                String all = m + (r.getThrown() != null ? "\n" + r.getThrown().getMessage() : "");
                Matcher rm = REASON.matcher(all);
                if (rm.find()) {
                    reason = rm.group(1).trim();
                }
                RECENT.addLast(new Object[]{System.currentTimeMillis(), a.group(1), reason});
                while (RECENT.size() > 200) {
                    RECENT.pollFirst();
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    /** since 之后记录到的连接失败：地址 → 原因 */
    static Map<String, String> since(long since) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Object[] o : RECENT) {
            if ((Long) o[0] >= since) {
                m.put((String) o[1], (String) o[2]);
            }
        }
        return m;
    }

    static List<String> describe(Map<String, String> m) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            out.add(e.getKey() + (e.getValue().isEmpty() ? "" : "（" + e.getValue() + "）"));
        }
        return out;
    }
}
