package dubbolite.run;

import java.net.InetAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMeter 变量与函数替换：${name}、${__func(a,b,var)}，支持嵌套与 \, 转义。
 * 实现了 JMeter 中最常用的一批函数；未知函数原样保留（与 JMeter 行为一致）。
 */
public final class Functions {

    public interface ScriptEval {
        String eval(String language, String script) throws Exception;
    }

    private static final AtomicLong COUNTER = new AtomicLong();

    private final Vars vars;
    private final Properties props;
    private final ScriptLog log;
    private final String samplerName;
    private final String groupName;
    private final ScriptEval scriptEval;

    public Functions(Vars vars, Properties props, ScriptLog log, String samplerName, String groupName, ScriptEval scriptEval) {
        this.vars = vars;
        this.props = props;
        this.log = log;
        this.samplerName = samplerName;
        this.groupName = groupName;
        this.scriptEval = scriptEval;
    }

    public String apply(String s) {
        if (s == null || !s.contains("${")) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        int[] pos = {0};
        while (pos[0] < s.length()) {
            int i = s.indexOf("${", pos[0]);
            if (i < 0) {
                out.append(s, pos[0], s.length());
                break;
            }
            out.append(s, pos[0], i);
            pos[0] = i;
            out.append(expression(s, pos));
        }
        return out.toString();
    }

    /** s[pos] 指向 "${"，解析一个表达式并返回求值结果，pos 移动到表达式之后 */
    private String expression(String s, int[] pos) {
        int start = pos[0];
        int i = start + 2;
        if (s.startsWith("__", i)) {
            int j = i;
            while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                j++;
            }
            String name = s.substring(i + 2, j);
            List<String> args = new ArrayList<>();
            if (j < s.length() && s.charAt(j) == '(') {
                pos[0] = j + 1;
                if (!parseArgs(s, pos, args)) {
                    pos[0] = start + 2;
                    return "${";
                }
                j = pos[0];
            }
            if (j < s.length() && s.charAt(j) == '}') {
                pos[0] = j + 1;
                String raw = s.substring(start, j + 1);
                return call(name, args, raw);
            }
            pos[0] = start + 2;
            return "${";
        }
        int end = s.indexOf('}', i);
        if (end < 0) {
            pos[0] = s.length();
            return s.substring(start);
        }
        String name = s.substring(i, end);
        if (name.contains("${")) {
            // 不支持嵌套变量名，按字面量处理外层，继续解析内层
            pos[0] = start + 2;
            return "${";
        }
        pos[0] = end + 1;
        String v = vars.get(name);
        return v != null ? v : s.substring(start, end + 1);
    }

    /** 解析函数参数直到匹配的 ')'，参数中的函数/变量即时求值 */
    private boolean parseArgs(String s, int[] pos, List<String> args) {
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        int i = pos[0];
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == ',' || n == '\\' || n == ')' || n == '(' || n == '$') {
                    cur.append(n);
                    i += 2;
                    continue;
                }
                cur.append(c);
                i++;
            } else if (c == '$' && s.startsWith("${", i)) {
                pos[0] = i;
                cur.append(expression(s, pos));
                i = pos[0];
            } else if (c == '(') {
                depth++;
                cur.append(c);
                i++;
            } else if (c == ')') {
                if (depth == 0) {
                    args.add(cur.toString());
                    pos[0] = i + 1;
                    if (args.size() == 1 && args.get(0).isEmpty()) {
                        args.clear();
                    }
                    return true;
                }
                depth--;
                cur.append(c);
                i++;
            } else if (c == ',' && depth == 0) {
                args.add(cur.toString());
                cur.setLength(0);
                i++;
            } else {
                cur.append(c);
                i++;
            }
        }
        return false;
    }

    private static String arg(List<String> a, int i) {
        return i < a.size() ? a.get(i) : "";
    }

    private String store(List<String> a, int varIdx, String value) {
        String var = arg(a, varIdx).trim();
        if (!var.isEmpty()) {
            vars.put(var, value);
        }
        return value;
    }

    private String call(String name, List<String> a, String raw) {
        try {
            switch (name) {
                case "Random": {
                    long min = Long.parseLong(arg(a, 0).trim());
                    long max = Long.parseLong(arg(a, 1).trim());
                    return store(a, 2, String.valueOf(min + (long) (ThreadLocalRandom.current().nextDouble() * (max - min + 1))));
                }
                case "RandomString": {
                    int len = Integer.parseInt(arg(a, 0).trim());
                    String chars = arg(a, 1);
                    if (chars.isEmpty()) {
                        chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < len; k++) {
                        sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
                    }
                    return store(a, 2, sb.toString());
                }
                case "UUID":
                    return UUID.randomUUID().toString();
                case "time":
                    return store(a, 1, time(arg(a, 0)));
                case "timeShift":
                    return store(a, 4, timeShift(arg(a, 0), arg(a, 1), arg(a, 2), arg(a, 3)));
                case "dateTimeConvert": {
                    Date d = new SimpleDateFormat(arg(a, 1)).parse(arg(a, 0));
                    return store(a, 3, new SimpleDateFormat(arg(a, 2)).format(d));
                }
                case "counter":
                    return store(a, 1, String.valueOf(COUNTER.incrementAndGet()));
                case "threadNum":
                    return "1";
                case "iterationNum":
                    return "1";
                case "threadGroupName":
                    return groupName;
                case "samplerName":
                    return store(a, 0, samplerName);
                case "P":
                case "property": {
                    String v = props.getProperty(arg(a, 0), a.size() > 1 ? arg(a, 1) : ("P".equals(name) ? "1" : arg(a, 0)));
                    return "property".equals(name) ? store(a, 2, v) : v;
                }
                case "setProperty":
                    props.setProperty(arg(a, 0), arg(a, 1));
                    return "";
                case "V": {
                    String v = vars.get(arg(a, 0));
                    return v != null ? v : (a.size() > 1 ? arg(a, 1) : "${" + arg(a, 0) + "}");
                }
                case "eval":
                    return apply(arg(a, 0));
                case "evalVar": {
                    String v = vars.get(arg(a, 0).trim());
                    return v == null ? "" : apply(v);
                }
                case "intSum":
                case "longSum": {
                    long sum = 0;
                    int n = a.size();
                    String var = null;
                    if (n > 2) {
                        try {
                            Long.parseLong(a.get(n - 1).trim());
                        } catch (NumberFormatException e) {
                            var = a.get(n - 1).trim();
                            n--;
                        }
                    }
                    for (int k = 0; k < n; k++) {
                        sum += Long.parseLong(a.get(k).trim());
                    }
                    String v = String.valueOf(sum);
                    if (var != null) {
                        vars.put(var, v);
                    }
                    return v;
                }
                case "base64Encode":
                    return store(a, 1, Base64.getEncoder().encodeToString(arg(a, 0).getBytes(StandardCharsets.UTF_8)));
                case "base64Decode":
                    return store(a, 1, new String(Base64.getDecoder().decode(arg(a, 0).trim()), StandardCharsets.UTF_8));
                case "urlencode":
                    return URLEncoder.encode(arg(a, 0), "UTF-8");
                case "urldecode":
                    return URLDecoder.decode(arg(a, 0), "UTF-8");
                case "digest":
                case "MD5":
                case "md5": {
                    String alg = "digest".equals(name) ? arg(a, 0) : "MD5";
                    int off = "digest".equals(name) ? 1 : 0;
                    MessageDigest md = MessageDigest.getInstance(alg.isEmpty() ? "SHA-1" : alg);
                    md.update(arg(a, off).getBytes(StandardCharsets.UTF_8));
                    if ("digest".equals(name) && !arg(a, 2).isEmpty()) {
                        md.update(arg(a, 2).getBytes(StandardCharsets.UTF_8));
                    }
                    StringBuilder sb = new StringBuilder();
                    for (byte bt : md.digest()) {
                        sb.append(String.format("%02x", bt));
                    }
                    String v = sb.toString();
                    if ("digest".equals(name)) {
                        if ("true".equalsIgnoreCase(arg(a, 3).trim())) {
                            v = v.toUpperCase();
                        }
                        return store(a, 4, v);
                    }
                    return store(a, 1, v);
                }
                case "char": {
                    StringBuilder sb = new StringBuilder();
                    for (String x : a) {
                        x = x.trim();
                        sb.append((char) (x.startsWith("0x") ? Integer.parseInt(x.substring(2), 16) : Integer.parseInt(x)));
                    }
                    return sb.toString();
                }
                case "changeCase": {
                    String mode = arg(a, 1).trim().toUpperCase();
                    String v = arg(a, 0);
                    v = "LOWER".equals(mode) ? v.toLowerCase() : "CAPITALIZE".equals(mode) && !v.isEmpty()
                            ? Character.toUpperCase(v.charAt(0)) + v.substring(1) : v.toUpperCase();
                    return store(a, 2, v);
                }
                case "machineName":
                    return store(a, 0, InetAddress.getLocalHost().getHostName());
                case "machineIP":
                    return store(a, 0, InetAddress.getLocalHost().getHostAddress());
                case "log":
                    log.info(arg(a, 0));
                    return arg(a, 0);
                case "BeanShell":
                case "groovy":
                case "javaScript": {
                    String lang = "BeanShell".equals(name) ? "beanshell" : "groovy".equals(name) ? "groovy" : "javascript";
                    String v = scriptEval.eval(lang, arg(a, 0));
                    return store(a, 1, v == null ? "" : v);
                }
                default:
                    log.system("WARN", "暂不支持函数 __" + name + "，已原样保留: " + raw);
                    return raw;
            }
        } catch (Exception e) {
            log.system("WARN", "函数求值失败 " + raw + " : " + e);
            return raw;
        }
    }

    private static String time(String format) {
        long now = System.currentTimeMillis();
        String f = format.trim();
        if (f.isEmpty()) {
            return String.valueOf(now);
        }
        if (f.startsWith("/")) {
            return String.valueOf(now / Long.parseLong(f.substring(1)));
        }
        switch (f) {
            case "YMD": f = "yyyyMMdd"; break;
            case "HMS": f = "HHmmss"; break;
            case "YMDHMS": f = "yyyyMMdd-HHmmss"; break;
            case "USER1": case "USER2": return String.valueOf(now);
            default:
        }
        return new SimpleDateFormat(format).format(new Date(now));
    }

    private static String timeShift(String format, String date, String shift, String locale) {
        Locale loc = locale.trim().isEmpty() ? Locale.getDefault() : Locale.forLanguageTag(locale.trim().replace('_', '-'));
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime t;
        DateTimeFormatter fmt = format.isEmpty() ? null : DateTimeFormatter.ofPattern(format, loc);
        if (date.trim().isEmpty()) {
            t = LocalDateTime.now();
        } else if (fmt == null) {
            t = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(date.trim())), zone);
        } else {
            try {
                t = LocalDateTime.parse(date, fmt);
            } catch (Exception e) {
                t = LocalDate.parse(date, fmt).atStartOfDay();
            }
        }
        if (!shift.trim().isEmpty()) {
            t = t.plus(Duration.parse(shift.trim()));
        }
        return fmt == null ? String.valueOf(t.atZone(zone).toInstant().toEpochMilli()) : t.format(fmt);
    }
}
