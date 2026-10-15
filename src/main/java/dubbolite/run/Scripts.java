package dubbolite.run;

import bsh.EvalError;
import bsh.Interpreter;
import bsh.TargetError;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 执行 BeanShell / JSR223 脚本，绑定 JMeter 兼容的 vars/props/log/prev/sampler/ctx */
final class Scripts {

    private Scripts() {
    }

    static Object run(String language, String script, Path file, String parameters,
                      Map<String, Object> bindings, ClassLoader loader, ScriptLog log) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>(bindings);
        String params = parameters == null ? "" : parameters;
        String[] args = params.trim().isEmpty() ? new String[0] : params.trim().split("\\s+");
        b.put("Parameters", params);
        b.put("args", args);
        b.put("FileName", file == null ? "" : file.toString());
        String code = file != null ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : script;
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        String lang = language == null ? "beanshell" : language.toLowerCase();
        if (!lang.equals("beanshell") && !lang.equals("bsh") && !lang.equals("java")) {
            ScriptEngine engine = new ScriptEngineManager(loader).getEngineByName(lang);
            if (engine != null) {
                Thread th = Thread.currentThread();
                ClassLoader old = th.getContextClassLoader();
                th.setContextClassLoader(loader);
                try {
                    Bindings bs = engine.createBindings();
                    bs.putAll(b);
                    bs.put("OUT", System.out);
                    return engine.eval(code, bs);
                } finally {
                    th.setContextClassLoader(old);
                }
            }
            log.system("WARN", "未找到 " + language + " 脚本引擎，已按 BeanShell 兼容方式执行"
                    + "（如需原生 Groovy，可在设置中加入 groovy 的 jar 所在目录）");
        }
        return beanshell(code, b, loader, log);
    }

    private static Object beanshell(String code, Map<String, Object> b, ClassLoader loader, ScriptLog log) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, "UTF-8");
        Interpreter bsh = new Interpreter(new StringReader(""), out, out, false);
        bsh.setClassLoader(loader);
        for (Map.Entry<String, Object> e : b.entrySet()) {
            bsh.set(e.getKey(), e.getValue());
        }
        bsh.set("bsh.args", b.get("args"));
        Thread th = Thread.currentThread();
        ClassLoader old = th.getContextClassLoader();
        th.setContextClassLoader(loader);
        try {
            return bsh.eval(code);
        } catch (TargetError e) {
            Throwable t = e.getTarget();
            throw new ScriptException("第 " + e.getErrorLineNumber() + " 行抛出异常: " + t, t);
        } catch (EvalError e) {
            throw new ScriptException("第 " + e.getErrorLineNumber() + " 行: " + e.getMessage(), null);
        } finally {
            th.setContextClassLoader(old);
            String printed = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
            if (!printed.isEmpty()) {
                log.info(printed);
            }
        }
    }

    static final class ScriptException extends Exception {
        ScriptException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
