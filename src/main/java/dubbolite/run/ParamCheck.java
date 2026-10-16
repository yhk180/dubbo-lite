package dubbolite.run;

import com.alibaba.fastjson.JSON;
import com.google.gson.JsonObject;
import com.yajad.jmeter.parse.YamlParamParser;
import dubbolite.jmx.Dandelion;
import org.snakeyaml.engine.v1.exceptions.Mark;
import org.snakeyaml.engine.v1.exceptions.MarkedYamlEngineException;

import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编辑参数时的即时校验：用与发送时相同的解析器检查语法，返回出错位置与原因。
 * ${变量} 先替换为等长的 0，位置保持不变；只依赖变量取值的错误要到发送时才能发现。
 */
public final class ParamCheck {

    private static final Pattern FASTJSON_POS = Pattern.compile("pos (\\d+)");

    private ParamCheck() {
    }

    /** 没有问题时返回空对象，否则 {error: 原因, pos: 出错的字符位置（可能没有）} */
    public static JsonObject check(String text, boolean dandelion) {
        String s = maskVars(text == null ? "" : text);
        if (s.trim().isEmpty()) {
            return new JsonObject();
        }
        return dandelion ? checkDandelion(s) : checkYajad(s);
    }

    private static JsonObject checkYajad(String s) {
        try {
            YamlParamParser.parseParameter(s);
            return new JsonObject();
        } catch (Exception e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof MarkedYamlEngineException) {
                    MarkedYamlEngineException m = (MarkedYamlEngineException) t;
                    Mark mark = m.getProblemMark().orElse(m.getContextMark().orElse(null));
                    int pos = mark == null ? -1 : mark.getIndex();
                    String p = m.getProblem() == null ? "" : m.getProblem();
                    if (pos >= 0 && p.startsWith("expected ',' or ") && !p.contains("<stream end>")) {
                        pos = missingCommaPos(s, pos);
                    }
                    return error(yamlProblem(m), pos);
                }
            }
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof DateTimeParseException) {
                    String v = ((DateTimeParseException) t).getParsedString();
                    return error("无法解析日期时间 '" + v + "'，格式应为 yyyy-MM-dd HH:mm:ss（如 2023-04-20 13:50:00）", s.indexOf(v));
                }
            }
            Throwable root = e.getCause() != null ? e.getCause() : e;
            return error(root.getMessage() != null ? root.getMessage() : root.toString(), -1);
        }
    }

    private static JsonObject checkDandelion(String s) {
        List<String[]> items;
        try {
            items = Dandelion.parseTyped(s);
        } catch (IllegalArgumentException e) {
            Matcher m = Pattern.compile("第 (\\d+) 个字符").matcher(e.getMessage());
            return error(e.getMessage(), m.find() ? Integer.parseInt(m.group(1)) - 1 : -1);
        }
        int from = 0;
        for (int i = 0; i < items.size(); i++) {
            String raw = items.get(i)[1];
            int at = s.indexOf(raw, from);
            from = at < 0 ? from : at + raw.length();
            char c = raw.charAt(0);
            // 与发送时一致：{ [ 开头按 JSON 解析（解析失败会被当成普通字符串发出去），" 开头按 JSON 字符串解析
            if (c != '{' && c != '[' && c != '"') {
                continue;
            }
            try {
                JSON.parse(raw);
            } catch (RuntimeException e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                Matcher m = FASTJSON_POS.matcher(msg);
                int pos = m.find() && at >= 0 ? at + Math.min(Integer.parseInt(m.group(1)), raw.length() - 1) : at;
                JsonObject o = error("第 " + (i + 1) + " 个参数不是合法的 JSON：" + msg.replaceAll(",? ?pos(ition at)? \\d+.*$", ""), pos);
                // fastjson 给出的位置常常不准，前端会在这个范围内再定位一次
                if (at >= 0) {
                    o.addProperty("valueStart", at);
                    o.addProperty("valueEnd", at + raw.length());
                }
                return o;
            }
        }
        return new JsonObject();
    }

    /**
     * 缺逗号时解析器报在下一个键（或其后的冒号）上；退回到上一个值的末尾，即逗号应在的位置
     */
    static int missingCommaPos(String s, int p) {
        int i = Math.min(p, s.length() - 1);
        if (s.charAt(i) == ':') {
            i = skipSpaceBack(s, i - 1);
            if (i >= 0 && s.charAt(i) == '"') {
                i--;
                while (i >= 0 && !(s.charAt(i) == '"' && (i == 0 || s.charAt(i - 1) != '\\'))) {
                    i--;
                }
            } else {
                while (i > 0 && !Character.isWhitespace(s.charAt(i - 1)) && "{[,".indexOf(s.charAt(i - 1)) < 0) {
                    i--;
                }
            }
        }
        int end = skipSpaceBack(s, i - 1);
        return end < 0 ? p : end + 1;
    }

    private static int skipSpaceBack(String s, int i) {
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        return i;
    }

    private static String yamlProblem(MarkedYamlEngineException e) {
        String p = e.getProblem() == null ? "" : e.getProblem();
        String ctx = e.getContext() == null ? "" : e.getContext();
        if (p.startsWith("expected ',' or '}'")) {
            return "这里缺少 , 或 }（上一个值之后需要逗号）";
        }
        if (p.startsWith("expected ',' or ']'")) {
            return p.contains("<stream end>") ? "缺少 ]，方括号没有闭合" : "这里缺少 , 或 ]（上一个值之后需要逗号）";
        }
        if (p.contains("unexpected end of stream") && ctx.contains("quoted scalar")) {
            return "字符串缺少结束引号";
        }
        if (p.startsWith("expected '<document start>'")) {
            return "多余的内容（括号可能多了）";
        }
        if (p.contains("found unknown escape character")) {
            return "字符串中有无法识别的转义字符";
        }
        return (ctx.isEmpty() ? "" : ctx + ": ") + p;
    }

    private static JsonObject error(String msg, int pos) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        if (pos >= 0) {
            o.addProperty("pos", pos);
        }
        return o;
    }

    /** ${...}（可嵌套）替换为等长的 0：可作数字也可作字符串，出错位置不受影响 */
    static String maskVars(String s) {
        StringBuilder sb = new StringBuilder(s);
        int i = s.indexOf("${");
        while (i >= 0) {
            int depth = 0;
            int j = i;
            for (; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    break;
                } else if (c == '\n') {
                    break;
                }
            }
            if (j >= s.length() || s.charAt(j) != '}') {
                break;
            }
            for (int k = i; k <= j; k++) {
                sb.setCharAt(k, '0');
            }
            i = s.indexOf("${", j + 1);
        }
        return sb.toString();
    }
}
