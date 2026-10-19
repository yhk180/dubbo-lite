package dubbolite;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** ~/.dubbo-lite/settings.json */
final class Settings {

    static final Path HOME = Paths.get(System.getProperty("user.home"), ".dubbo-lite");
    private static final Path FILE = HOME.resolve("settings.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static final class Data {
        List<String> recent = new ArrayList<>();
        /** 额外的 jar 目录或 jar 文件：脚本中用到的公司工具类、groovy 等 */
        List<String> extPaths = new ArrayList<>();
        /** 界面主题：system（跟随系统）/ light / dark */
        String theme = "system";
        /** 检查更新的地址（latest.json）；为空时使用默认的 GitHub Releases 地址 */
        String updateUrl;
        /** 启动时自动检查更新 */
        boolean autoUpdate = true;
        /** 用户选择跳过的版本：自动检查时不再提示 */
        String skipVersion;
    }

    private Data data = new Data();
    private volatile ClassLoader loader;

    synchronized void load() {
        try {
            if (Files.isRegularFile(FILE)) {
                Data d = GSON.fromJson(new String(Files.readAllBytes(FILE), StandardCharsets.UTF_8), Data.class);
                if (d != null) {
                    if (d.recent == null) {
                        d.recent = new ArrayList<>();
                    }
                    if (d.extPaths == null) {
                        d.extPaths = new ArrayList<>();
                    }
                    data = d;
                }
            }
        } catch (Exception e) {
            System.err.println("读取设置失败: " + e);
        }
        loader = null;
    }

    synchronized void save() {
        try {
            Files.createDirectories(HOME);
            Files.write(FILE, GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("保存设置失败: " + e);
        }
    }

    synchronized List<String> recent() {
        data.recent.removeIf(p -> !Files.isRegularFile(Paths.get(p)));
        return new ArrayList<>(data.recent);
    }

    synchronized void touchRecent(Path p) {
        String s = p.toAbsolutePath().toString();
        data.recent.remove(s);
        data.recent.add(0, s);
        while (data.recent.size() > 12) {
            data.recent.remove(data.recent.size() - 1);
        }
        save();
    }

    synchronized void removeRecent(String p) {
        data.recent.remove(p);
        save();
    }

    synchronized String theme() {
        return "light".equals(data.theme) || "dark".equals(data.theme) ? data.theme : "system";
    }

    synchronized void setTheme(String theme) {
        data.theme = theme;
        save();
    }

    synchronized String updateUrl() {
        return data.updateUrl == null || data.updateUrl.trim().isEmpty() ? Updater.DEFAULT_URL : data.updateUrl.trim();
    }

    synchronized void setUpdateUrl(String url) {
        data.updateUrl = url == null || url.trim().isEmpty() || url.trim().equals(Updater.DEFAULT_URL) ? null : url.trim();
        save();
    }

    synchronized boolean autoUpdate() {
        return data.autoUpdate;
    }

    synchronized void setAutoUpdate(boolean on) {
        data.autoUpdate = on;
        save();
    }

    synchronized String skipVersion() {
        return data.skipVersion;
    }

    synchronized void setSkipVersion(String v) {
        data.skipVersion = v;
        save();
    }

    synchronized List<String> extPaths() {
        return new ArrayList<>(data.extPaths);
    }

    synchronized void setExtPaths(List<String> paths) {
        data.extPaths = new ArrayList<>(paths);
        loader = null;
        save();
    }

    /** 脚本使用的类加载器：应用自身 + ~/.dubbo-lite/lib + 设置中的扩展路径 */
    ClassLoader scriptLoader() {
        ClassLoader l = loader;
        if (l != null) {
            return l;
        }
        synchronized (this) {
            if (loader == null) {
                List<URL> urls = new ArrayList<>();
                List<String> paths = new ArrayList<>(data.extPaths);
                paths.add(0, HOME.resolve("lib").toString());
                for (String p : paths) {
                    File f = new File(p.trim());
                    try {
                        if (f.isDirectory()) {
                            File[] jars = f.listFiles((dir, name) -> name.endsWith(".jar"));
                            if (jars != null) {
                                for (File j : jars) {
                                    urls.add(j.toURI().toURL());
                                }
                            }
                        } else if (f.isFile() && f.getName().endsWith(".jar")) {
                            urls.add(f.toURI().toURL());
                        }
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
                loader = new URLClassLoader(urls.toArray(new URL[0]), Settings.class.getClassLoader());
            }
            return loader;
        }
    }
}
