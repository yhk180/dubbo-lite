package dubbolite.run;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** CSV Data Set：每次发送读取下一行（到末尾后按 recycle 决定是否从头开始） */
final class Csv {

    private final Map<String, Integer> cursors = new ConcurrentHashMap<>();

    void reset() {
        cursors.clear();
    }

    /** @return 读到的行号描述，用于日志 */
    String next(Path file, String encoding, String variableNames, String delimiter, boolean ignoreFirstLine,
                boolean quoted, boolean recycle, Vars vars) throws Exception {
        Charset cs = encoding == null || encoding.trim().isEmpty() ? Charset.forName("UTF-8") : Charset.forName(encoding.trim());
        List<String> lines = new ArrayList<>();
        for (String l : Files.readAllLines(file, cs)) {
            if (!l.isEmpty()) {
                lines.add(l.startsWith("﻿") ? l.substring(1) : l);
            }
        }
        String delim = delimiter == null || delimiter.isEmpty() ? "," : ("\\t".equals(delimiter) ? "\t" : delimiter);
        String[] names;
        int first = 0;
        if (variableNames == null || variableNames.trim().isEmpty()) {
            if (lines.isEmpty()) {
                return "空文件";
            }
            names = split(lines.get(0), delim, quoted);
            first = 1;
        } else {
            names = variableNames.split(",");
            if (ignoreFirstLine) {
                first = 1;
            }
        }
        int rows = lines.size() - first;
        String key = file + "|" + variableNames;
        int cur = cursors.getOrDefault(key, 0);
        if (cur >= rows) {
            if (recycle && rows > 0) {
                cur = 0;
            } else {
                for (String n : names) {
                    vars.put(n.trim(), "<EOF>");
                }
                return "已读到文件末尾(<EOF>)";
            }
        }
        String[] values = split(lines.get(first + cur), delim, quoted);
        for (int k = 0; k < names.length; k++) {
            vars.put(names[k].trim(), k < values.length ? values[k] : "");
        }
        cursors.put(key, cur + 1);
        return "第 " + (cur + 1) + "/" + rows + " 行";
    }

    private static String[] split(String line, String delim, boolean quoted) {
        if (!quoted) {
            return line.split(java.util.regex.Pattern.quote(delim), -1);
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQ = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"' && cur.length() == 0) {
                inQ = true;
            } else if (line.startsWith(delim, i)) {
                out.add(cur.toString());
                cur.setLength(0);
                i += delim.length() - 1;
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
