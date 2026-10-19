package dubbolite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 检查更新：读取 GitHub Releases 上最新版本的 latest.json，下载对应平台的安装包、校验 sha256、解压到 ~/.dubbo-lite/update，
 * 然后启动一个安装脚本——等原生外壳退出后替换应用并重新打开（失败时恢复旧版本）。
 * <p>
 * latest.json 由 release.sh 生成：
 * <pre>{"version":"1.1.0","date":"2026-10-20","notes":"…","page":"https://…/tag/v1.1.0",
 *  "assets":{"mac":{"url":"…","sha256":"…","size":123},"win":{…}}}</pre>
 * 只有从 DubboLite.app / DubboLite.exe 启动（外壳通过 -Ddubbolite.shellPid 告知自己的进程号）时才能自动安装；
 * 浏览器模式只提示并打开下载页面。
 */
final class Updater {

    private static final String LATEST_URL = "https://github.com/yhk180/dubbo-lite/releases/latest/download/latest.json";

    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean MAC = OS.contains("mac");
    private static final boolean WIN = OS.contains("win");
    private static final Path DIR = Settings.HOME.resolve("update");

    private final Settings settings;
    /** 最近一次检查得到的 latest.json */
    private volatile JsonObject latest;
    /** idle / downloading / ready / error / installing */
    private volatile String phase = "idle";
    private volatile long done;
    private volatile long total;
    private volatile String error;
    /** 解压后的新版应用：macOS 为 DubboLite.app，Windows 为含 DubboLite.exe 的目录 */
    private volatile Path staged;

    Updater(Settings settings) {
        this.settings = settings;
    }

    // ------------------------------------------------------------------ 接口

    synchronized JsonObject check() throws Exception {
        JsonObject j = fetchJson(LATEST_URL);
        String v = str(j, "version");
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("更新信息格式不正确：缺少 version");
        }
        if (latest == null || !v.equals(str(latest, "version"))) {
            phase = "idle";
            staged = null;
        }
        latest = j;
        return describe();
    }

    synchronized void skip(String version) {
        settings.setSkipVersion(version);
    }

    synchronized JsonObject download() {
        if ("downloading".equals(phase) || "ready".equals(phase)) {
            return status();
        }
        String reason = installBlocker();
        if (reason != null) {
            throw new IllegalStateException(reason);
        }
        JsonObject asset = asset();
        if (asset == null || str(asset, "url") == null || str(asset, "sha256") == null) {
            throw new IllegalStateException("这个版本没有提供当前系统的安装包");
        }
        phase = "downloading";
        done = 0;
        total = asset.has("size") ? asset.get("size").getAsLong() : 0;
        error = null;
        Thread t = new Thread(() -> {
            try {
                Path p = fetchAndStage(asset);
                synchronized (Updater.this) {
                    staged = p;
                    phase = "ready";
                }
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                phase = "error";
            }
        }, "updater");
        t.setDaemon(true);
        t.start();
        return status();
    }

    JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("phase", phase);
        o.addProperty("done", done);
        o.addProperty("total", total);
        o.addProperty("error", error);
        return o;
    }

    /** 启动安装脚本；之后由页面通知外壳退出，脚本等外壳退出后替换并重新打开 */
    synchronized JsonObject install() throws Exception {
        if (!"ready".equals(phase) || staged == null) {
            throw new IllegalStateException("安装包还没有下载完成");
        }
        String reason = installBlocker();
        if (reason != null) {
            throw new IllegalStateException(reason);
        }
        Path target = target();
        String pid = System.getProperty("dubbolite.shellPid");
        Path logDir = Settings.HOME.resolve("logs");
        Files.createDirectories(logDir);
        ProcessBuilder pb;
        if (MAC) {
            Path script = DIR.resolve("install.sh");
            Files.write(script, MAC_SCRIPT.getBytes(StandardCharsets.UTF_8));
            // 外壳退出时会给后台进程所在的进程组发信号；set -m 让安装脚本在单独的进程组中运行，不被一起结束
            pb = new ProcessBuilder("/bin/bash", "-c", "set -m; /bin/bash \"$@\" &", "install",
                    script.toString(), target.toString(), staged.toString(), pid);
        } else {
            Path script = DIR.resolve("install.ps1");
            byte[] body = WIN_SCRIPT.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            try (OutputStream os = Files.newOutputStream(script)) {
                os.write(bom);
                os.write(body);
            }
            pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                    "-File", script.toString(), "-Dir", target.toString(), "-New", staged.toString(), "-ShellPid", pid);
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logDir.resolve("update.log").toFile()));
        pb.start();
        phase = "installing";
        return status();
    }

    /** 用系统浏览器打开发布页（只打开 latest.json 中给出的 http/https 地址） */
    void openPage() throws Exception {
        JsonObject j = latest;
        String url = j == null ? null : str(j, "page");
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            url = "https://github.com/yhk180/dubbo-lite/releases/latest";
        }
        if (MAC) {
            new ProcessBuilder("open", url).start();
        } else if (WIN) {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } else {
            new ProcessBuilder("xdg-open", url).start();
        }
    }

    // ------------------------------------------------------------------ 检查

    private JsonObject describe() {
        JsonObject j = latest;
        String v = str(j, "version");
        JsonObject o = new JsonObject();
        o.addProperty("current", Main.VERSION);
        o.addProperty("latest", v);
        o.addProperty("newer", compare(v, Main.VERSION) > 0);
        o.addProperty("skipped", v.equals(settings.skipVersion()));
        o.addProperty("notes", str(j, "notes"));
        o.addProperty("date", str(j, "date"));
        JsonObject asset = asset();
        if (asset != null && asset.has("size")) {
            o.addProperty("size", asset.get("size").getAsLong());
        }
        String reason = installBlocker();
        if (reason == null && asset == null) {
            reason = "这个版本没有提供当前系统的安装包，请到下载页面获取。";
        }
        o.addProperty("canInstall", reason == null);
        o.addProperty("reason", reason);
        return o;
    }

    private JsonObject asset() {
        JsonObject j = latest;
        if (j == null || !j.has("assets") || !j.get("assets").isJsonObject()) {
            return null;
        }
        JsonElement a = j.getAsJsonObject("assets").get(MAC ? "mac" : WIN ? "win" : "none");
        return a != null && a.isJsonObject() ? a.getAsJsonObject() : null;
    }

    /** 不能自动安装的原因；可以安装时为 null */
    private static String installBlocker() {
        if (!MAC && !WIN) {
            return "当前系统不支持自动更新，请到下载页面获取新版本。";
        }
        if (System.getProperty("dubbolite.shellPid") == null) {
            return "当前不是从 Dubbo Lite " + (MAC ? "应用（DubboLite.app）" : "程序（DubboLite.exe）") + "启动的，请到下载页面获取新版本。";
        }
        Path t = target();
        if (t == null) {
            return "没有找到当前应用的安装位置（开发模式？），请到下载页面获取新版本。";
        }
        if (MAC && t.toString().contains("/AppTranslocation/")) {
            return "请先把 Dubbo Lite 拖到“应用程序”文件夹，从那里打开后再更新。";
        }
        if (!writable(t.getParent()) || (MAC && !writable(t))) {
            return "没有写入 " + t.getParent() + " 的权限，无法自动更新，请到下载页面获取新版本。";
        }
        return null;
    }

    /** 当前应用的位置：macOS 为 xxx.app，Windows 为 DubboLite.exe 所在目录 */
    private static Path target() {
        try {
            Path jar = Paths.get(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(jar) || jar.getParent() == null) {
                return null;
            }
            Path appDir = jar.getParent();
            if (MAC) {
                // xxx.app/Contents/Resources/app/dubbo-lite.jar
                Path app = appDir.getParent() == null || appDir.getParent().getParent() == null ? null : appDir.getParent().getParent().getParent();
                return app != null && app.getFileName().toString().endsWith(".app") ? app : null;
            }
            // DubboLite-windows/app/dubbo-lite.jar
            Path dir = appDir.getParent();
            return dir != null && Files.isRegularFile(dir.resolve("DubboLite.exe")) ? dir : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean writable(Path dir) {
        try {
            Path probe = Files.createTempFile(dir, ".dubbo-lite-", ".tmp");
            Files.delete(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 按数字逐段比较版本号（忽略开头的 v）；dev 视为最旧 */
    static int compare(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("[.\\-]");
        String[] y = b.replaceFirst("^[vV]", "").split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? num(x[i]) : 0;
            long q = i < y.length ? num(y[i]) : 0;
            if (p != q) {
                return p < q ? -1 : 1;
            }
        }
        return 0;
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ 下载

    private static HttpURLConnection connect(String url) throws IOException {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            throw new IllegalArgumentException("地址必须是 http:// 或 https:// 开头：" + url);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "DubboLite/" + Main.VERSION);
        return c;
    }

    private static JsonObject fetchJson(String url) throws Exception {
        HttpURLConnection c;
        int code;
        try {
            // GitHub 会把 latest 的跳转缓存几分钟，加随机参数确保刚发布的版本立即可见
            c = connect(url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis());
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Cache-Control", "no-cache");
            code = c.getResponseCode();
        } catch (IOException e) {
            throw new IOException("无法连接更新服务器（" + e.getMessage() + "），请检查网络", e);
        }
        if (code == 404) {
            throw new IOException("GitHub 上还没有发布任何版本（HTTP 404）：" + url);
        }
        if (code != 200) {
            throw new IOException("检查更新失败（HTTP " + code + "）：" + url);
        }
        String body = Api.read(c.getInputStream());
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("更新信息不是有效的 JSON：" + url);
        }
    }

    private Path fetchAndStage(JsonObject asset) throws Exception {
        deleteTree(DIR);
        Files.createDirectories(DIR);
        String url = str(asset, "url");
        HttpURLConnection c = connect(url);
        int code = c.getResponseCode();
        if (code != 200) {
            throw new IOException("下载失败（HTTP " + code + "）：" + url);
        }
        if (total <= 0) {
            total = c.getContentLengthLong();
        }
        Path zip = DIR.resolve(MAC ? "DubboLite-mac.zip" : "DubboLite-windows.zip");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(zip)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) {
            hex.append(String.format("%02x", b));
        }
        if (!hex.toString().equalsIgnoreCase(str(asset, "sha256").trim())) {
            throw new IOException("安装包校验失败（sha256 不一致），请重试");
        }
        Path out = DIR.resolve("new");
        Files.createDirectories(out);
        if (MAC) {
            // ditto 保留可执行权限与签名
            Process p = new ProcessBuilder("/usr/bin/ditto", "-x", "-k", zip.toString(), out.toString())
                    .redirectErrorStream(true).start();
            String msg = Api.read(p.getInputStream());
            if (p.waitFor() != 0) {
                throw new IOException("解压失败：" + msg.trim());
            }
            try (Stream<Path> s = Files.list(out)) {
                Path app = s.filter(x -> x.getFileName().toString().endsWith(".app")).findFirst().orElse(null);
                if (app == null || !Files.isRegularFile(app.resolve("Contents/MacOS/DubboLite"))) {
                    throw new IOException("安装包内容不完整：没有找到 DubboLite.app");
                }
                return app;
            }
        }
        unzip(zip, out);
        for (Path cand : new Path[]{out.resolve("DubboLite-windows"), out}) {
            if (Files.isRegularFile(cand.resolve("DubboLite.exe")) && Files.isRegularFile(cand.resolve("app/dubbo-lite.jar"))) {
                return cand;
            }
        }
        throw new IOException("安装包内容不完整：没有找到 DubboLite.exe");
    }

    private static void unzip(Path zip, Path out) throws IOException {
        Path root = out.toAbsolutePath().normalize();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                Path p = root.resolve(e.getName()).normalize();
                if (!p.startsWith(root)) {
                    throw new IOException("安装包中有非法路径：" + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(p);
                } else {
                    Files.createDirectories(p.getParent());
                    Files.copy(zin, p);
                }
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    // ------------------------------------------------------------------ 安装脚本

    /** 参数：旧 .app、新 .app、外壳进程号 */
    private static final String MAC_SCRIPT = String.join("\n",
            "#!/bin/bash",
            "# Dubbo Lite 自动更新：等应用退出后替换并重新打开；失败时恢复旧版本",
            "trap '' HUP TERM",
            "APP=\"$1\"; NEW=\"$2\"; PID=\"$3\"",
            "echo \"$(date '+%F %T') 更新 $APP\"",
            "for _ in $(seq 1 3000); do kill -0 \"$PID\" 2>/dev/null || break; sleep 0.2; done",
            "if kill -0 \"$PID\" 2>/dev/null; then echo '应用没有退出，放弃更新'; exit 1; fi",
            "sleep 0.5",
            "OLD=\"$(dirname \"$APP\")/.$(basename \"$APP\").old\"",
            "rm -rf \"$OLD\"",
            "if ! mv \"$APP\" \"$OLD\"; then echo '无法移动旧版本'; open \"$APP\"; exit 1; fi",
            "if ditto \"$NEW\" \"$APP\"; then",
            "  rm -rf \"$OLD\" \"$NEW\"",
            "  echo '已安装'",
            "else",
            "  echo '安装失败，恢复旧版本'",
            "  rm -rf \"$APP\"; mv \"$OLD\" \"$APP\"",
            "fi",
            "xattr -dr com.apple.quarantine \"$APP\" 2>/dev/null",
            "open \"$APP\"",
            "");

    /** 参数：程序目录、新版目录、外壳进程号。只替换新版中有的文件（保留用户放在程序目录的 jre 等） */
    private static final String WIN_SCRIPT = String.join("\n",
            "param([string]$Dir, [string]$New, [int]$ShellPid)",
            "\"$(Get-Date -Format s) update $Dir\"",
            "try { Wait-Process -Id $ShellPid -Timeout 600 -ErrorAction SilentlyContinue } catch {}",
            "if (Get-Process -Id $ShellPid -ErrorAction SilentlyContinue) { 'app is still running, abort'; exit 1 }",
            "Start-Sleep -Milliseconds 800",
            "$ErrorActionPreference = 'Stop'",
            "$names = @(Get-ChildItem -LiteralPath $New | ForEach-Object { $_.Name })",
            "$moved = @()",
            "try {",
            "  foreach ($n in $names) {",
            "    $p = Join-Path $Dir $n",
            "    $o = \"$p.old-update\"",
            "    if (Test-Path -LiteralPath $o) { Remove-Item -LiteralPath $o -Recurse -Force }",
            "    if (Test-Path -LiteralPath $p) { Rename-Item -LiteralPath $p -NewName \"$n.old-update\"; $moved += $n }",
            "    Copy-Item -LiteralPath (Join-Path $New $n) -Destination $p -Recurse -Force",
            "  }",
            "  foreach ($n in $moved) { Remove-Item -LiteralPath (Join-Path $Dir \"$n.old-update\") -Recurse -Force -ErrorAction SilentlyContinue }",
            "  'installed'",
            "} catch {",
            "  \"failed: $_, restoring\"",
            "  foreach ($n in $moved) {",
            "    $p = Join-Path $Dir $n",
            "    if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Recurse -Force -ErrorAction SilentlyContinue }",
            "    Rename-Item -LiteralPath \"$p.old-update\" -NewName $n -ErrorAction SilentlyContinue",
            "  }",
            "}",
            "Start-Process -FilePath (Join-Path $Dir 'DubboLite.exe')",
            "");
}
