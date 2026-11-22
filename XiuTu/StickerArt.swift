import CoreGraphics
import Foundation

/// 内置的一套图形贴纸，全部用代码画出来（不是表情符号，也不带任何外部素材）。
/// 每个图形画在 0...1 的方格里，y 向上。贴到画面上时由 OverlayDrawing 加白边和阴影。
enum StickerArt {
    struct Piece: Identifiable {
        let id: String
        let title: String
        fileprivate let draw: (CGContext) -> Void
    }

    private static func rgb(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> CGColor {
        CGColor(srgbRed: r, green: g, blue: b, alpha: a)
    }

    private static let red = rgb(1.0, 0.33, 0.4)
    private static let pink = rgb(1.0, 0.62, 0.76)
    private static let yellow = rgb(1.0, 0.82, 0.2)
    private static let orange = rgb(1.0, 0.6, 0.2)
    private static let blue = rgb(0.36, 0.68, 1.0)
    private static let green = rgb(0.3, 0.8, 0.5)
    private static let purple = rgb(0.68, 0.52, 0.98)
    private static let white = rgb(1, 1, 1)

    /// 填充一个多边形，并用同色的圆角描边把尖角磨圆。
    private static func blob(_ ctx: CGContext, _ points: [CGPoint], color: CGColor, round: CGFloat = 0.06) {
        guard let first = points.first else { return }
        let path = CGMutablePath()
        path.move(to: first)
        for p in points.dropFirst() { path.addLine(to: p) }
        path.closeSubpath()
        ctx.setFillColor(color)
        ctx.setStrokeColor(color)
        ctx.setLineJoin(.round)
        ctx.setLineWidth(round)
        ctx.addPath(path)
        ctx.drawPath(using: .fillStroke)
    }

    private static func circle(_ ctx: CGContext, _ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ color: CGColor) {
        ctx.setFillColor(color)
        ctx.fillEllipse(in: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2))
    }

    private static func heartPath() -> CGPath {
        let p = CGMutablePath()
        p.move(to: CGPoint(x: 0.5, y: 0.1))
        p.addCurve(to: CGPoint(x: 0.06, y: 0.62), control1: CGPoint(x: 0.22, y: 0.3), control2: CGPoint(x: 0.06, y: 0.44))
        p.addCurve(to: CGPoint(x: 0.29, y: 0.9), control1: CGPoint(x: 0.06, y: 0.79), control2: CGPoint(x: 0.16, y: 0.9))
        p.addCurve(to: CGPoint(x: 0.5, y: 0.75), control1: CGPoint(x: 0.39, y: 0.9), control2: CGPoint(x: 0.46, y: 0.84))
        p.addCurve(to: CGPoint(x: 0.71, y: 0.9), control1: CGPoint(x: 0.54, y: 0.84), control2: CGPoint(x: 0.61, y: 0.9))
        p.addCurve(to: CGPoint(x: 0.94, y: 0.62), control1: CGPoint(x: 0.84, y: 0.9), control2: CGPoint(x: 0.94, y: 0.79))
        p.addCurve(to: CGPoint(x: 0.5, y: 0.1), control1: CGPoint(x: 0.94, y: 0.44), control2: CGPoint(x: 0.78, y: 0.3))
        p.closeSubpath()
        return p
    }

    private static func star(points n: Int, outer: CGFloat, inner: CGFloat, center: CGPoint = CGPoint(x: 0.5, y: 0.48),
                             start: CGFloat = .pi / 2) -> [CGPoint] {
        (0..<(n * 2)).map { i in
            let r = i % 2 == 0 ? outer : inner
            let a = start + CGFloat(i) * .pi / CGFloat(n)
            return CGPoint(x: center.x + r * cos(a), y: center.y + r * sin(a))
        }
    }

    static let all: [Piece] = [
        Piece(id: "heart", title: "爱心") { ctx in
            ctx.addPath(heartPath())
            ctx.setFillColor(red)
            ctx.fillPath()
            // 一小块高光
            ctx.saveGState()
            ctx.translateBy(x: 0.27, y: 0.72)
            ctx.rotate(by: 0.6)
            ctx.setFillColor(rgb(1, 1, 1, 0.55))
            ctx.fillEllipse(in: CGRect(x: -0.07, y: -0.035, width: 0.14, height: 0.07))
            ctx.restoreGState()
        },
        Piece(id: "star", title: "星星") { ctx in
            blob(ctx, star(points: 5, outer: 0.42, inner: 0.19), color: yellow, round: 0.09)
        },
        Piece(id: "sparkle", title: "闪光") { ctx in
            let p = CGMutablePath()
            p.move(to: CGPoint(x: 0.5, y: 0.97))
            p.addQuadCurve(to: CGPoint(x: 0.97, y: 0.5), control: CGPoint(x: 0.58, y: 0.58))
            p.addQuadCurve(to: CGPoint(x: 0.5, y: 0.03), control: CGPoint(x: 0.58, y: 0.42))
            p.addQuadCurve(to: CGPoint(x: 0.03, y: 0.5), control: CGPoint(x: 0.42, y: 0.42))
            p.addQuadCurve(to: CGPoint(x: 0.5, y: 0.97), control: CGPoint(x: 0.42, y: 0.58))
            ctx.addPath(p)
            ctx.setFillColor(rgb(1.0, 0.9, 0.45))
            ctx.fillPath()
        },
        Piece(id: "flower", title: "小花") { ctx in
            for i in 0..<5 {
                let a = .pi / 2 + CGFloat(i) * 2 * .pi / 5
                circle(ctx, 0.5 + 0.25 * cos(a), 0.5 + 0.25 * sin(a), 0.21, pink)
            }
            circle(ctx, 0.5, 0.5, 0.15, yellow)
        },
        Piece(id: "cloud", title: "云朵") { ctx in
            let c = rgb(0.86, 0.94, 1.0)
            circle(ctx, 0.3, 0.44, 0.19, c)
            circle(ctx, 0.5, 0.56, 0.25, c)
            circle(ctx, 0.72, 0.46, 0.21, c)
            ctx.setFillColor(c)
            ctx.fill(CGRect(x: 0.3, y: 0.25, width: 0.42, height: 0.25))
        },
        Piece(id: "sun", title: "太阳") { ctx in
            ctx.setStrokeColor(orange)
            ctx.setLineCap(.round)
            ctx.setLineWidth(0.075)
            for i in 0..<8 {
                let a = CGFloat(i) * .pi / 4
                ctx.move(to: CGPoint(x: 0.5 + 0.33 * cos(a), y: 0.5 + 0.33 * sin(a)))
                ctx.addLine(to: CGPoint(x: 0.5 + 0.44 * cos(a), y: 0.5 + 0.44 * sin(a)))
            }
            ctx.strokePath()
            circle(ctx, 0.5, 0.5, 0.24, yellow)
        },
        Piece(id: "moon", title: "月亮") { ctx in
            ctx.beginTransparencyLayer(auxiliaryInfo: nil)
            circle(ctx, 0.47, 0.5, 0.41, rgb(1.0, 0.87, 0.4))
            ctx.setBlendMode(.clear)
            circle(ctx, 0.65, 0.6, 0.35, white)
            ctx.setBlendMode(.normal)
            ctx.endTransparencyLayer()
        },
        Piece(id: "bolt", title: "闪电") { ctx in
            blob(ctx, [CGPoint(x: 0.6, y: 0.93), CGPoint(x: 0.22, y: 0.46), CGPoint(x: 0.47, y: 0.46),
                       CGPoint(x: 0.38, y: 0.07), CGPoint(x: 0.78, y: 0.57), CGPoint(x: 0.53, y: 0.57)],
                 color: yellow, round: 0.07)
        },
        Piece(id: "burst", title: "惊叹") { ctx in
            blob(ctx, star(points: 12, outer: 0.45, inner: 0.34, center: CGPoint(x: 0.5, y: 0.5)), color: orange, round: 0.04)
            ctx.setStrokeColor(white)
            ctx.setLineCap(.round)
            ctx.setLineWidth(0.1)
            ctx.move(to: CGPoint(x: 0.5, y: 0.7))
            ctx.addLine(to: CGPoint(x: 0.5, y: 0.46))
            ctx.strokePath()
            circle(ctx, 0.5, 0.29, 0.055, white)
        },
        Piece(id: "arrow", title: "箭头") { ctx in
            blob(ctx, [CGPoint(x: 0.1, y: 0.4), CGPoint(x: 0.54, y: 0.4), CGPoint(x: 0.54, y: 0.23),
                       CGPoint(x: 0.9, y: 0.5), CGPoint(x: 0.54, y: 0.77), CGPoint(x: 0.54, y: 0.6),
                       CGPoint(x: 0.1, y: 0.6)], color: blue, round: 0.08)
        },
        Piece(id: "crown", title: "皇冠") { ctx in
            blob(ctx, [CGPoint(x: 0.17, y: 0.27), CGPoint(x: 0.83, y: 0.27), CGPoint(x: 0.9, y: 0.68),
                       CGPoint(x: 0.69, y: 0.5), CGPoint(x: 0.5, y: 0.8), CGPoint(x: 0.31, y: 0.5),
                       CGPoint(x: 0.1, y: 0.68)], color: yellow, round: 0.07)
            circle(ctx, 0.5, 0.42, 0.055, red)
            circle(ctx, 0.3, 0.38, 0.04, blue)
            circle(ctx, 0.7, 0.38, 0.04, blue)
        },
        Piece(id: "check", title: "对勾") { ctx in
            circle(ctx, 0.5, 0.5, 0.44, green)
            ctx.setStrokeColor(white)
            ctx.setLineCap(.round)
            ctx.setLineJoin(.round)
            ctx.setLineWidth(0.1)
            ctx.move(to: CGPoint(x: 0.29, y: 0.5))
            ctx.addLine(to: CGPoint(x: 0.44, y: 0.35))
            ctx.addLine(to: CGPoint(x: 0.72, y: 0.65))
            ctx.strokePath()
        },
        Piece(id: "bow", title: "蝴蝶结") { ctx in
            blob(ctx, [CGPoint(x: 0.5, y: 0.5), CGPoint(x: 0.12, y: 0.76), CGPoint(x: 0.12, y: 0.24)], color: pink, round: 0.1)
            blob(ctx, [CGPoint(x: 0.5, y: 0.5), CGPoint(x: 0.88, y: 0.76), CGPoint(x: 0.88, y: 0.24)], color: pink, round: 0.1)
            let knot = CGPath(roundedRect: CGRect(x: 0.41, y: 0.39, width: 0.18, height: 0.22),
                              cornerWidth: 0.07, cornerHeight: 0.07, transform: nil)
            ctx.addPath(knot)
            ctx.setFillColor(rgb(1.0, 0.45, 0.65))
            ctx.fillPath()
        },
        Piece(id: "drop", title: "水滴") { ctx in
            let p = CGMutablePath()
            p.move(to: CGPoint(x: 0.5, y: 0.94))
            p.addCurve(to: CGPoint(x: 0.2, y: 0.38), control1: CGPoint(x: 0.42, y: 0.78), control2: CGPoint(x: 0.2, y: 0.58))
            p.addCurve(to: CGPoint(x: 0.5, y: 0.08), control1: CGPoint(x: 0.2, y: 0.2), control2: CGPoint(x: 0.34, y: 0.08))
            p.addCurve(to: CGPoint(x: 0.8, y: 0.38), control1: CGPoint(x: 0.66, y: 0.08), control2: CGPoint(x: 0.8, y: 0.2))
            p.addCurve(to: CGPoint(x: 0.5, y: 0.94), control1: CGPoint(x: 0.8, y: 0.58), control2: CGPoint(x: 0.58, y: 0.78))
            ctx.addPath(p)
            ctx.setFillColor(blue)
            ctx.fillPath()
            ctx.setFillColor(rgb(1, 1, 1, 0.5))
            ctx.fillEllipse(in: CGRect(x: 0.33, y: 0.3, width: 0.09, height: 0.16))
        },
        Piece(id: "gem", title: "钻石") { ctx in
            blob(ctx, [CGPoint(x: 0.5, y: 0.1), CGPoint(x: 0.1, y: 0.6), CGPoint(x: 0.28, y: 0.84),
                       CGPoint(x: 0.72, y: 0.84), CGPoint(x: 0.9, y: 0.6)], color: purple, round: 0.05)
            ctx.setStrokeColor(rgb(1, 1, 1, 0.65))
            ctx.setLineWidth(0.025)
            ctx.setLineJoin(.round)
            ctx.move(to: CGPoint(x: 0.1, y: 0.6))
            ctx.addLine(to: CGPoint(x: 0.9, y: 0.6))
            ctx.move(to: CGPoint(x: 0.5, y: 0.1))
            ctx.addLine(to: CGPoint(x: 0.36, y: 0.6))
            ctx.addLine(to: CGPoint(x: 0.5, y: 0.84))
            ctx.addLine(to: CGPoint(x: 0.64, y: 0.6))
            ctx.closePath()
            ctx.strokePath()
        },
    ]

    private static var cache: [String: CGImage] = [:]
    private static let lock = NSLock()

    /// 画成一张透明背景的方图。
    static func image(_ piece: Piece, size: Int = 512) -> CGImage? {
        let key = "\(piece.id)-\(size)"
        lock.lock()
        let hit = cache[key]
        lock.unlock()
        if let hit { return hit }
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.scaleBy(x: CGFloat(size), y: CGFloat(size))
        piece.draw(ctx)
        guard let image = ctx.makeImage() else { return nil }
        lock.lock()
        cache[key] = image
        lock.unlock()
        return image
    }
}
