// 把 SVG 渲染为透明背景的 PNG（qlmanage 生成的缩略图带白色底，图标四角会出现白边）。
// 用法：svg2png <in.svg> <out.png> <边长像素> [shadow]
// NSImage 不支持 SVG 滤镜，shadow 时用 CoreGraphics 补一层与 macOS 图标规范相近的投影。
import AppKit

let args = CommandLine.arguments
guard args.count >= 4, let size = Int(args[3]), let img = NSImage(contentsOfFile: args[1]) else {
    FileHandle.standardError.write("usage: svg2png <in.svg> <out.png> <size> [shadow]\n".data(using: .utf8)!)
    exit(1)
}
let shadow = args.count > 4 && args[4] == "shadow"
guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
                                 samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                                 bytesPerRow: 0, bitsPerPixel: 0),
      let ctx = NSGraphicsContext(bitmapImageRep: rep) else {
    exit(1)
}
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = ctx
let rect = NSRect(x: 0, y: 0, width: size, height: size)
NSColor.clear.set()
rect.fill(using: .copy)
if shadow {
    let k = CGFloat(size) / 1024
    ctx.cgContext.setShadow(offset: CGSize(width: 0, height: -12 * k), blur: 28 * k,
                            color: NSColor.black.withAlphaComponent(0.22).cgColor)
}
img.draw(in: rect, from: .zero, operation: .sourceOver, fraction: 1)
NSGraphicsContext.restoreGraphicsState()
guard let png = rep.representation(using: .png, properties: [:]) else {
    exit(1)
}
try png.write(to: URL(fileURLWithPath: args[2]))
