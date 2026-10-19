// Dubbo Lite 的 macOS 原生外壳：一个 WKWebView 窗口 + 后台 Java 进程。
// 构建：shell/build-app.sh（需要 Xcode 命令行工具）
import Cocoa
import WebKit
import UniformTypeIdentifiers

final class DropWebView: WKWebView {
    var onOpenFiles: (([String]) -> Void)?

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        let urls = (sender.draggingPasteboard.readObjects(forClasses: [NSURL.self],
                                                          options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
        let jmx = urls.filter { $0.pathExtension.lowercased() == "jmx" }.map { $0.path }
        if !jmx.isEmpty {
            onOpenFiles?(jmx)
            evaluateJavaScript("document.body.classList.remove('dropping')", completionHandler: nil)
            return true
        }
        return super.performDragOperation(sender)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
    var window: NSWindow!
    var web: DropWebView!
    var backend: Process?
    let stdinPipe = Pipe()
    var ready = false
    var pendingFiles: [String] = []
    var stdoutBuffer = ""
    var closeConfirmed = false
    var titleObservation: NSKeyValueObservation?

    // MARK: - lifecycle

    func applicationDidFinishLaunching(_ notification: Notification) {
        buildMenu()
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1360, height: 860),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable],
                          backing: .buffered, defer: false)
        window.title = "Dubbo Lite"
        window.minSize = NSSize(width: 900, height: 560)
        window.center()
        window.setFrameAutosaveName("DubboLiteMainWindow")
        window.delegate = self
        window.tabbingMode = .disallowed

        let config = WKWebViewConfiguration()
        config.userContentController.add(self, name: "dubbolite")
        web = DropWebView(frame: .zero, configuration: config)
        web.navigationDelegate = self
        web.uiDelegate = self
        web.allowsBackForwardNavigationGestures = false
        web.setValue(false, forKey: "drawsBackground")
        web.onOpenFiles = { [weak self] files in self?.open(files) }
        window.contentView = web
        titleObservation = web.observe(\.title, options: [.new]) { [weak self] w, _ in
            if let t = w.title, !t.isEmpty { self?.window.title = t }
        }
        web.loadHTMLString(Self.splash("正在启动…"), baseURL: nil)
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        startBackend()
    }

    func application(_ sender: NSApplication, openFiles filenames: [String]) {
        open(filenames)
        sender.reply(toOpenOrPrint: .success)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if closeConfirmed || !ready { return .terminateNow }
        confirmDiscard { ok in
            self.closeConfirmed = ok
            sender.reply(toApplicationShouldTerminate: ok)
        }
        return .terminateLater
    }

    func applicationWillTerminate(_ notification: Notification) {
        try? stdinPipe.fileHandleForWriting.close()
        backend?.terminate()
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        if closeConfirmed || !ready { return true }
        confirmDiscard { ok in
            if ok {
                self.closeConfirmed = true
                self.window.close()
            }
        }
        return false
    }

    private func confirmDiscard(_ done: @escaping (Bool) -> Void) {
        web.evaluateJavaScript("window.__hasDirty && window.__hasDirty()") { result, _ in
            guard (result as? Bool) == true else { done(true); return }
            let alert = NSAlert()
            alert.messageText = "有未保存的修改"
            alert.informativeText = "退出后，尚未保存的修改将会丢失。"
            alert.addButton(withTitle: "取消")
            alert.addButton(withTitle: "不保存，直接退出")
            alert.beginSheetModal(for: self.window) { resp in done(resp == .alertSecondButtonReturn) }
        }
    }

    // MARK: - backend

    private func javaHome() -> String? {
        for args in [["-v", "1.8"], ["-v", "17"], []] {
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/libexec/java_home")
            p.arguments = args
            let out = Pipe()
            p.standardOutput = out
            p.standardError = Pipe()
            do { try p.run() } catch { continue }
            p.waitUntilExit()
            let s = String(data: out.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if p.terminationStatus == 0 && !s.isEmpty { return s }
        }
        return nil
    }

    private func startBackend() {
        guard let res = Bundle.main.resourcePath else { return }
        let jar = res + "/app/dubbo-lite.jar"
        guard let home = javaHome() else {
            web.loadHTMLString(Self.splash("没有找到 Java。请安装 JDK 8 或更高版本后重新打开。", error: true), baseURL: nil)
            return
        }
        let logDir = NSHomeDirectory() + "/.dubbo-lite/logs"
        try? FileManager.default.createDirectory(atPath: logDir, withIntermediateDirectories: true)
        let logPath = logDir + "/backend.log"
        FileManager.default.createFile(atPath: logPath, contents: nil)
        let log = FileHandle(forWritingAtPath: logPath)

        let p = Process()
        p.executableURL = URL(fileURLWithPath: home + "/bin/java")
        // shellPid：自动更新时，安装脚本等这个进程退出后再替换应用
        p.arguments = ["-Xmx512m", "-Dapple.awt.UIElement=true", "-Dfile.encoding=UTF-8",
                       "-Ddubbolite.shellPid=\(ProcessInfo.processInfo.processIdentifier)", "-jar", jar, "--shell"]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = log ?? FileHandle.nullDevice
        p.standardInput = stdinPipe
        out.fileHandleForReading.readabilityHandler = { [weak self] h in
            let data = h.availableData
            guard let self = self, !data.isEmpty, let s = String(data: data, encoding: .utf8) else { return }
            log?.write(data)
            DispatchQueue.main.async { self.consume(s) }
        }
        p.terminationHandler = { [weak self] proc in
            DispatchQueue.main.async {
                guard let self = self, !self.closeConfirmed else { return }
                self.ready = false
                self.web.loadHTMLString(Self.splash("后台进程已退出（code \(proc.terminationStatus)）。日志：~/.dubbo-lite/logs/backend.log", error: true), baseURL: nil)
            }
        }
        do {
            try p.run()
            backend = p
        } catch {
            web.loadHTMLString(Self.splash("启动失败：\(error.localizedDescription)", error: true), baseURL: nil)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 40) { [weak self] in
            guard let self = self, !self.ready, self.backend?.isRunning == true else { return }
            self.web.loadHTMLString(Self.splash("后台启动超时。日志：~/.dubbo-lite/logs/backend.log", error: true), baseURL: nil)
        }
    }

    private func consume(_ chunk: String) {
        stdoutBuffer += chunk
        while let nl = stdoutBuffer.firstIndex(of: "\n") {
            let line = String(stdoutBuffer[..<nl])
            stdoutBuffer = String(stdoutBuffer[stdoutBuffer.index(after: nl)...])
            if !ready, line.hasPrefix("DUBBO_LITE_URL "), var comps = URLComponents(string: String(line.dropFirst(15))) {
                ready = true
                if let first = pendingFiles.first {
                    comps.queryItems = (comps.queryItems ?? []) + [URLQueryItem(name: "open", value: first)]
                    pendingFiles.removeFirst()
                }
                if let url = comps.url { web.load(URLRequest(url: url)) }
            }
        }
    }

    private func open(_ files: [String]) {
        guard ready else { pendingFiles += files; return }
        for f in files { js("openPath(\(Self.jsString(f)))") }
    }

    // MARK: - bridge

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let type = body["type"] as? String,
              let id = body["id"] as? Int else { return }
        let dir = (body["dir"] as? String) ?? ""
        switch type {
        case "pickOpen":
            let panel = NSOpenPanel()
            panel.message = "打开 JMeter 测试计划 (.jmx)"
            panel.allowedContentTypes = [UTType(filenameExtension: "jmx") ?? .xml]
            panel.allowsMultipleSelection = false
            if !dir.isEmpty { panel.directoryURL = URL(fileURLWithPath: dir) }
            panel.beginSheetModal(for: window) { r in self.reply(id, r == .OK ? panel.url?.path : nil) }
        case "pickSave":
            let panel = NSSavePanel()
            panel.allowedContentTypes = [UTType(filenameExtension: "jmx") ?? .xml]
            panel.nameFieldStringValue = (body["name"] as? String) ?? "未命名.jmx"
            if !dir.isEmpty { panel.directoryURL = URL(fileURLWithPath: dir) }
            panel.beginSheetModal(for: window) { r in self.reply(id, r == .OK ? panel.url?.path : nil) }
        case "theme":
            // 标题栏等原生部分与页面主题一致；system 时跟随系统
            let t = body["theme"] as? String
            NSApp.appearance = t == "dark" ? NSAppearance(named: .darkAqua) : t == "light" ? NSAppearance(named: .aqua) : nil
            reply(id, nil)
        case "quit":
            // 页面已处理未保存的修改（如安装更新前），直接退出
            closeConfirmed = true
            NSApp.terminate(nil)
        default:
            reply(id, nil)
        }
    }

    private func reply(_ id: Int, _ path: String?) {
        js("window.__nativeReply(\(id), \(path.map(Self.jsString) ?? "null"))")
    }

    private func js(_ code: String) {
        web.evaluateJavaScript(code, completionHandler: nil)
    }

    @objc func command(_ sender: NSMenuItem) {
        guard let cmd = sender.representedObject as? String else { return }
        js("window.__command && window.__command('\(cmd)')")
    }

    // 外部链接交给系统浏览器
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = action.request.url, action.navigationType == .linkActivated,
           url.host != "127.0.0.1" && url.host != "localhost" {
            NSWorkspace.shared.open(url)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let a = NSAlert()
        a.messageText = message
        a.beginSheetModal(for: window) { _ in completionHandler() }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let a = NSAlert()
        a.messageText = message
        a.addButton(withTitle: "确定")
        a.addButton(withTitle: "取消")
        a.beginSheetModal(for: window) { r in completionHandler(r == .alertFirstButtonReturn) }
    }

    // MARK: - menu

    private func item(_ title: String, _ cmd: String, _ key: String, _ mods: NSEvent.ModifierFlags = .command) -> NSMenuItem {
        let i = NSMenuItem(title: title, action: #selector(command(_:)), keyEquivalent: key)
        i.keyEquivalentModifierMask = mods
        i.representedObject = cmd
        i.target = self
        return i
    }

    private func buildMenu() {
        let main = NSMenu()
        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "关于 Dubbo Lite", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        appMenu.addItem(.separator())
        appMenu.addItem(item("检查更新…", "checkUpdate", ""))
        appMenu.addItem(item("设置…", "settings", ","))
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "隐藏 Dubbo Lite", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        appMenu.addItem(withTitle: "退出 Dubbo Lite", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        main.addItem(appItem)

        let fileItem = NSMenuItem()
        let file = NSMenu(title: "文件")
        file.addItem(item("新建测试计划", "new", "n"))
        file.addItem(item("打开…", "open", "o"))
        file.addItem(.separator())
        file.addItem(item("保存", "save", "s"))
        file.addItem(item("另存为…", "saveAs", "s", [.command, .shift]))
        fileItem.submenu = file
        main.addItem(fileItem)

        let editItem = NSMenuItem()
        let edit = NSMenu(title: "编辑")
        edit.addItem(withTitle: "撤销", action: Selector(("undo:")), keyEquivalent: "z")
        let redo = edit.addItem(withTitle: "重做", action: Selector(("redo:")), keyEquivalent: "z")
        redo.keyEquivalentModifierMask = [.command, .shift]
        edit.addItem(.separator())
        edit.addItem(withTitle: "剪切", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "拷贝", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "粘贴", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "全选", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        edit.addItem(.separator())
        edit.addItem(item("搜索请求", "find", "k"))
        editItem.submenu = edit
        main.addItem(editItem)

        let reqItem = NSMenuItem()
        let req = NSMenu(title: "请求")
        req.addItem(item("发送", "send", "\r"))
        req.addItem(item("新建请求", "newRequest", "n", [.command, .shift]))
        reqItem.submenu = req
        main.addItem(reqItem)

        let winItem = NSMenuItem()
        let win = NSMenu(title: "窗口")
        win.addItem(withTitle: "最小化", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        win.addItem(withTitle: "缩放", action: #selector(NSWindow.performZoom(_:)), keyEquivalent: "")
        winItem.submenu = win
        main.addItem(winItem)
        NSApp.mainMenu = main
        NSApp.windowsMenu = win
    }

    // MARK: - helpers

    static func jsString(_ s: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: [s])
        let arr = data.flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
        return String(arr.dropFirst().dropLast())
    }

    static func splash(_ text: String, error: Bool = false) -> String {
        """
        <!doctype html><meta charset="utf-8"><style>
        :root{color-scheme:light dark}
        body{margin:0;height:100vh;display:grid;place-items:center;font:13px -apple-system,"PingFang SC";color:#6b6b76;background:#fff}
        @media (prefers-color-scheme:dark){body{background:#19191c;color:#9b9ba6}}
        .b{text-align:center}.l{width:56px;height:56px;border-radius:14px;margin:0 auto 18px;background:linear-gradient(135deg,#6366f1,#8b5cf6);box-shadow:0 10px 30px rgba(99,102,241,.35)}
        .s{width:16px;height:16px;border:2px solid rgba(99,102,241,.2);border-top-color:#6366f1;border-radius:50%;animation:r .7s linear infinite;margin:14px auto 0}
        @keyframes r{to{transform:rotate(360deg)}} .e{color:#e5484d;max-width:460px;line-height:1.6}
        </style><div class="b"><div class="l"></div><div class="\(error ? "e" : "")">\(text)</div>\(error ? "" : "<div class=s></div>")</div>
        """
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
