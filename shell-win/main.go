//go:build windows

// Dubbo Lite 的 Windows 外壳：WebView2 原生窗口 + 后台 Java 进程。
// 构建（可在 macOS/Linux 上交叉编译）：shell-win/build.sh
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"

	webview2 "github.com/jchv/go-webview2"
	"golang.org/x/sys/windows"
)

var (
	user32           = windows.NewLazySystemDLL("user32.dll")
	comdlg32         = windows.NewLazySystemDLL("comdlg32.dll")
	ole32            = windows.NewLazySystemDLL("ole32.dll")
	shell32          = windows.NewLazySystemDLL("shell32.dll")
	procSetWindowLongPtr = user32.NewProc("SetWindowLongPtrW")
	procCallWindowProc   = user32.NewProc("CallWindowProcW")
	procPostMessage      = user32.NewProc("PostMessageW")
	procMessageBox       = user32.NewProc("MessageBoxW")
	procGetDpiForSystem  = user32.NewProc("GetDpiForSystem")
	procGetOpenFileName  = comdlg32.NewProc("GetOpenFileNameW")
	procGetSaveFileName  = comdlg32.NewProc("GetSaveFileNameW")
	procCoInitializeEx   = ole32.NewProc("CoInitializeEx")
	procShellExecute     = shell32.NewProc("ShellExecuteW")
)

const (
	wmClose         = 0x0010
	createNoWindow  = 0x08000000
	gwlpWndProc     = ^uintptr(3) // -4
	mbIconError     = 0x10
	ofnOverwrite    = 0x2
	ofnNoChangeDir  = 0x8
	ofnPathMust     = 0x800
	ofnFileMust     = 0x1000
	ofnExplorer     = 0x80000
)

type app struct {
	w          webview2.WebView
	hwnd       uintptr
	exeDir     string
	backend    *exec.Cmd
	stdin      interface{ Close() error }
	ready      atomic.Bool
	allowClose atomic.Bool
	closeAck   chan struct{}
	oldProc    uintptr
	quitting   atomic.Bool
}

func main() {
	runtime.LockOSThread()
	exe, _ := os.Executable()
	a := &app{exeDir: filepath.Dir(exe), closeAck: make(chan struct{}, 4)}

	var openFile string
	for _, arg := range os.Args[1:] {
		if strings.HasSuffix(strings.ToLower(arg), ".jmx") {
			openFile, _ = filepath.Abs(arg)
		}
	}

	java := a.findJava()
	jar := filepath.Join(a.exeDir, "app", "dubbo-lite.jar")

	scale := 1.0
	if procGetDpiForSystem.Find() == nil {
		if dpi, _, _ := procGetDpiForSystem.Call(); dpi > 0 {
			scale = float64(dpi) / 96
		}
	}
	dataDir := filepath.Join(os.Getenv("LOCALAPPDATA"), "DubboLite", "WebView2")
	a.w = webview2.NewWithOptions(webview2.WebViewOptions{
		AutoFocus: true,
		DataPath:  dataDir,
		WindowOptions: webview2.WindowOptions{
			Title:  "Dubbo Lite",
			Width:  uint(1360 * scale),
			Height: uint(860 * scale),
			IconId: 2, // rsrc：清单为 1，图标组为 2
			Center: true,
		},
	})
	if a.w == nil {
		// 没有 WebView2 运行时：退回到浏览器模式（Edge/Chrome 无地址栏窗口），由 Java 进程自己管理生命周期
		if java == "" {
			fatal("没有找到 Java。\n\n请安装 JDK/JRE 8 或更高版本，或把 JRE 解压到程序目录下的 jre 文件夹。")
		}
		args := []string{"-Xmx512m", "-Dfile.encoding=UTF-8", "-jar", jar}
		if openFile != "" {
			args = append(args, openFile)
		}
		cmd := exec.Command(javaw(java), args...)
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
		if err := cmd.Start(); err != nil {
			fatal("启动失败：" + err.Error())
		}
		return
	}
	defer a.w.Destroy()
	a.hwnd = uintptr(a.w.Window())
	a.subclass()
	a.w.SetSize(900, 560, webview2.HintMin)
	_ = a.w.Bind("dlNative", a.native)
	a.w.SetHtml(splash("正在启动…", false))

	if java == "" {
		a.w.SetHtml(splash("没有找到 Java。<br>请安装 JDK/JRE 8 或更高版本，或把 JRE 解压到程序目录下的 <b>jre</b> 文件夹后重新打开。", true))
	} else if _, err := os.Stat(jar); err != nil {
		a.w.SetHtml(splash("缺少 app\\dubbo-lite.jar，请完整解压后再运行。", true))
	} else {
		go a.startBackend(java, jar, openFile)
	}

	a.w.Run()
	a.quitting.Store(true)
	a.stopBackend()
}

// ------------------------------------------------------------------ backend

func (a *app) startBackend(java, jar, openFile string) {
	home, _ := os.UserHomeDir()
	logDir := filepath.Join(home, ".dubbo-lite", "logs")
	_ = os.MkdirAll(logDir, 0o755)
	logFile, _ := os.Create(filepath.Join(logDir, "backend.log"))

	cmd := exec.Command(java, "-Xmx512m", "-Dfile.encoding=UTF-8", "-jar", jar, "--shell")
	cmd.Dir = a.exeDir
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	if logFile != nil {
		cmd.Stderr = logFile
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		a.fail("启动失败：" + err.Error())
		return
	}
	stdin, err := cmd.StdinPipe() // 外壳退出时管道关闭，Java 进程随之退出
	if err != nil {
		a.fail("启动失败：" + err.Error())
		return
	}
	if err := cmd.Start(); err != nil {
		a.fail("无法启动 Java（" + java + "）：" + err.Error())
		return
	}
	a.backend = cmd
	a.stdin = stdin

	go func() {
		time.Sleep(60 * time.Second)
		if !a.ready.Load() && !a.quitting.Load() {
			a.fail("后台启动超时。日志：%USERPROFILE%\\.dubbo-lite\\logs\\backend.log")
		}
	}()

	sc := bufio.NewScanner(stdout)
	for sc.Scan() {
		line := sc.Text()
		if logFile != nil {
			_, _ = logFile.WriteString(line + "\r\n")
		}
		if !a.ready.Load() && strings.HasPrefix(line, "DUBBO_LITE_URL ") {
			target := strings.TrimSpace(strings.TrimPrefix(line, "DUBBO_LITE_URL "))
			if openFile != "" {
				target += "&open=" + url.QueryEscape(openFile)
			}
			a.ready.Store(true)
			a.w.Dispatch(func() { a.w.Navigate(target) })
		}
	}
	err = cmd.Wait()
	if !a.quitting.Load() {
		a.ready.Store(false)
		a.fail(fmt.Sprintf("后台进程已退出（%v）。日志：%%USERPROFILE%%\\.dubbo-lite\\logs\\backend.log", err))
	}
}

func (a *app) stopBackend() {
	if a.stdin != nil {
		_ = a.stdin.Close()
	}
	if a.backend != nil && a.backend.Process != nil {
		done := make(chan struct{})
		go func() { _, _ = a.backend.Process.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			_ = a.backend.Process.Kill()
		}
	}
}

func (a *app) fail(msg string) {
	a.w.Dispatch(func() { a.w.SetHtml(splash(msg, true)) })
}

// findJava 依次查找：程序目录 jre → JAVA_HOME → 常见安装目录（优先 Java 8）→ PATH
func (a *app) findJava() string {
	var cands []string
	add := func(p string) {
		if p == "" {
			return
		}
		if st, err := os.Stat(p); err == nil && !st.IsDir() {
			cands = append(cands, p)
		}
	}
	add(filepath.Join(a.exeDir, "jre", "bin", "java.exe"))
	if jh := os.Getenv("JAVA_HOME"); jh != "" {
		add(filepath.Join(jh, "bin", "java.exe"))
	}
	var scanned []string
	for _, root := range []string{os.Getenv("ProgramFiles"), os.Getenv("ProgramFiles(x86)"), os.Getenv("ProgramW6432")} {
		if root == "" {
			continue
		}
		for _, vendor := range []string{"Java", "Amazon Corretto", "Eclipse Adoptium", "Eclipse Foundation", "AdoptOpenJDK",
			"Zulu", "Microsoft", "BellSoft", "Semeru", "OpenJDK", "RedHat"} {
			dirs, _ := filepath.Glob(filepath.Join(root, vendor, "*", "bin", "java.exe"))
			scanned = append(scanned, dirs...)
		}
	}
	// 优先 Java 8，其次版本号大的
	sort.SliceStable(scanned, func(i, j int) bool {
		return is8(scanned[i]) && !is8(scanned[j]) || (is8(scanned[i]) == is8(scanned[j]) && scanned[i] > scanned[j])
	})
	for _, p := range scanned {
		add(p)
	}
	if p, err := exec.LookPath("java.exe"); err == nil {
		add(p)
	}
	if len(cands) == 0 {
		return ""
	}
	return cands[0]
}

func is8(p string) bool {
	l := strings.ToLower(p)
	return strings.Contains(l, "1.8") || strings.Contains(l, "jdk8") || strings.Contains(l, "jre8") ||
		strings.Contains(l, "-8.") || strings.Contains(l, "jdk-8") || strings.Contains(l, "jre-8") || strings.Contains(l, "corretto-8")
}

func javaw(java string) string {
	w := filepath.Join(filepath.Dir(java), "javaw.exe")
	if _, err := os.Stat(w); err == nil {
		return w
	}
	return java
}

// ------------------------------------------------------------------ JS bridge

type nativeMsg struct {
	ID    int    `json:"id"`
	Type  string `json:"type"`
	Name  string `json:"name"`
	Dir   string `json:"dir"`
	Title string `json:"title"`
	Path  string `json:"path"`
}

// native 由页面中的 window.dlNative(JSON) 调用（在 UI 线程上）
func (a *app) native(raw string) {
	var m nativeMsg
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		return
	}
	switch m.Type {
	case "title":
		a.w.SetTitle(m.Title)
	case "closeAck":
		select {
		case a.closeAck <- struct{}{}:
		default:
		}
	case "close":
		a.allowClose.Store(true)
		_, _, _ = procPostMessage.Call(a.hwnd, wmClose, 0, 0)
	case "reveal":
		args, _ := windows.UTF16PtrFromString("/select,\"" + m.Path + "\"")
		verb, _ := windows.UTF16PtrFromString("open")
		file, _ := windows.UTF16PtrFromString("explorer.exe")
		_, _, _ = procShellExecute.Call(0, uintptr(unsafe.Pointer(verb)), uintptr(unsafe.Pointer(file)), uintptr(unsafe.Pointer(args)), 0, 1)
	case "pickOpen", "pickSave":
		go func() {
			runtime.LockOSThread()
			defer runtime.UnlockOSThread()
			_, _, _ = procCoInitializeEx.Call(0, 2) // COINIT_APARTMENTTHREADED
			p := a.fileDialog(m.Type == "pickSave", m.Name, m.Dir)
			reply := "null"
			if p != "" {
				b, _ := json.Marshal(p)
				reply = string(b)
			}
			a.w.Dispatch(func() { a.w.Eval(fmt.Sprintf("window.__nativeReply(%d, %s)", m.ID, reply)) })
		}()
	}
}

// 子类化窗口过程：关闭窗口前先让页面检查未保存的修改
func (a *app) subclass() {
	cb := windows.NewCallback(func(hwnd, msg, wp, lp uintptr) uintptr {
		if msg == wmClose && !a.allowClose.Load() && a.ready.Load() {
			for len(a.closeAck) > 0 {
				<-a.closeAck
			}
			a.w.Eval("window.__requestClose ? window.__requestClose() : window.dlNative(JSON.stringify({type:'close'}))")
			go func() {
				select {
				case <-a.closeAck:
				case <-time.After(2 * time.Second): // 页面无响应时直接关闭
					a.w.Dispatch(func() {
						a.allowClose.Store(true)
						_, _, _ = procPostMessage.Call(a.hwnd, wmClose, 0, 0)
					})
				}
			}()
			return 0
		}
		r, _, _ := procCallWindowProc.Call(a.oldProc, hwnd, msg, wp, lp)
		return r
	})
	a.oldProc, _, _ = procSetWindowLongPtr.Call(a.hwnd, gwlpWndProc, cb)
}

type openFileName struct {
	lStructSize       uint32
	hwndOwner         uintptr
	hInstance         uintptr
	lpstrFilter       *uint16
	lpstrCustomFilter *uint16
	nMaxCustFilter    uint32
	nFilterIndex      uint32
	lpstrFile         *uint16
	nMaxFile          uint32
	lpstrFileTitle    *uint16
	nMaxFileTitle     uint32
	lpstrInitialDir   *uint16
	lpstrTitle        *uint16
	flags             uint32
	nFileOffset       uint16
	nFileExtension    uint16
	lpstrDefExt       *uint16
	lCustData         uintptr
	lpfnHook          uintptr
	lpTemplateName    *uint16
	pvReserved        uintptr
	dwReserved        uint32
	flagsEx           uint32
}

func utf16z(s string) []uint16 {
	return append(utf16.Encode([]rune(s)), 0)
}

func (a *app) fileDialog(save bool, name, dir string) string {
	filter := utf16.Encode([]rune("JMeter 测试计划 (*.jmx)\x00*.jmx\x00所有文件 (*.*)\x00*.*\x00\x00"))
	buf := make([]uint16, 32768)
	if save && name != "" {
		copy(buf, utf16.Encode([]rune(name)))
	}
	var title []uint16
	if save {
		title = utf16z("保存测试计划")
	} else {
		title = utf16z("打开 JMeter 测试计划 (.jmx)")
	}
	ofn := openFileName{
		hwndOwner:    a.hwnd,
		lpstrFilter:  &filter[0],
		nFilterIndex: 1,
		lpstrFile:    &buf[0],
		nMaxFile:     uint32(len(buf)),
		lpstrTitle:   &title[0],
	}
	ofn.lStructSize = uint32(unsafe.Sizeof(ofn))
	if dir != "" {
		d := utf16z(strings.ReplaceAll(dir, "/", "\\"))
		ofn.lpstrInitialDir = &d[0]
	}
	ext := utf16z("jmx")
	ofn.lpstrDefExt = &ext[0]
	var r uintptr
	if save {
		ofn.flags = ofnExplorer | ofnOverwrite | ofnPathMust | ofnNoChangeDir
		r, _, _ = procGetSaveFileName.Call(uintptr(unsafe.Pointer(&ofn)))
	} else {
		ofn.flags = ofnExplorer | ofnFileMust | ofnPathMust | ofnNoChangeDir
		r, _, _ = procGetOpenFileName.Call(uintptr(unsafe.Pointer(&ofn)))
	}
	if r == 0 {
		return ""
	}
	return windows.UTF16ToString(buf)
}

// ------------------------------------------------------------------ helpers

func fatal(msg string) {
	t, _ := windows.UTF16PtrFromString(msg)
	c, _ := windows.UTF16PtrFromString("Dubbo Lite")
	_, _, _ = procMessageBox.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), mbIconError)
	os.Exit(1)
}

func splash(text string, isErr bool) string {
	cls, spin := "", `<div class="s"></div>`
	if isErr {
		cls, spin = "e", ""
	}
	return `<!doctype html><meta charset="utf-8"><style>
:root{color-scheme:light dark}
body{margin:0;height:100vh;display:grid;place-items:center;font:14px "Segoe UI","Microsoft YaHei",sans-serif;color:#6b6b76;background:#fff}
@media (prefers-color-scheme:dark){body{background:#19191c;color:#9b9ba6}}
.b{text-align:center}.l{width:56px;height:56px;border-radius:14px;margin:0 auto 18px;background:linear-gradient(135deg,#6366f1,#9333ea);box-shadow:0 10px 30px rgba(99,102,241,.35)}
.s{width:16px;height:16px;border:2px solid rgba(99,102,241,.2);border-top-color:#6366f1;border-radius:50%;animation:r .7s linear infinite;margin:14px auto 0}
@keyframes r{to{transform:rotate(360deg)}}.e{color:#e5484d;max-width:480px;line-height:1.7}
</style><div class="b"><div class="l"></div><div class="` + cls + `">` + text + `</div>` + spin + `</div>`
}
