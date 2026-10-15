Dubbo Lite（Windows 版）
=========================

轻量的 Dubbo / ZMS 请求工具，直接读写 JMeter 的 .jmx 文件。

【运行】
  双击 DubboLite.exe。
  也可以把 .jmx 文件拖到 DubboLite.exe 上，或在 .jmx 上右键“打开方式”选择 DubboLite.exe。

【环境要求】
  1. Java 8 或更高版本（优先使用 Java 8）。查找顺序：
     程序目录下的 jre 文件夹 → JAVA_HOME → C:\Program Files 下常见的 JDK 目录 → PATH 中的 java
     没装 Java 时，可以把任意 JRE 8 解压到本目录，命名为 jre（即 jre\bin\java.exe）。
  2. Microsoft Edge WebView2 运行时（Windows 11 自带；Windows 10 一般已随 Edge 安装）。
     如果没有，程序会自动改用 Edge/Chrome 的独立窗口打开；也可以直接双击 DubboLite-browser.bat。

【快捷键】
  Ctrl+Enter 发送    Ctrl+S 保存    Ctrl+Shift+S 另存为    Ctrl+O 打开    Ctrl+K 搜索

【文件位置】
  设置与最近文件：%USERPROFILE%\.dubbo-lite\
  后台日志：      %USERPROFILE%\.dubbo-lite\logs\backend.log
  脚本扩展 jar：  放到 %USERPROFILE%\.dubbo-lite\lib\ ，或在“设置”中添加目录
