package dubbolite.run;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/** 脚本中的 log 对象（兼容 slf4j Logger 的常用方法），输出收集到界面的“日志”页 */
public class ScriptLog {

    public static final class Line {
        public final long time = System.currentTimeMillis();
        public final String level;
        public final String source;
        public final String message;

        Line(String level, String source, String message) {
            this.level = level;
            this.source = source;
            this.message = message;
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private String source = "";

    public void setSource(String source) {
        this.source = source;
    }

    public List<Line> lines() {
        return lines;
    }

    private synchronized void add(String level, String fmt, Object... args) {
        String msg = format(fmt, args);
        if (args.length > 0 && args[args.length - 1] instanceof Throwable && countPlaceholders(fmt) < args.length) {
            msg = msg + "\n" + stack((Throwable) args[args.length - 1]);
        }
        lines.add(new Line(level, source, msg));
    }

    public void system(String level, String msg) {
        synchronized (this) {
            lines.add(new Line(level, "", msg));
        }
    }

    static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static int countPlaceholders(String fmt) {
        int n = 0;
        for (int i = fmt.indexOf("{}"); i >= 0; i = fmt.indexOf("{}", i + 2)) {
            n++;
        }
        return n;
    }

    private static String format(String fmt, Object... args) {
        if (fmt == null) {
            return "null";
        }
        if (args.length == 0) {
            return fmt;
        }
        StringBuilder sb = new StringBuilder();
        int from = 0;
        int ai = 0;
        int i;
        while (ai < args.length && (i = fmt.indexOf("{}", from)) >= 0) {
            sb.append(fmt, from, i).append(args[ai++]);
            from = i + 2;
        }
        sb.append(fmt.substring(from));
        return sb.toString();
    }

    // BeanShell 2.0b6 不支持可变参数，这里显式列出常用重载
    public void info(Object msg) { add("INFO", String.valueOf(msg)); }
    public void info(String msg, Object a) { add("INFO", msg, a); }
    public void info(String msg, Object a, Object b) { add("INFO", msg, a, b); }
    public void info(String msg, Object[] args) { add("INFO", msg, args); }
    public void warn(Object msg) { add("WARN", String.valueOf(msg)); }
    public void warn(String msg, Object a) { add("WARN", msg, a); }
    public void warn(String msg, Object a, Object b) { add("WARN", msg, a, b); }
    public void error(Object msg) { add("ERROR", String.valueOf(msg)); }
    public void error(String msg, Object a) { add("ERROR", msg, a); }
    public void error(String msg, Object a, Object b) { add("ERROR", msg, a, b); }
    public void debug(Object msg) { add("DEBUG", String.valueOf(msg)); }
    public void debug(String msg, Object a) { add("DEBUG", msg, a); }
    public void debug(String msg, Object a, Object b) { add("DEBUG", msg, a, b); }
    public void trace(Object msg) { add("DEBUG", String.valueOf(msg)); }
    public boolean isDebugEnabled() { return true; }
    public boolean isInfoEnabled() { return true; }
    public boolean isWarnEnabled() { return true; }
    public boolean isErrorEnabled() { return true; }
}
