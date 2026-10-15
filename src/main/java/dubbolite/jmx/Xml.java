package dubbolite.jmx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简、保真的 XML 树。
 * <p>
 * JMX 由 JMeter(XStream) 生成，结构非常规整：要么纯文本叶子，要么纯元素容器。
 * 这里记录每个节点前面的原始空白、原始起始标签、原始文本以及是否自闭合，
 * 未被修改的部分写回时与原文件逐字节一致，最大限度保证与 JMeter 的兼容。
 */
public final class Xml {

    private Xml() {
    }

    public static final class Node {
        public String name;
        /** 属性（已反转义），保持原始顺序 */
        public final LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
        public final List<Node> children = new ArrayList<>();
        public Node parent;

        /** 该节点起始标签之前的原始文本（通常是换行+缩进） */
        String pre = "";
        /** 原始起始标签（不含 "/>" 或 ">"）；属性被修改后置空，按 attrs 重新生成 */
        String rawStart;
        /** 叶子节点的原始文本（未反转义）；被修改后置空，按 text 重新转义 */
        String rawText;
        /** 叶子节点文本（已反转义） */
        String text = "";
        /** 最后一个子元素之后、结束标签之前的原始文本 */
        String tail = "";
        boolean selfClosing;

        public Node(String name) {
            this.name = name;
        }

        public String attr(String key) {
            return attrs.get(key);
        }

        public void setAttr(String key, String value) {
            if (value == null) {
                if (attrs.remove(key) != null) {
                    rawStart = null;
                }
                return;
            }
            String old = attrs.put(key, value);
            if (!value.equals(old)) {
                rawStart = null;
            }
        }

        public String text() {
            return text;
        }

        public void setText(String value) {
            if (value == null) {
                value = "";
            }
            if (value.equals(text) && rawText != null) {
                return;
            }
            text = value;
            rawText = null;
            // JMeter 对空字符串属性写成 <stringProp name="x"></stringProp>
            selfClosing = false;
        }

        public Node child(String name) {
            for (Node c : children) {
                if (c.name.equals(name)) {
                    return c;
                }
            }
            return null;
        }

        public String indent() {
            int i = pre.lastIndexOf('\n');
            return i < 0 ? "" : pre.substring(i + 1);
        }

        /** 在 index 位置插入子节点，自动处理缩进与自闭合容器 */
        public void insert(int index, Node child) {
            String myIndent = indent();
            String childIndent;
            if (!children.isEmpty()) {
                childIndent = children.get(Math.min(index, children.size() - 1)).indent();
            } else {
                childIndent = myIndent + "  ";
            }
            if (children.isEmpty()) {
                selfClosing = false;
                tail = "\n" + myIndent;
                rawText = null;
                text = "";
            }
            child.reindent(childIndent);
            child.parent = this;
            children.add(index, child);
        }

        public void append(Node child) {
            insert(children.size(), child);
        }

        public void remove(Node child) {
            children.remove(child);
            child.parent = null;
            if (children.isEmpty() && "hashTree".equals(name)) {
                selfClosing = true;
                tail = "";
            }
        }

        /** 把整棵子树的缩进调整为以 indent 为基准 */
        void reindent(String indent) {
            String old = indent();
            pre = "\n" + indent;
            if (!children.isEmpty()) {
                for (Node c : children) {
                    String rel = c.indent().startsWith(old) ? c.indent().substring(old.length()) : "  ";
                    c.reindent(indent + rel);
                }
                tail = "\n" + indent;
            }
        }

        public Node deepCopy() {
            Node n = new Node(name);
            n.attrs.putAll(attrs);
            n.pre = pre;
            n.rawStart = rawStart;
            n.rawText = rawText;
            n.text = text;
            n.tail = tail;
            n.selfClosing = selfClosing;
            for (Node c : children) {
                Node cc = c.deepCopy();
                cc.parent = n;
                n.children.add(cc);
            }
            return n;
        }
    }

    public static final class Document {
        /** 根元素之前的原始内容（XML 声明等） */
        String prolog = "";
        public Node root;
        /** 根元素之后的原始内容 */
        String epilog = "";
    }

    // ------------------------------------------------------------------ parse

    public static Document parse(String s) {
        if (!s.isEmpty() && s.charAt(0) == '﻿') {
            s = s.substring(1);
        }
        Parser p = new Parser(s);
        return p.document();
    }

    /** 解析一个片段（用于模板），返回第一个元素 */
    public static Node parseFragment(String s) {
        return parse(s).root;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        Document document() {
            Document d = new Document();
            int start = pos;
            // 跳过声明、注释、DOCTYPE
            while (true) {
                int lt = s.indexOf('<', pos);
                if (lt < 0) {
                    throw new IllegalArgumentException("不是有效的 XML 文件");
                }
                if (s.startsWith("<?", lt)) {
                    pos = indexOrFail("?>", lt) + 2;
                } else if (s.startsWith("<!--", lt)) {
                    pos = indexOrFail("-->", lt) + 3;
                } else if (s.startsWith("<!", lt)) {
                    pos = indexOrFail(">", lt) + 1;
                } else {
                    pos = lt;
                    break;
                }
            }
            d.prolog = s.substring(start, pos);
            d.root = element("");
            d.epilog = s.substring(pos);
            return d;
        }

        int indexOrFail(String token, int from) {
            int i = s.indexOf(token, from);
            if (i < 0) {
                throw new IllegalArgumentException("XML 意外结束 @" + from);
            }
            return i;
        }

        Node element(String pre) {
            // s[pos] == '<'
            int nameStart = pos + 1;
            int i = nameStart;
            while (i < s.length() && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '>' && s.charAt(i) != '/') {
                i++;
            }
            Node n = new Node(s.substring(nameStart, i));
            n.pre = pre;
            // 属性
            while (true) {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                if (i >= s.length()) {
                    throw new IllegalArgumentException("XML 意外结束");
                }
                char c = s.charAt(i);
                if (c == '/' || c == '>') {
                    break;
                }
                int eq = s.indexOf('=', i);
                String key = s.substring(i, eq).trim();
                int q = eq + 1;
                while (Character.isWhitespace(s.charAt(q))) {
                    q++;
                }
                char quote = s.charAt(q);
                int end = s.indexOf(quote, q + 1);
                n.attrs.put(key, unescape(s.substring(q + 1, end)));
                i = end + 1;
            }
            n.rawStart = s.substring(pos, i);
            if (s.charAt(i) == '/') {
                n.selfClosing = true;
                pos = indexOrFail(">", i) + 1;
                return n;
            }
            pos = i + 1;
            int contentStart = pos;
            StringBuilder pending = new StringBuilder();
            while (true) {
                int lt = s.indexOf('<', pos);
                if (lt < 0) {
                    throw new IllegalArgumentException("XML 意外结束：<" + n.name + "> 未闭合");
                }
                String between = s.substring(pos, lt);
                if (s.startsWith("</", lt)) {
                    int gt = indexOrFail(">", lt);
                    if (n.children.isEmpty()) {
                        n.rawText = s.substring(contentStart, lt);
                        n.text = decodeContent(n.rawText);
                    } else {
                        n.tail = pending.append(between).toString();
                    }
                    pos = gt + 1;
                    return n;
                }
                if (s.startsWith("<!--", lt)) {
                    int end = indexOrFail("-->", lt) + 3;
                    pending.append(between).append(s, lt, end);
                    pos = end;
                    continue;
                }
                if (s.startsWith("<![CDATA[", lt)) {
                    // JMeter 不会生成 CDATA，这里按文本处理（整体保留为 rawText）
                    int end = indexOrFail("]]>", lt) + 3;
                    pos = end;
                    continue;
                }
                pos = lt;
                Node child = element(pending.append(between).toString());
                pending.setLength(0);
                child.parent = n;
                n.children.add(child);
            }
        }
    }

    // ------------------------------------------------------------------ write

    public static String write(Document d) {
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append(d.prolog);
        writeNode(sb, d.root, true);
        sb.append(d.epilog);
        return sb.toString();
    }

    private static void writeNode(StringBuilder sb, Node n, boolean root) {
        if (!root) {
            sb.append(n.pre);
        }
        if (n.rawStart != null) {
            sb.append(n.rawStart);
        } else {
            sb.append('<').append(n.name);
            for (Map.Entry<String, String> e : n.attrs.entrySet()) {
                sb.append(' ').append(e.getKey()).append("=\"").append(escape(e.getValue())).append('"');
            }
        }
        if (n.selfClosing && n.children.isEmpty()) {
            sb.append("/>");
            return;
        }
        sb.append('>');
        if (n.children.isEmpty()) {
            sb.append(n.rawText != null ? n.rawText : escape(n.text));
        } else {
            for (Node c : n.children) {
                writeNode(sb, c, false);
            }
            sb.append(n.tail);
        }
        sb.append("</").append(n.name).append('>');
    }

    /** 与 XStream(JMeter) 一致的转义规则 */
    public static String escape(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep;
            switch (c) {
                case '&': rep = "&amp;"; break;
                case '<': rep = "&lt;"; break;
                case '>': rep = "&gt;"; break;
                case '"': rep = "&quot;"; break;
                case '\'': rep = "&apos;"; break;
                case '\r': rep = "&#xd;"; break;
                default:
                    if (c < 0x20 && c != '\n' && c != '\t') {
                        rep = "&#x" + Integer.toHexString(c) + ";";
                    } else {
                        rep = null;
                    }
            }
            if (rep != null) {
                if (sb == null) {
                    sb = new StringBuilder(s.length() + 16);
                    sb.append(s, 0, i);
                }
                sb.append(rep);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    private static String decodeContent(String raw) {
        if (!raw.contains("<![CDATA[") && !raw.contains("<!--")) {
            return unescape(raw);
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            int c1 = raw.indexOf("<![CDATA[", i);
            int c2 = raw.indexOf("<!--", i);
            int next = c1 < 0 ? c2 : (c2 < 0 ? c1 : Math.min(c1, c2));
            if (next < 0) {
                sb.append(unescape(raw.substring(i)));
                break;
            }
            sb.append(unescape(raw.substring(i, next)));
            if (next == c1) {
                int end = raw.indexOf("]]>", next);
                sb.append(raw, next + 9, end);
                i = end + 3;
            } else {
                i = raw.indexOf("-->", next) + 3;
            }
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        int amp = s.indexOf('&');
        if (amp < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (amp >= 0) {
            sb.append(s, i, amp);
            int semi = s.indexOf(';', amp);
            if (semi < 0) {
                break;
            }
            String ent = s.substring(amp + 1, semi);
            switch (ent) {
                case "amp": sb.append('&'); break;
                case "lt": sb.append('<'); break;
                case "gt": sb.append('>'); break;
                case "quot": sb.append('"'); break;
                case "apos": sb.append('\''); break;
                default:
                    if (ent.startsWith("#x") || ent.startsWith("#X")) {
                        sb.appendCodePoint(Integer.parseInt(ent.substring(2), 16));
                    } else if (ent.startsWith("#")) {
                        sb.appendCodePoint(Integer.parseInt(ent.substring(1)));
                    } else {
                        sb.append('&').append(ent).append(';');
                    }
            }
            i = semi + 1;
            amp = s.indexOf('&', i);
        }
        sb.append(s.substring(i));
        return sb.toString();
    }
}
