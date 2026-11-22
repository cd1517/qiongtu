import AppKit
import CoreGraphics

let N = 1024
let space = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: N, height: N, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
func color(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> CGColor { CGColor(colorSpace: space, components: [r, g, b, a])! }

// 背景：暖橙到莓粉的对角渐变
let bg = CGGradient(colorsSpace: space, colors: [color(1.0, 0.74, 0.30), color(1.0, 0.50, 0.36), color(0.96, 0.30, 0.52)] as CFArray,
                    locations: [0, 0.55, 1])!
ctx.drawLinearGradient(bg, start: CGPoint(x: 0, y: CGFloat(N)), end: CGPoint(x: CGFloat(N), y: 0), options: [])
// 左上角一团柔和的高光
let glow = CGGradient(colorsSpace: space, colors: [color(1, 1, 1, 0.28), color(1, 1, 1, 0)] as CFArray, locations: [0, 1])!
ctx.drawRadialGradient(glow, startCenter: CGPoint(x: 260, y: 820), startRadius: 0, endCenter: CGPoint(x: 260, y: 820), endRadius: 620, options: [])

func card(center: CGPoint, size: CGFloat, angle: CGFloat, draw: (CGRect) -> Void) {
    ctx.saveGState()
    ctx.translateBy(x: center.x, y: center.y)
    ctx.rotate(by: angle * .pi / 180)
    draw(CGRect(x: -size / 2, y: -size / 2, width: size, height: size))
    ctx.restoreGState()
}

// 后面那张"图"：半透明白
card(center: CGPoint(x: 430, y: 560), size: 470, angle: 11) { r in
    ctx.setFillColor(color(1, 1, 1, 0.45))
    ctx.addPath(CGPath(roundedRect: r, cornerWidth: 96, cornerHeight: 96, transform: nil))
    ctx.fillPath()
}
// 前面那张"图"：白底，里面是一幅小风景
card(center: CGPoint(x: 540, y: 455), size: 500, angle: -7) { r in
    ctx.setShadow(offset: CGSize(width: 0, height: -18), blur: 50, color: color(0.55, 0.10, 0.20, 0.35))
    ctx.setFillColor(color(1, 1, 1))
    let outer = CGPath(roundedRect: r, cornerWidth: 104, cornerHeight: 104, transform: nil)
    ctx.addPath(outer); ctx.fillPath()
    ctx.setShadow(offset: .zero, blur: 0, color: nil)
    // 画面区域
    let inner = r.insetBy(dx: 44, dy: 44)
    let clip = CGPath(roundedRect: inner, cornerWidth: 66, cornerHeight: 66, transform: nil)
    ctx.saveGState()
    ctx.addPath(clip); ctx.clip()
    let sky = CGGradient(colorsSpace: space, colors: [color(1.0, 0.86, 0.52), color(1.0, 0.62, 0.42)] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(sky, start: CGPoint(x: inner.midX, y: inner.maxY), end: CGPoint(x: inner.midX, y: inner.minY), options: [])
    // 太阳
    ctx.setFillColor(color(1, 1, 1, 0.95))
    ctx.fillEllipse(in: CGRect(x: inner.minX + 70, y: inner.maxY - 190, width: 110, height: 110))
    // 远山和近山
    func hill(_ pts: [CGPoint], _ c: CGColor) {
        ctx.setFillColor(c); ctx.beginPath(); ctx.move(to: pts[0])
        ctx.addCurve(to: pts[3], control1: pts[1], control2: pts[2])
        ctx.addLine(to: CGPoint(x: inner.maxX, y: inner.minY)); ctx.addLine(to: CGPoint(x: inner.minX, y: inner.minY)); ctx.closePath(); ctx.fillPath()
    }
    hill([CGPoint(x: inner.minX, y: inner.minY + 120), CGPoint(x: inner.minX + 130, y: inner.minY + 330),
          CGPoint(x: inner.minX + 260, y: inner.minY + 250), CGPoint(x: inner.maxX, y: inner.minY + 90)], color(0.98, 0.42, 0.45))
    hill([CGPoint(x: inner.minX, y: inner.minY + 40), CGPoint(x: inner.minX + 170, y: inner.minY + 110),
          CGPoint(x: inner.minX + 260, y: inner.minY + 300), CGPoint(x: inner.maxX, y: inner.minY + 170)], color(0.90, 0.26, 0.46))
    ctx.restoreGState()
}

// "piu" 的那一下闪光：四角星 + 两颗小星
func sparkle(_ c: CGPoint, _ R: CGFloat, _ fill: CGColor) {
    let k: CGFloat = 0.20
    ctx.beginPath()
    ctx.move(to: CGPoint(x: c.x, y: c.y + R))
    ctx.addQuadCurve(to: CGPoint(x: c.x + R, y: c.y), control: CGPoint(x: c.x + R * k, y: c.y + R * k))
    ctx.addQuadCurve(to: CGPoint(x: c.x, y: c.y - R), control: CGPoint(x: c.x + R * k, y: c.y - R * k))
    ctx.addQuadCurve(to: CGPoint(x: c.x - R, y: c.y), control: CGPoint(x: c.x - R * k, y: c.y - R * k))
    ctx.addQuadCurve(to: CGPoint(x: c.x, y: c.y + R), control: CGPoint(x: c.x - R * k, y: c.y + R * k))
    ctx.closePath()
    ctx.setFillColor(fill); ctx.fillPath()
}
ctx.setShadow(offset: CGSize(width: 0, height: -8), blur: 30, color: color(0.6, 0.1, 0.2, 0.3))
sparkle(CGPoint(x: 770, y: 760), 150, color(1, 1, 1))
ctx.setShadow(offset: .zero, blur: 0, color: nil)
sparkle(CGPoint(x: 905, y: 610), 52, color(1, 0.97, 0.80))
sparkle(CGPoint(x: 640, y: 905), 40, color(1, 0.97, 0.80, 0.95))

let image = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: image)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
print("ok")
