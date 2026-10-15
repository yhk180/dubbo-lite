package dubbolite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dubbo Lite 入口：本机 127.0.0.1 上的 HTTP 服务 + Web 界面。
 * <ul>
 *   <li>默认：启动后用 Chrome/Edge 的 --app 模式（无地址栏的独立窗口）打开，找不到则用默认浏览器；窗口全部关闭后自动退出</li>
 *   <li>--shell：由 macOS 原生外壳(DubboLite.app)启动，只在标准输出打印地址，标准输入关闭时退出</li>
 * </ul>
 */
public final class Main {

    static final String VERSION = "1.0.0";
    private static final int DEFAULT_PORT = 17788;

    private final String token = randomToken();
    private final Settings settings = new Settings();
    private final Api api = new Api(settings);
    private final List<OutputStream> eventClients = new CopyOnWriteArrayList<>();
    private final AtomicLong lastClientSeen = new AtomicLong(System.currentTimeMillis());
    private volatile boolean everConnected;
    private int port;

    public static void main(String[] args) throws Exception {
        Logger.getLogger("").setLevel(Level.WARNING);
        boolean shell = false;
        String open = null;
        for (String a : args) {
            if ("--shell".equals(a)) {
                shell = true;
            } else if (a.endsWith(".jmx")) {
                open = new File(a).getAbsolutePath();
            }
        }
        if (!shell && reuseRunningInstance(open)) {
            return;
        }
        new Main().start(shell, open);
    }

    private void start(boolean shell, String open) throws Exception {
        settings.load();
        HttpServer server = bind(shell ? 0 : DEFAULT_PORT);
        port = server.getAddress().getPort();
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::route);
        server.start();
        String url = "http://127.0.0.1:" + port + "/?t=" + token + (open == null ? "" : "&open=" + java.net.URLEncoder.encode(open, "UTF-8"));
        writeInstanceFile();
        if (shell) {
            System.out.println("DUBBO_LITE_URL " + url);
            System.out.flush();
            // 父进程（原生外壳）退出时标准输入会关闭，随之退出
            Thread watcher = new Thread(() -> {
                try {
                    while (System.in.read() >= 0) {
                        // 忽略
                    }
                } catch (IOException ignore) {
                    // ignore
                }
                System.exit(0);
            }, "stdin-watcher");
            watcher.setDaemon(true);
            watcher.start();
        } else {
            System.out.println("Dubbo Lite 已启动: " + url);
            openWindow(url);
            startIdleWatcher();
        }
        Thread ping = new Thread(this::pingLoop, "sse-ping");
        ping.setDaemon(true);
        ping.start();
        // 保持主线程
        Thread.currentThread().join();
    }

    private static HttpServer bind(int preferred) throws IOException {
        InetAddress lo = InetAddress.getLoopbackAddress();
        if (preferred > 0) {
            try {
                return HttpServer.create(new InetSocketAddress(lo, preferred), 0);
            } catch (IOException ignore) {
                // 端口被占用，改用随机端口
            }
        }
        return HttpServer.create(new InetSocketAddress(lo, 0), 0);
    }

    // ------------------------------------------------------------------ routing

    private void route(HttpExchange ex) throws IOException {
        try {
            String host = ex.getRequestHeaders().getFirst("Host");
            if (host == null || !(host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port))) {
                send(ex, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/events")) {
                events(ex);
                return;
            }
            if (path.equals("/api/ping")) {
                if (token.equals(ex.getRequestHeaders().getFirst("X-Token"))) {
                    send(ex, 200, "text/plain", "dubbo-lite".getBytes(StandardCharsets.UTF_8));
                } else {
                    send(ex, 403, "text/plain", new byte[0]);
                }
                return;
            }
            if (path.startsWith("/api/")) {
                apiCall(ex, path.substring(5));
                return;
            }
            staticFile(ex, path);
        } catch (Throwable t) {
            try {
                send(ex, 500, "text/plain; charset=utf-8", String.valueOf(t).getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignore) {
                // ignore
            }
        } finally {
            if (!ex.getRequestURI().getPath().equals("/api/events")) {
                ex.close();
            }
        }
    }

    private void apiCall(HttpExchange ex, String action) throws IOException {
        if (!"POST".equals(ex.getRequestMethod()) || !token.equals(ex.getRequestHeaders().getFirst("X-Token"))) {
            send(ex, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String body = Api.readSafe(ex.getRequestBody());
        JsonObject in = body.trim().isEmpty() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        JsonObject out = new JsonObject();
        try {
            JsonElement r = api.handle(action, in);
            out.addProperty("ok", true);
            out.add("data", r);
        } catch (Throwable t) {
            out.addProperty("ok", false);
            out.addProperty("error", t.getMessage() == null ? t.toString() : t.getMessage());
        }
        send(ex, 200, "application/json; charset=utf-8", Json.GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
    }

    private void staticFile(HttpExchange ex, String path) throws IOException {
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            send(ex, 404, "text/plain", new byte[0]);
            return;
        }
        InputStream in = Main.class.getResourceAsStream("/web" + path);
        if (in == null) {
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] data = Api.readBytes(in);
        String type = path.endsWith(".html") ? "text/html; charset=utf-8"
                : path.endsWith(".js") ? "application/javascript; charset=utf-8"
                : path.endsWith(".css") ? "text/css; charset=utf-8"
                : path.endsWith(".svg") ? "image/svg+xml"
                : path.endsWith(".png") ? "image/png" : "application/octet-stream";
        if (path.equals("/index.html")) {
            String q = ex.getRequestURI().getQuery();
            if (q == null || !q.contains("t=" + token)) {
                send(ex, 403, "text/html; charset=utf-8",
                        "<meta charset=utf-8><p style='font:14px sans-serif;padding:40px'>请通过 Dubbo Lite 启动器打开。</p>"
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, type, data);
    }

    private void events(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();
        if (q == null || !q.contains("t=" + token)) {
            send(ex, 403, "text/plain", new byte[0]);
            ex.close();
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        os.write(": hello\n\n".getBytes(StandardCharsets.UTF_8));
        os.flush();
        everConnected = true;
        lastClientSeen.set(System.currentTimeMillis());
        eventClients.add(os);
    }

    private void pingLoop() {
        while (true) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
            for (OutputStream os : eventClients) {
                try {
                    os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException e) {
                    eventClients.remove(os);
                    try {
                        os.close();
                    } catch (IOException ignore) {
                        // ignore
                    }
                }
            }
            if (!eventClients.isEmpty()) {
                lastClientSeen.set(System.currentTimeMillis());
            }
        }
    }

    /** 浏览器模式：所有窗口关闭 15 秒后退出；启动后 2 分钟内没有窗口连上也退出 */
    private void startIdleWatcher() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                long idle = System.currentTimeMillis() - lastClientSeen.get();
                if (eventClients.isEmpty() && ((everConnected && idle > 15_000) || (!everConnected && idle > 120_000))) {
                    System.exit(0);
                }
            }
        }, "idle-watcher");
        t.setDaemon(true);
        t.start();
    }

    private static void send(HttpExchange ex, int code, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, data.length == 0 ? -1 : data.length);
        if (data.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
    }

    // ------------------------------------------------------------------ instance & window

    private void writeInstanceFile() {
        try {
            Files.createDirectories(Settings.HOME);
            JsonObject o = new JsonObject();
            o.addProperty("port", port);
            o.addProperty("token", token);
            Path f = Settings.HOME.resolve("instance.json");
            Files.write(f, Json.GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
            f.toFile().setReadable(false, false);
            f.toFile().setReadable(true, true);
        } catch (IOException ignore) {
            // ignore
        }
    }

    /** 已有实例在运行时直接打开新窗口，不再启动第二个进程 */
    private static boolean reuseRunningInstance(String open) {
        try {
            Path f = Settings.HOME.resolve("instance.json");
            if (!Files.isRegularFile(f)) {
                return false;
            }
            JsonObject o = JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject();
            int port = o.get("port").getAsInt();
            String token = o.get("token").getAsString();
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/ping").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(800);
            c.setRequestProperty("X-Token", token);
            if (c.getResponseCode() != 200) {
                return false;
            }
            String url = "http://127.0.0.1:" + port + "/?t=" + token + (open == null ? "" : "&open=" + java.net.URLEncoder.encode(open, "UTF-8"));
            System.out.println("Dubbo Lite 已在运行，打开新窗口: " + url);
            openWindow(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void openWindow(String url) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) {
                for (String app : new String[]{"Google Chrome", "Microsoft Edge", "Brave Browser", "Chromium"}) {
                    if (new File("/Applications/" + app + ".app").exists()) {
                        new ProcessBuilder("open", "-na", app, "--args", "--app=" + url).start();
                        return;
                    }
                }
                new ProcessBuilder("open", url).start();
                return;
            }
            if (os.contains("win")) {
                String[] candidates = {
                        System.getenv("ProgramFiles") + "\\Google\\Chrome\\Application\\chrome.exe",
                        System.getenv("ProgramFiles(x86)") + "\\Google\\Chrome\\Application\\chrome.exe",
                        System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
                        System.getenv("ProgramFiles(x86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
                        System.getenv("ProgramFiles") + "\\Microsoft\\Edge\\Application\\msedge.exe"};
                for (String c : candidates) {
                    if (new File(c).exists()) {
                        new ProcessBuilder(c, "--app=" + url).start();
                        return;
                    }
                }
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                return;
            }
            for (String c : new String[]{"google-chrome", "chromium", "chromium-browser", "microsoft-edge"}) {
                try {
                    new ProcessBuilder(c, "--app=" + url).start();
                    return;
                } catch (IOException ignore) {
                    // try next
                }
            }
            new ProcessBuilder("xdg-open", url).start();
        } catch (Exception e) {
            System.out.println("请在浏览器中打开: " + url);
        }
    }

    private static String randomToken() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
