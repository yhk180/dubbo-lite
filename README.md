# Dubbo Lite

轻量、专注的 Dubbo 请求工具：只做一件事——**打开 JMeter 的 .jmx，编辑并发送 Dubbo 请求，看清响应**。

- 直接读写 JMeter `.jmx`：未改动的部分逐字节保持原样，保存后 JMeter 5.x 可正常打开
- 兼容 `com.yajad.jmeter.sampler.DubboSampler`（参数格式、Dubbo Defaults 继承规则、泛化调用方式与插件一致），也能读取/发送 `ningyu` Dubbo 插件的请求
- 支持 `${变量}`、常用 JMeter 函数（`__Random`/`__time`/`__timeShift`/`__UUID`/`__RandomString`/`__counter`/`__digest` 等）、用户定义变量、CSV 数据文件、BeanShell/JSR223 前置与后置脚本（`vars`/`props`/`log`/`prev`/`sampler`/`ctx`，`RpcContext.setAttachment` 可用）
- 支持 dandelion 插件的 JavaSampler Dubbo 请求（`mm.jmeter.duser.SgrDubbo` / `DubboApp`，含 appidKey 签名）
- 支持 ZMS 消息（`com.zto.zmsjmeter.ZMSSampler`，syncSend / sendOneWay）；ZMS 客户端放在 `lib-zms/`，用独立类加载器加载，不与 Dubbo 依赖冲突
- 不做：HTTP、线程组并发、压测、断言、监听器（这些元素在树中只读显示或隐藏，保存时原样保留）

## 使用

| 方式 | 命令 |
| --- | --- |
| macOS 原生应用 | 打开 `dist/DubboLite.app`（可拖到“应用程序”；支持双击/拖入 .jmx） |
| Windows 原生应用 | 解压 `dist/DubboLite-windows.zip`，双击 `DubboLite.exe`（WebView2 窗口；没有 WebView2 时自动改用 Edge 独立窗口） |
| 任意平台 | `dist/dubbo-lite/dubbo-lite.sh [文件.jmx]` / `dubbo-lite.bat`（以 Chrome/Edge 无地址栏窗口打开） |

需要本机安装 Java 8+（优先使用 Java 8）。快捷键：`⌘↵` 发送、`⌘S` 保存、`⌘O` 打开、`⌘K` 搜索。

## 与 JMeter 的差异（有意为之）

- 发送单个请求，不跑线程组；会话变量在多次发送之间保留（后置脚本提取的值可被后续请求使用），可在“变量”页清空
- `连接数(serviceConnections)` 只对压测有意义，这里使用 Dubbo 共享连接（JMX 中的值保持不变）
- 调用失败时标记为失败并给出简明原因（yajad 插件会把异常也标记为成功）
- 未找到 Groovy 引擎时，JSR223 脚本按 BeanShell 兼容方式执行；脚本需要的第三方 jar 放到 `~/.dubbo-lite/lib` 或在“设置”里添加目录

## 构建

```bash
./build.sh        # dist/dubbo-lite（jar + lib + 启动脚本）
./build.sh app    # 另外生成 dist/DubboLite.app（需要 Xcode 命令行工具）
./build.sh win    # 另外生成 dist/DubboLite-windows.zip（需要 Go，macOS 上可直接交叉编译）
./build.sh all    # 全部
```

依赖都在 `lib/`（取自本机 JMeter 与 Maven 仓库），离线即可构建。

## 结构

```
src/main/java/dubbolite/
  Main.java            本机 HTTP 服务(127.0.0.1 + 随机令牌) / 窗口启动 / 单实例
  Api.java             前端接口
  jmx/Xml.java         保真 XML 解析与写回（与 JMeter/XStream 相同的转义）
  jmx/JmxDoc.java      测试元素树、增删改移、Dubbo 字段映射
  run/Runner.java      发送流程：变量 → CSV → Defaults → 前置脚本 → 函数替换 → 泛化调用 → 后置脚本
  run/DubboClient.java 泛化调用（dubbo 2.6.8，配置与 yajad 插件一致）
  run/Functions.java   JMeter 函数
  run/Scripts.java     BeanShell / JSR223
src/main/resources/web 界面（原生 HTML/CSS/JS，无第三方依赖）
shell/                 macOS 原生外壳（Swift + WKWebView）
shell-win/             Windows 原生外壳（Go + WebView2，原生文件对话框、关闭前未保存提示）
```
