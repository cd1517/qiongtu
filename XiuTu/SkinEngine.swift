import CoreGraphics
import CoreImage
import Foundation
import simd

/// 皮肤工具的图像处理。只用 Core Image / Core Graphics，不依赖界面，方便在 Mac 上单独编译测试。
///
/// 思路：每张脸只处理它周围的一块（脸 + 额头 + 脖子）。
/// - 哪里是皮肤 = 按五官画出来的范围 × 颜色像不像这张脸的肤色，所以眼睛、眉毛、嘴唇、头发都不会被动到；
/// - 磨皮走"分频"：只把中间那一档（色块、痘印、细纹）抹平，毛孔级的细纹理留着，再按需要补回来；
/// - 丰盈 / 提拉是在对应位置补光、填暗，再加一点点变形。
enum SkinEngine {

    // MARK: - 脸的几何

    /// 把一张脸的关键点换成像素坐标（y 朝下），并建立"脸自己的坐标系"：以两眼中点为原点、两眼间距为单位。
    struct Frame {
        let reliable: Bool
        let size: CGSize
        /// 按画面里的左右排：[左眼, 右眼] 的中心。
        let eyes: [CGPoint]
        let eyePolygons: [[CGPoint]]
        let browPolygons: [[CGPoint]]
        let eyeMid: CGPoint
        /// 两眼间距。
        let d: CGFloat
        /// 脸的"右"和"下"两个单位向量。
        let ex: CGPoint
        let ey: CGPoint
        let mouth: CGPoint
        /// [左嘴角, 右嘴角]。
        let mouthCorners: [CGPoint]
        /// [左鼻翼, 右鼻翼]。
        let alar: [CGPoint]
        let chin: CGPoint
        /// 下颌轮廓线，从画面左边到右边。
        let contour: [CGPoint]
        let outerLips: [CGPoint]
        let innerLips: [CGPoint]
        let fw: CGFloat
        /// 左右半边脸各露出来多少（正脸约 1；侧脸时远离镜头的那边很小）。
        let side: [CGFloat]
        /// 要处理的范围：像素坐标、y 朝下、已取整并限制在图片内。
        let roi: CGRect

        static func add(_ a: CGPoint, _ b: CGPoint, _ k: CGFloat = 1) -> CGPoint {
            CGPoint(x: a.x + b.x * k, y: a.y + b.y * k)
        }

        static func dot(_ a: CGPoint, _ b: CGPoint) -> CGFloat { a.x * b.x + a.y * b.y }

        static func centroid(_ points: [CGPoint]) -> CGPoint {
            guard !points.isEmpty else { return .zero }
            let sum = points.reduce(CGPoint.zero) { CGPoint(x: $0.x + $1.x, y: $0.y + $1.y) }
            return CGPoint(x: sum.x / CGFloat(points.count), y: sum.y / CGFloat(points.count))
        }

        /// 脸坐标系里的一点：u 向右、v 向下，单位是两眼间距。
        func at(_ u: CGFloat, _ v: CGFloat) -> CGPoint {
            Self.add(Self.add(eyeMid, ex, u * d), ey, v * d)
        }

        /// 从 p 出发，沿脸的方向走 (u, v)。
        func from(_ p: CGPoint, _ u: CGFloat, _ v: CGFloat) -> CGPoint {
            Self.add(Self.add(p, ex, u * d), ey, v * d)
        }

        init(_ face: FaceInfo, size: CGSize) {
            self.size = size
            func px(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * size.width, y: p.y * size.height) }
            let box = CGRect(x: face.box.minX * size.width, y: face.box.minY * size.height,
                             width: face.box.width * size.width, height: face.box.height * size.height)
            var reliable = face.hasReliableLandmarks
            var eyes: [CGPoint] = []
            var eyePolygons: [[CGPoint]] = []
            var browPolygons: [[CGPoint]] = []
            var eyeMid = CGPoint.zero
            var d: CGFloat = 1
            var ex = CGPoint(x: 1, y: 0)
            var ey = CGPoint(x: 0, y: 1)
            var mouth = CGPoint.zero
            var chin = CGPoint.zero
            var contour: [CGPoint] = []
            var outerLips: [CGPoint] = []
            var innerLips: [CGPoint] = []
            var nose: [CGPoint] = []
            if reliable {
                var a = face.leftEye.map(px), b = face.rightEye.map(px)
                var ba = face.leftBrow.map(px), bb = face.rightBrow.map(px)
                if Self.centroid(a).x > Self.centroid(b).x {
                    swap(&a, &b)
                    swap(&ba, &bb)
                }
                // 眉毛不一定和眼睛同名对应，按离哪只眼近来配。
                if !ba.isEmpty, !bb.isEmpty, Self.centroid(ba).x > Self.centroid(bb).x { swap(&ba, &bb) }
                eyePolygons = [a, b]
                browPolygons = [ba, bb]
                eyes = [Self.centroid(a), Self.centroid(b)]
                d = hypot(eyes[1].x - eyes[0].x, eyes[1].y - eyes[0].y)
                if d < 2 {
                    reliable = false
                } else {
                    ex = CGPoint(x: (eyes[1].x - eyes[0].x) / d, y: (eyes[1].y - eyes[0].y) / d)
                    ey = CGPoint(x: -ex.y, y: ex.x)
                    eyeMid = CGPoint(x: (eyes[0].x + eyes[1].x) / 2, y: (eyes[0].y + eyes[1].y) / 2)
                    outerLips = face.outerLips.map(px)
                    innerLips = face.innerLips.map(px)
                    nose = face.nose.map(px)
                    contour = face.contour.map(px)
                    chin = contour[contour.count / 2]
                    mouth = outerLips.count >= 3 ? Self.centroid(outerLips)
                        : CGPoint(x: eyeMid.x + (chin.x - eyeMid.x) * 0.6, y: eyeMid.y + (chin.y - eyeMid.y) * 0.6)
                    if Self.dot(CGPoint(x: chin.x - eyeMid.x, y: chin.y - eyeMid.y), ey) < 0 {
                        ey = CGPoint(x: -ey.x, y: -ey.y)
                    }
                    let first = contour[0], last = contour[contour.count - 1]
                    if Self.dot(CGPoint(x: first.x - eyeMid.x, y: first.y - eyeMid.y), ex)
                        > Self.dot(CGPoint(x: last.x - eyeMid.x, y: last.y - eyeMid.y), ex) {
                        contour.reverse()
                    }
                }
            }
            if !reliable {
                // 只有人脸框可用：按一般的比例估一个正脸。
                d = max(box.width * 0.42, 2)
                eyeMid = CGPoint(x: box.midX, y: box.minY + box.height * 0.4)
                ex = CGPoint(x: 1, y: 0)
                ey = CGPoint(x: 0, y: 1)
                eyes = [CGPoint(x: eyeMid.x - d / 2, y: eyeMid.y), CGPoint(x: eyeMid.x + d / 2, y: eyeMid.y)]
                eyePolygons = [[], []]
                browPolygons = [[], []]
                mouth = CGPoint(x: eyeMid.x, y: eyeMid.y + d * 1.1)
                chin = CGPoint(x: eyeMid.x, y: box.maxY + box.height * 0.05)
                contour = [CGPoint(x: box.minX, y: eyeMid.y + d * 0.1), chin, CGPoint(x: box.maxX, y: eyeMid.y + d * 0.1)]
                outerLips = []
                innerLips = []
                nose = []
            }
            self.reliable = reliable
            self.eyes = eyes
            self.eyePolygons = eyePolygons
            self.browPolygons = browPolygons
            self.eyeMid = eyeMid
            self.d = d
            self.ex = ex
            self.ey = ey
            self.mouth = mouth
            self.chin = chin
            self.contour = contour
            self.outerLips = outerLips
            self.innerLips = innerLips
            let a = contour[0], b = contour[contour.count - 1]
            fw = max(hypot(a.x - b.x, a.y - b.y), d * 1.6)

            func along(_ p: CGPoint) -> CGFloat { Self.dot(CGPoint(x: p.x - eyeMid.x, y: p.y - eyeMid.y), ex) }
            // 嘴角、鼻翼：有关键点就取最左最右的点，没有就按比例估。
            func extremes(_ points: [CGPoint], _ u: CGFloat, _ v: CGFloat) -> [CGPoint] {
                guard points.count >= 3, let l = points.min(by: { along($0) < along($1) }),
                      let r = points.max(by: { along($0) < along($1) }) else {
                    let o = eyeMid
                    return [CGPoint(x: o.x - ex.x * u * d + ey.x * v * d, y: o.y - ex.y * u * d + ey.y * v * d),
                            CGPoint(x: o.x + ex.x * u * d + ey.x * v * d, y: o.y + ex.y * u * d + ey.y * v * d)]
                }
                return [l, r]
            }
            mouthCorners = extremes(outerLips, 0.4, 1.1)
            alar = extremes(nose, 0.3, 0.72)
            side = [0, 1].map { i in
                let end = i == 0 ? a : b
                let w = abs(along(end) - along(eyes[i]))
                return min(max(w / (0.62 * d), 0.35), 1.15)
            }

            // 处理范围：上到发际线上面一点，下到脖子，左右各多留半张脸。
            var corners: [CGPoint] = []
            let top = CGPoint(x: eyeMid.x - ey.x * d * 2.1, y: eyeMid.y - ey.y * d * 2.1)
            let bottom = CGPoint(x: chin.x + ey.x * d * 1.05, y: chin.y + ey.y * d * 1.05)
            for p in [top, bottom] {
                corners.append(CGPoint(x: p.x - ex.x * fw * 1.05, y: p.y - ex.y * fw * 1.05))
                corners.append(CGPoint(x: p.x + ex.x * fw * 1.05, y: p.y + ex.y * fw * 1.05))
            }
            let xs = corners.map(\.x), ys = corners.map(\.y)
            let raw = CGRect(x: xs.min()!, y: ys.min()!, width: xs.max()! - xs.min()!, height: ys.max()! - ys.min()!)
            roi = raw.intersection(CGRect(origin: .zero, size: size)).integral
        }

        /// 嘴是不是张开到能看见牙。
        var mouthOpen: Bool {
            guard reliable, innerLips.count >= 4, outerLips.count >= 4 else { return false }
            func extent(_ points: [CGPoint], _ axis: CGPoint) -> CGFloat {
                let values = points.map { Self.dot($0, axis) }
                return (values.max() ?? 0) - (values.min() ?? 0)
            }
            let gap = extent(innerLips, ey)
            let width = extent(outerLips, ex)
            return width > 0 && gap / width > 0.07
        }
    }

    // MARK: - 蒙版

    /// 一张脸要用到的所有区域，每张位图的 R/G/B 各放一个区域。位图只按"处理范围"的比例画，和图片分辨率无关。
    final class MaskSet {
        /// 皮肤（不含眼、眉、唇）/ 额头 / 眼周。
        let a: CGImage
        /// 法令纹 / 脖子 / 口周。
        let b: CGImage
        /// 眼下 / 泪沟 / 苹果肌。
        let c: CGImage
        /// 面颊 / 太阳穴 / 额头中央。
        let d: CGImage
        /// 眼睛里面 / 牙齿 / 鼻基底。
        let fine: CGImage

        init(a: CGImage, b: CGImage, c: CGImage, d: CGImage, fine: CGImage) {
            self.a = a
            self.b = b
            self.c = c
            self.d = d
            self.fine = fine
        }
    }

    /// 在灰度位图上按"图片像素坐标"画形状。
    private final class Painter {
        let ctx: CGContext
        let w: Int
        let h: Int
        private let roi: CGRect
        private let sx: CGFloat
        private let sy: CGFloat
        private let exc: CGPoint
        private let eyc: CGPoint
        private let gradient: CGGradient?

        init?(w: Int, h: Int, frame: Frame) {
            guard let c = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                    space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
            ctx = c
            self.w = w
            self.h = h
            roi = frame.roi
            sx = CGFloat(w) / max(frame.roi.width, 1)
            sy = CGFloat(h) / max(frame.roi.height, 1)
            // 位图的 y 朝上，脸的两个方向要跟着翻一下。
            exc = CGPoint(x: frame.ex.x, y: -frame.ex.y)
            eyc = CGPoint(x: frame.ey.x, y: -frame.ey.y)
            gradient = CGGradient(colorSpace: CGColorSpaceCreateDeviceGray(), colorComponents: [1, 1, 0, 1],
                                  locations: [0, 1], count: 2)
            ctx.setFillColor(gray: 0, alpha: 1)
            ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
            ctx.setLineJoin(.round)
            ctx.setLineCap(.round)
        }

        func point(_ p: CGPoint) -> CGPoint {
            CGPoint(x: (p.x - roi.minX) * sx, y: (roi.maxY - p.y) * sy)
        }

        private func inFace(_ center: CGPoint, _ ru: CGFloat, _ rv: CGFloat, _ body: () -> Void) {
            let c = point(center)
            ctx.saveGState()
            ctx.translateBy(x: c.x, y: c.y)
            ctx.concatenate(CGAffineTransform(a: exc.x, b: exc.y, c: eyc.x, d: eyc.y, tx: 0, ty: 0))
            ctx.scaleBy(x: max(ru * sx, 0.01), y: max(rv * sx, 0.01))
            body()
            ctx.restoreGState()
        }

        /// 实心椭圆，长短轴跟着脸的方向。半径是图片像素。
        func oval(_ center: CGPoint, _ ru: CGFloat, _ rv: CGFloat, white: Bool = true) {
            ctx.setFillColor(gray: white ? 1 : 0, alpha: 1)
            inFace(center, ru, rv) { ctx.fillEllipse(in: CGRect(x: -1, y: -1, width: 2, height: 2)) }
        }

        /// 中间亮、边缘渐隐的一团。几团重叠时取"滤色"，不会互相盖掉。
        func blob(_ center: CGPoint, _ ru: CGFloat, _ rv: CGFloat) {
            guard let gradient else { return }
            ctx.setBlendMode(.screen)
            inFace(center, ru, rv) {
                ctx.drawRadialGradient(gradient, startCenter: .zero, startRadius: 0, endCenter: .zero, endRadius: 1, options: [])
            }
            ctx.setBlendMode(.normal)
        }

        func polygon(_ points: [CGPoint], white: Bool = true, grow: CGFloat = 0) {
            guard points.count >= 3 else { return }
            let gray: CGFloat = white ? 1 : 0
            ctx.setFillColor(gray: gray, alpha: 1)
            ctx.setStrokeColor(gray: gray, alpha: 1)
            ctx.beginPath()
            ctx.addLines(between: points.map(point))
            ctx.closePath()
            if grow > 0 {
                ctx.setLineWidth(grow * sx * 2)
                ctx.drawPath(using: .fillStroke)
            } else {
                ctx.fillPath()
            }
        }

        func line(_ from: CGPoint, _ to: CGPoint, width: CGFloat) {
            ctx.setStrokeColor(gray: 1, alpha: 1)
            ctx.setLineWidth(max(width * sx, 1))
            ctx.beginPath()
            ctx.move(to: point(from))
            ctx.addLine(to: point(to))
            ctx.strokePath()
        }

        var bytes: [UInt8] {
            guard let data = ctx.data else { return [] }
            let rowBytes = ctx.bytesPerRow
            var out = [UInt8](repeating: 0, count: w * h)
            let p = data.bindMemory(to: UInt8.self, capacity: rowBytes * h)
            for y in 0..<h {
                for x in 0..<w { out[y * w + x] = p[y * rowBytes + x] }
            }
            return out
        }
    }

    private static func span(_ points: [CGPoint]) -> CGFloat {
        guard let minX = points.map(\.x).min(), let maxX = points.map(\.x).max(),
              let minY = points.map(\.y).min(), let maxY = points.map(\.y).max() else { return 0 }
        return hypot(maxX - minX, maxY - minY)
    }

    private static func pack(_ r: [UInt8], _ g: [UInt8], _ b: [UInt8], w: Int, h: Int) -> CGImage? {
        var data = Data(count: w * h * 4)
        data.withUnsafeMutableBytes { raw in
            let p = raw.bindMemory(to: UInt8.self)
            for i in 0..<(w * h) {
                p[i * 4] = r.isEmpty ? 0 : r[i]
                p[i * 4 + 1] = g.isEmpty ? 0 : g[i]
                p[i * 4 + 2] = b.isEmpty ? 0 : b[i]
                p[i * 4 + 3] = 255
            }
        }
        guard let provider = CGDataProvider(data: data as CFData) else { return nil }
        return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4,
                       space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    private static var maskCache: [String: MaskSet] = [:]
    private static var maskOrder: [String] = []
    private static let maskLock = NSLock()

    /// 这张脸的区域蒙版。画一次要几毫秒，拖滑块时每帧都要用，所以按"脸 + 图片比例"缓存。
    static func masks(for frame: Frame, face: FaceInfo) -> MaskSet? {
        var key = "\(Int(frame.roi.minX)),\(Int(frame.roi.minY)),\(Int(frame.roi.width)),\(Int(frame.roi.height))"
        key += "|\(Int(frame.size.width))x\(Int(frame.size.height))|\(frame.reliable)"
        key += "|\(face.box.minX),\(face.box.minY),\(face.box.width)"
        if let p = face.contour.first, let q = face.outerLips.first, let e = face.leftEye.first {
            key += "|\(p.x),\(p.y),\(q.x),\(q.y),\(e.x),\(e.y),\(face.innerLips.count)"
        }
        maskLock.lock()
        if let hit = maskCache[key] {
            maskLock.unlock()
            return hit
        }
        maskLock.unlock()
        guard let built = buildMasks(frame) else { return nil }
        maskLock.lock()
        maskCache[key] = built
        maskOrder.append(key)
        if maskOrder.count > 12 { maskCache[maskOrder.removeFirst()] = nil }
        maskLock.unlock()
        return built
    }

    private static func buildMasks(_ f: Frame) -> MaskSet? {
        let roi = f.roi
        guard roi.width >= 8, roi.height >= 8 else { return nil }
        let scale = 384 / max(roi.width, roi.height)
        let w = max(8, Int((roi.width * scale).rounded())), h = max(8, Int((roi.height * scale).rounded()))
        let d = f.d
        func layer(_ draw: (Painter) -> Void) -> [UInt8] {
            guard let p = Painter(w: w, h: h, frame: f) else { return [] }
            draw(p)
            return p.bytes
        }
        func cutFeatures(_ p: Painter, eyes: CGFloat, brows: CGFloat, lips: CGFloat) {
            for eye in f.eyePolygons { p.polygon(eye, white: false, grow: span(eye) * eyes) }
            for brow in f.browPolygons { p.polygon(brow, white: false, grow: span(brow) * brows) }
            p.polygon(f.outerLips, white: false, grow: span(f.outerLips) * lips)
        }
        /// 下颌轮廓加上估出来的额头，就是整张脸的范围。
        func facePolygon(forehead: CGFloat) -> [CGPoint] {
            let a = f.contour[0], b = f.contour[f.contour.count - 1]
            let mid = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
            let half = hypot(a.x - mid.x, a.y - mid.y)
            var polygon = f.contour
            for step in stride(from: 11, through: 1, by: -1) {
                let t = CGFloat(step) / 12 * .pi
                // 从轮廓的右端绕过头顶回到左端。
                polygon.append(CGPoint(x: mid.x + cos(t) * (a.x - mid.x) - sin(t) * f.ey.x * half * forehead,
                                       y: mid.y + cos(t) * (a.y - mid.y) - sin(t) * f.ey.y * half * forehead))
            }
            return polygon
        }

        // 皮肤
        let skin = layer { p in
            if f.reliable {
                p.polygon(facePolygon(forehead: 1.22))
                cutFeatures(p, eyes: 0.16, brows: 0.07, lips: 0.04)
            } else {
                p.oval(f.at(0, 0.35), f.fw * 0.55, f.fw * 0.72)
            }
        }
        guard f.reliable else {
            guard let a = pack(skin, [], [], w: w, h: h), let empty = pack([], [], [], w: w, h: h) else { return nil }
            return MaskSet(a: a, b: empty, c: empty, d: empty, fine: empty)
        }
        let sides: [(index: Int, sign: CGFloat, q: CGFloat)] = [(0, -1, f.side[0]), (1, 1, f.side[1])]
        // 远离镜头、几乎看不到的那半边脸不画只属于那一侧的区域。
        let shown = sides.filter { $0.q > 0.5 }

        let forehead = layer { p in
            p.oval(f.at(0, -0.92), d * 1.02, d * 0.46)
            for brow in f.browPolygons { p.polygon(brow, white: false, grow: span(brow) * 0.1) }
        }
        let eyeAround = layer { p in
            for s in sides {
                let eye = f.eyes[s.index]
                p.oval(f.from(eye, 0, 0.07), d * 0.6, d * 0.4)
                if s.q > 0.5 { p.oval(f.from(eye, s.sign * 0.5 * s.q, 0.02), d * 0.3 * s.q, d * 0.32) }
            }
            cutFeatures(p, eyes: 0.14, brows: 0.08, lips: 0)
        }
        let naso = layer { p in
            for s in shown {
                let from = f.from(f.alar[s.index], s.sign * 0.1, -0.06)
                let to = f.from(f.mouthCorners[s.index], s.sign * 0.24 * s.q, 0.16)
                p.line(from, to, width: d * 0.2)
            }
        }
        let neck = layer { p in
            p.oval(f.from(f.chin, 0, 0.72), d * 0.85, d * 0.62)
            // 下巴以上不算脖子。
            var face = f.contour
            face.append(f.from(f.contour[f.contour.count - 1], 0, -1.5))
            face.append(f.from(f.contour[0], 0, -1.5))
            p.polygon(face, white: false, grow: d * 0.04)
        }
        let mouthAround = layer { p in
            p.oval(f.from(f.mouth, 0, 0.06), d * 0.78, d * 0.5)
            p.polygon(f.outerLips, white: false, grow: span(f.outerLips) * 0.06)
        }
        let underEye = layer { p in
            for s in sides {
                let eye = f.eyes[s.index]
                p.oval(f.from(eye, 0, 0.3), d * 0.42, d * 0.2)
            }
            cutFeatures(p, eyes: 0.14, brows: 0, lips: 0)
        }
        let tear = layer { p in
            for s in sides { p.blob(f.from(f.eyes[s.index], -s.sign * 0.12, 0.36), d * 0.32, d * 0.15) }
        }
        let apple = layer { p in
            for s in shown { p.blob(f.from(f.eyes[s.index], s.sign * 0.14 * s.q, 0.68), d * 0.42 * s.q, d * 0.34) }
        }
        let cheek = layer { p in
            for s in shown { p.blob(f.from(f.eyes[s.index], s.sign * 0.3 * s.q, 1.0), d * 0.3 * s.q, d * 0.4) }
        }
        let temple = layer { p in
            for s in shown { p.blob(f.from(f.eyes[s.index], s.sign * 0.52 * s.q, -0.42), d * 0.22 * s.q, d * 0.34) }
        }
        let foreheadCenter = layer { p in
            p.blob(f.at(0, -0.9), d * 0.78, d * 0.42)
        }
        let eyeInside = layer { p in
            for eye in f.eyePolygons { p.polygon(eye) }
        }
        let teeth = layer { p in
            if f.mouthOpen { p.polygon(f.innerLips) }
        }
        let noseBase = layer { p in
            for s in shown { p.blob(f.from(f.alar[s.index], s.sign * 0.12, 0.03), d * 0.2, d * 0.2) }
        }
        guard let a = pack(skin, forehead, eyeAround, w: w, h: h),
              let b = pack(naso, neck, mouthAround, w: w, h: h),
              let c = pack(underEye, tear, apple, w: w, h: h),
              let dd = pack(cheek, temple, foreheadCenter, w: w, h: h),
              let fine = pack(eyeInside, teeth, noseBase, w: w, h: h) else { return nil }
        return MaskSet(a: a, b: b, c: c, d: dd, fine: fine)
    }

    // MARK: - 内核

    private static let library = """
    float skL(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
    float skLike(vec3 c, vec3 t) {
        vec3 a = max(c, vec3(0.002));
        vec3 m = max(t, vec3(0.002));
        float u = log(a.r / a.g) - log(m.r / m.g);
        float v = log(a.b / a.g) - log(m.b / m.g);
        float su = u < 0.0 ? 0.34 : 0.26;
        float sv = v > 0.0 ? 0.50 : 0.36;
        float d2 = (u * u) / (su * su) + (v * v) / (sv * sv);
        float l = skL(a) / max(skL(m), 0.002);
        return exp(-0.5 * d2) * smoothstep(0.08, 0.30, l);
    }
    vec3 skUn(vec4 p) { return p.rgb / max(p.a, 0.004); }
    float skSat(vec3 c) {
        float mx = max(c.r, max(c.g, c.b));
        return (mx - min(c.r, min(c.g, c.b))) / max(mx, 0.0005);
    }
    float skBodyLike(vec3 c, vec3 t) {
        vec3 a = max(c, vec3(0.002));
        vec3 m = max(t, vec3(0.002));
        float u = log(a.r / a.g) - log(m.r / m.g);
        float v = log(a.b / a.g) - log(m.b / m.g);
        float su = u < 0.0 ? 0.26 : 0.20;
        float sv = v > 0.0 ? 0.34 : 0.24;
        float l = skL(a) / max(skL(m), 0.002);
        float tone = exp(-0.5 * ((u * u) / (su * su) + (v * v) / (sv * sv)));
        return tone * smoothstep(0.08, 0.30, l) * (1.0 - smoothstep(1.8, 2.6, l))
             * smoothstep(0.35, 0.70, skSat(a) / max(skSat(m), 0.02));
    }

    """

    /// 所有内核的源码。内核写错了 Core Image 不会报错只会返回 nil，所以留着名字方便在 Mac 上逐个编译检查。
    static let sources: [(name: String, color: Bool, source: String)] = [
        ("skinWeight", true, library + """
        kernel vec4 skinWeight(__sample s, __sample ma, __sample mb, vec3 tone) {
            float w = max(ma.r, mb.g) * skLike(s.rgb, tone);
            return vec4(s.rgb * w, w);
        }
        """),
        ("skinDev", true, library + """
        kernel vec4 skinDev(__sample s, __sample mean) {
            float g = sqrt(max(skL(s.rgb), 0.0));
            float gm = sqrt(max(skL(mean.rgb), 0.0));
            float v = min(abs(g - gm) * 4.0, 1.0);
            return vec4(v, v, v, 1.0);
        }
        """),
        ("skinCoef", true, """
        kernel vec4 skinCoef(__sample d, float e) {
            float v = d.r * 0.25;
            float a = (v * v) / (v * v + e);
            return vec4(a, a, a, 1.0);
        }
        """),
        ("skinBase", true, """
        kernel vec4 skinBase(__sample s, __sample mean, __sample a) {
            return vec4(mix(mean.rgb, s.rgb, a.r), s.a);
        }
        """),
        ("skinTexture", true, library + """
        kernel vec4 skinTexture(__sample s, __sample fine, __sample base, __sample mid, __sample wide,
                                __sample ma, __sample mb, __sample mc, vec4 p, vec4 w, vec4 x, vec3 tone) {
            vec3 c = s.rgb;
            float conf = smoothstep(0.04, 0.30, mid.a);
            float confW = smoothstep(0.04, 0.30, wide.a);
            vec3 m = skUn(mid);
            vec3 wd = skUn(wide);
            float skin = ma.r * smoothstep(0.12, 0.55, mid.a);
            vec3 dF = c - fine.rgb;
            float lc = max(skL(c), 0.0005);
            float lm = max(skL(m), 0.0005);
            float lf = max(skL(fine.rgb), 0.0005);
            float lb = max(skL(base.rgb), 0.0005);
            // 比周围皮肤暗很多的东西（发丝、痣、鼻孔）不去抹它。x.w = 1 表示只有人脸框、没有五官位置：
            // 这时眼睛、眉毛、嘴唇没法按位置避开，就按"够不够亮、颜色像不像皮肤"逐个像素把它们让出来。
            float feature = smoothstep(mix(0.30, 0.45, x.w), mix(0.55, 0.75, x.w), lf / lm);
            skin *= feature * mix(1.0, smoothstep(0.02, 0.25, skLike(fine.rgb, tone)), x.w);

            float lift = w.x * ma.g * ma.r + w.y * ma.b + w.z * mb.r + w.w * mb.g + x.x * mb.b + x.y * mc.r;
            lift = min(lift, 1.0) * conf;
            float ln = mix(lf, lc, 0.6);
            float crease = max(lm - ln, 0.0);
            float flatten = x.z * ma.g * ma.r * conf;
            float bump = max(ln - lm, 0.0);
            c *= max(lc + crease * lift * 0.92 - bump * flatten * 0.7, 0.0) / lc;
            vec3 b2 = base.rgb * (max(lb + (lm - lb) * max(lift * 0.9, flatten * 0.7), 0.0) / lb);

            float dip = (lm - lf) / lm;
            float red = s.r / lc - m.r / lm;
            float blem = smoothstep(0.05, 0.18, max(dip, 0.0) + max(red, 0.0) * 0.6);
            c = mix(c, m + dF * 0.85, p.w * blem * skin * conf);
            b2 = mix(b2, m, p.w * blem * skin * conf);

            float sm = max(p.x, x.z * ma.g * 0.8) * skin;
            b2 = mix(b2, m, 0.45 * p.x * conf);
            c = mix(c, b2 + dF * mix(1.0, 0.62, p.x), sm);

            float lw = max(skL(wd), 0.0005);
            vec3 chroma = (wd / lw) / max(m / lm, vec3(0.05));
            vec3 g = clamp(chroma * pow(lw / lm, 0.30), vec3(0.75), vec3(1.35));
            c *= mix(vec3(1.0), g, p.z * skin * conf * confW);

            c += clamp(dF, vec3(-0.10 * lc), vec3(0.10 * lc)) * p.y * 0.65 * skin;
            return vec4(max(c, vec3(0.0)), s.a);
        }
        """),
        ("skinTone", true, library + """
        kernel vec4 skinTone(__sample s, __sample mid, __sample wide, __sample ma, __sample mc, __sample mf,
                             vec4 t, vec4 e, float teeth, vec3 tone) {
            vec3 c = s.rgb;
            float conf = smoothstep(0.04, 0.30, mid.a);
            float confW = smoothstep(0.04, 0.30, wide.a);
            vec3 m = skUn(mid);
            vec3 wd = skUn(wide);
            float lm = max(skL(m), 0.0005);
            float lw = max(skL(wd), 0.0005);
            float lt = max(skL(tone), 0.002);

            float l = max(skL(c), 0.0005);
            float mx = max(c.r, max(c.g, c.b));
            float mn = min(c.r, min(c.g, c.b));
            float sat = (mx - mn) / max(mx, 0.0005);
            float satW = (max(wd.r, max(wd.g, wd.b)) - min(wd.r, min(wd.g, wd.b))) / max(max(wd.r, max(wd.g, wd.b)), 0.0005);
            float shine = smoothstep(1.06, 1.45, l / lw) * (1.0 - smoothstep(0.70, 1.0, sat / max(satW, 0.02))) * ma.r * conf * confW * t.w;
            float ln = mix(l, min(l, lw * 1.10), shine * 0.85);
            c *= ln / l;
            c = mix(c, (wd / lw) * skL(c), shine * 0.6);

            float under = mc.r * e.x * conf * confW;
            vec3 want = mix(wd / lw, tone / lt, 0.5);
            float lcur = max(skL(c), 0.0005);
            float gl = min(clamp(pow(lw / lm, 1.4), 1.0, 1.7), max(1.0, lw * 1.05 / lcur));
            vec3 gain = clamp(want / max(m / lm, vec3(0.05)), vec3(0.8), vec3(1.25)) * gl;
            c *= mix(vec3(1.0), gain, under * 0.9);

            float skin = smoothstep(0.10, 0.50, mid.a) * mix(0.35, 1.0, skLike(c, tone));
            vec3 cw = pow(max(c, vec3(0.0)), vec3(1.0 / (1.0 + 0.42 * t.x)));
            cw = mix(vec3(skL(cw)), cw, 1.0 - 0.10 * t.x);
            c = mix(c, cw, skin);
            vec3 tint = vec3(1.0 + 0.10 * t.y + 0.10 * t.z, 1.0 - 0.04 * t.z, 1.0 - 0.14 * t.y + 0.03 * t.z);
            vec3 ct = c * tint;
            ct *= skL(c) / max(skL(ct), 0.0005);
            c = mix(c, ct, skin);

            float ei = mf.r;
            l = max(skL(c), 0.0005);
            mx = max(c.r, max(c.g, c.b));
            mn = min(c.r, min(c.g, c.b));
            sat = (mx - mn) / max(mx, 0.0005);
            float bright = smoothstep(0.35, 0.70, sqrt(l / lt));
            vec3 g2 = sqrt(max(c, vec3(0.0)));
            vec3 sc = g2 * g2 * (3.0 - 2.0 * g2);
            vec3 eb = mix(g2, sc, 0.45 * e.y) * (1.0 + 0.10 * e.y);
            eb = eb * eb;
            eb = mix(vec3(skL(eb)), eb, 1.0 + 0.2 * e.y);
            c = mix(c, eb, ei);
            float sclera = ei * (1.0 - smoothstep(0.22, 0.48, sat)) * bright;
            vec3 cs = mix(c, vec3(skL(c)), 0.5 * e.z) * (1.0 + 0.22 * e.z);
            c = mix(c, cs, sclera);
            float veins = ei * (1.0 - smoothstep(0.35, 0.65, sat)) * bright;
            c.r -= max(c.r - max(c.g, c.b) * 1.04, 0.0) * 0.85 * e.w * veins;

            l = max(skL(c), 0.0005);
            float tooth = mf.g * smoothstep(0.30, 0.60, sqrt(l / lt)) * (1.0 - smoothstep(1.30, 1.75, c.r / max(c.g, 0.0005)));
            vec3 cz = mix(c, vec3(l), 0.55 * teeth) * (1.0 + 0.25 * teeth);
            cz.b *= 1.0 + 0.04 * teeth;
            c = mix(c, cz, tooth);
            return vec4(max(c, vec3(0.0)), s.a);
        }
        """),
        ("skinPlump", true, library + """
        kernel vec4 skinPlump(__sample s, __sample mid, __sample wide, __sample mc, __sample md, __sample mf,
                              vec4 a, vec4 b) {
            vec3 c = s.rgb;
            float conf = smoothstep(0.04, 0.30, mid.a) * smoothstep(0.04, 0.30, wide.a);
            vec3 m = skUn(mid);
            vec3 wd = skUn(wide);
            float lm = max(skL(m), 0.0005);
            float lw = max(skL(wd), 0.0005);
            float fill = clamp(lw / lm, 1.0, 1.6) - 1.0;
            float bright = a.x * md.b * 0.20 + a.z * mc.b * 0.24 + a.y * mc.g * 0.10 + a.w * md.r * 0.12
                         + b.x * mf.b * 0.14 + b.y * md.g * 0.14;
            float hollow = a.x * md.b * 0.4 + a.y * mc.g * 1.0 + a.z * mc.b * 0.4 + a.w * md.r * 0.9
                         + b.x * mf.b * 1.0 + b.y * md.g * 0.8;
            float l = max(skL(c), 0.0005);
            float room = (1.0 - smoothstep(0.6, 1.0, l)) * (1.0 - smoothstep(1.10, 1.60, l / lw));
            c *= 1.0 + (bright + fill * min(hollow, 1.0)) * room * conf;
            vec3 chroma = clamp((wd / lw) / max(m / lm, vec3(0.05)), vec3(0.85), vec3(1.2));
            c *= mix(vec3(1.0), chroma, a.y * mc.g * conf * 0.6);
            return vec4(max(c, vec3(0.0)), s.a);
        }
        """),
        ("skinHole", true, """
        kernel vec4 skinHole(__sample s, __sample bm) {
            float w = 1.0 - smoothstep(0.02, 0.40, bm.b);
            return vec4(s.rgb * w, w);
        }
        """),
        ("skinConceal", true, """
        kernel vec4 skinConceal(__sample s, __sample near, __sample far, __sample fine, __sample bm) {
            vec3 f1 = near.rgb / max(near.a, 0.004);
            vec3 f2 = far.rgb / max(far.a, 0.004);
            vec3 f = mix(f2, f1, smoothstep(0.06, 0.35, near.a));
            float ok = smoothstep(0.01, 0.10, far.a);
            vec3 r = f + (s.rgb - fine.rgb) * 0.8;
            return vec4(mix(s.rgb, max(r, vec3(0.0)), bm.b * ok), s.a);
        }
        """),
        ("skinBrush", true, library + """
        kernel vec4 skinBrush(__sample s, __sample bm) {
            vec3 c = s.rgb;
            float l = skL(c);
            float up = 1.0 + bm.r * 0.32 * (1.0 - smoothstep(0.45, 1.0, l));
            vec3 down = mix(vec3(1.0), vec3(0.70, 0.64, 0.60), bm.g * 0.6);
            return vec4(c * up * down, s.a);
        }
        """),
        ("skinBody", true, library + """
        kernel vec4 skinBody(__sample s, __sample body, vec3 tone) {
            float w = skBodyLike(s.rgb, tone) * body.r;
            return vec4(s.rgb * w, w);
        }
        """),
        ("skinUnify", true, library + """
        kernel vec4 skinUnify(__sample s, __sample wide, __sample body, vec3 tone, vec3 target, float amount) {
            vec3 c = s.rgb;
            vec3 m = skUn(wide);
            float lm = max(skL(m), 0.0005);
            float lt = max(skL(target), 0.0005);
            vec3 chroma = (target / lt) / max(m / lm, vec3(0.05));
            vec3 g = clamp(chroma * pow(lt / lm, 0.35), vec3(0.6), vec3(1.8));
            float w = smoothstep(0.05, 0.35, wide.a) * skBodyLike(c, tone) * body.r;
            return vec4(c * mix(vec3(1.0), g, amount * w), s.a);
        }
        """),
        ("skinWarp", false, """
        kernel vec4 skinWarp(sampler image, sampler map, float shift) {
            vec2 d = destCoord();
            vec4 m = sample(map, samplerTransform(map, d));
            vec2 p = d - (m.rg - vec2(0.50196078)) * shift;
            return sample(image, samplerTransform(image, p));
        }
        """),
    ]

    private static let colorKernels: [String: CIColorKernel] = {
        var out: [String: CIColorKernel] = [:]
        for item in sources where item.color {
            if let k = CIColorKernel(source: item.source) { out[item.name] = k }
        }
        return out
    }()

    private static let warpKernel: CIKernel? = sources.first { $0.name == "skinWarp" }.flatMap { CIKernel(source: $0.source) }

    /// 没编过的内核的名字（检查用）。
    static var brokenKernels: [String] {
        sources.filter { $0.color ? colorKernels[$0.name] == nil : warpKernel == nil }.map(\.name)
    }

    private static func run(_ name: String, extent: CGRect, _ arguments: [Any]) -> CIImage? {
        colorKernels[name]?.apply(extent: extent, arguments: arguments)?.cropped(to: extent)
    }

    // MARK: - 小工具

    private static func vec(_ v: SIMD3<Float>) -> CIVector {
        CIVector(x: CGFloat(v.x), y: CGFloat(v.y), z: CGFloat(v.z))
    }

    private static func blur(_ image: CIImage, _ radius: CGFloat, clamp: Bool, crop: CGRect) -> CIImage {
        guard radius > 0.05 else { return image.cropped(to: crop) }
        return (clamp ? image.clampedToExtent() : image)
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: radius])
            .cropped(to: crop)
    }

    /// 把一张小蒙版放到 rect 上并羽化。rect 外面是 0。
    private static func place(_ mask: CGImage, in rect: CGRect, feather: CGFloat) -> CIImage {
        let m = CIImage(cgImage: mask, options: [.colorSpace: NSNull()])
            .transformed(by: CGAffineTransform(scaleX: rect.width / CGFloat(mask.width), y: rect.height / CGFloat(mask.height)))
            .transformed(by: CGAffineTransform(translationX: rect.minX, y: rect.minY))
        return blur(m, feather, clamp: false, crop: rect)
    }

    private static func scaled(_ image: CIImage, by k: CGFloat, crop: CGRect) -> CIImage {
        image.clampedToExtent()
            .transformed(by: CGAffineTransform(scaleX: k, y: k))
            .cropped(to: crop)
    }

    /// 处理范围在 Core Image 坐标里的位置（y 朝上）。
    private static func ciRect(_ roi: CGRect, in extent: CGRect) -> CGRect {
        CGRect(x: extent.minX + roi.minX, y: extent.minY + extent.height - roi.maxY, width: roi.width, height: roi.height)
    }

    /// 美白、冷暖、红润对"平均肤色"本身的影响，和内核里的算法一致。统一肤色时拿它当目标。
    static func toned(_ tone: SIMD3<Float>, white: CGFloat, warmth: CGFloat, rosy: CGFloat) -> SIMD3<Float> {
        func luma(_ c: SIMD3<Float>) -> Float { c.x * 0.2126 + c.y * 0.7152 + c.z * 0.0722 }
        var c = simd_max(tone, SIMD3<Float>(repeating: 0))
        let e = 1 / (1 + 0.42 * Float(white))
        var cw = SIMD3<Float>(powf(c.x, e), powf(c.y, e), powf(c.z, e))
        let l = luma(cw)
        cw = SIMD3<Float>(repeating: l) + (cw - SIMD3<Float>(repeating: l)) * (1 - 0.10 * Float(white))
        c = cw
        let y = Float(warmth), z = Float(rosy)
        var ct = c * SIMD3<Float>(1 + 0.10 * y + 0.10 * z, 1 - 0.04 * z, 1 - 0.14 * y + 0.03 * z)
        ct *= luma(c) / max(luma(ct), 0.0005)
        return ct
    }

    // MARK: - 量肤色

    /// 量这张脸的平均肤色（工作空间里的线性 RGB）。取鼻子和两颊这一带，再把颜色差得远的像素（鼻孔、阴影、头发）剔掉重算一遍。
    static func measureTone(_ image: CIImage, face: FaceInfo, context: CIContext) -> SIMD3<Float>? {
        let extent = image.extent
        let f = Frame(face, size: extent.size)
        let c = f.at(0, 0.55)
        let pixel = CGRect(x: c.x - f.d * 0.75, y: c.y - f.d * 0.4, width: f.d * 1.5, height: f.d * 0.8)
            .intersection(CGRect(origin: .zero, size: extent.size)).integral
        guard pixel.width >= 2, pixel.height >= 2 else { return nil }
        let rect = ciRect(pixel, in: extent)
        func average(_ source: CIImage) -> SIMD4<Float>? {
            let avg = source.cropped(to: rect).applyingFilter("CIAreaAverage", parameters: [kCIInputExtentKey: CIVector(cgRect: rect)])
            var px = [Float](repeating: 0, count: 4)
            context.render(avg, toBitmap: &px, rowBytes: 16, bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
                           format: .RGBAf, colorSpace: nil)
            guard px.allSatisfy({ $0.isFinite }) else { return nil }
            return SIMD4<Float>(px[0], px[1], px[2], px[3])
        }
        guard let first = average(image), first.w > 0.01 else { return nil }
        let rough = SIMD3<Float>(first.x, first.y, first.z) / first.w
        let white = CIImage(color: CIColor(red: 1, green: 1, blue: 1)).cropped(to: rect)
        guard let weighted = run("skinBody", extent: rect, [image.cropped(to: rect), white, vec(rough)]),
              let second = average(weighted), second.w > 0.02 else { return rough }
        return SIMD3<Float>(second.x, second.y, second.z) / second.w
    }

    // MARK: - 一张脸的修饰

    private static let textureKeys: [SkinKey] = [.smooth, .texture, .even, .acne, .foreheadLines, .eyeLines, .nasolabial,
                                                 .neckLines, .liftMouth, .eyeBag, .liftForehead, .liftEye, .liftMid]
    private static let toneKeys: [SkinKey] = [.white, .warmth, .rosy, .deshine, .darkCircle, .eyeBright, .eyeWhite, .redEye, .teeth]
    private static let plumpKeys: [SkinKey] = [.plumpForehead, .plumpTear, .plumpApple, .plumpCheek, .plumpNoseBase, .plumpTemple]
    private static let warpKeys: [SkinKey] = [.liftContour, .liftEye, .liftMid, .liftMouth, .plumpApple, .plumpTemple]

    /// 这张脸上所有不涉及变形的修饰。
    static func retouch(_ image: CIImage, face: SkinFace) -> CIImage {
        let extent = image.extent
        let f = Frame(face.face, size: extent.size)
        func v(_ key: SkinKey) -> CGFloat { (!f.reliable && key.needsLandmarks) ? 0 : face[key] }
        let wantsTexture = textureKeys.contains { v($0) != 0 }
        let wantsTone = toneKeys.contains { v($0) != 0 }
        let wantsPlump = plumpKeys.contains { v($0) != 0 }
        guard wantsTexture || wantsTone || wantsPlump, f.roi.width >= 16, f.roi.height >= 16,
              let masks = masks(for: f, face: face.face) else { return image }

        let rect = ciRect(f.roi, in: extent)
        let fw = f.fw
        let q: CGFloat = 0.25
        let rectQ = CGRect(x: rect.minX * q, y: rect.minY * q, width: rect.width * q, height: rect.height * q)
        let tone = vec(face.tone)
        let source = image.cropped(to: rect)

        let soft = fw * 0.03
        let ma = place(masks.a, in: rect, feather: soft)
        let mb = place(masks.b, in: rect, feather: soft)
        let mc = place(masks.c, in: rect, feather: soft)
        let md = place(masks.d, in: rect, feather: soft)
        let mf = place(masks.fine, in: rect, feather: fw * 0.01)

        // 四分之一大小上算"只统计皮肤"的局部平均和大范围平均：边上的头发、背景不会混进来。
        let small = source
            .applyingFilter("CILanczosScaleTransform", parameters: [kCIInputScaleKey: q, kCIInputAspectRatioKey: 1.0])
            .clampedToExtent()
            .cropped(to: rectQ)
        let maQ = place(masks.a, in: rectQ, feather: soft * q)
        let mbQ = place(masks.b, in: rectQ, feather: soft * q)
        guard let weighted = run("skinWeight", extent: rectQ, [small, maQ, mbQ, tone]) else { return image }
        let mid = scaled(blur(weighted, fw * 0.03 * q, clamp: false, crop: rectQ), by: 1 / q, crop: rect)
        let wide = scaled(blur(weighted, fw * 0.22 * q, clamp: false, crop: rectQ), by: 1 / q, crop: rect)

        var out = source
        if wantsTexture {
            // 保边的底子：反差大的地方（五官边缘、发丝）跟原图走，反差小的地方（色块、痘印）取周围的平均。
            let mean = blur(small, fw * 0.035 * q, clamp: true, crop: rectQ)
            let fine = blur(source, max(fw * 0.006, 0.6), clamp: true, crop: rect)
            if let dev = run("skinDev", extent: rectQ, [small, mean]),
               let coef = run("skinCoef", extent: rectQ, [blur(dev, fw * 0.035 * q, clamp: true, crop: rectQ), 0.0036]),
               let base = run("skinBase", extent: rect, [
                   source, scaled(mean, by: 1 / q, crop: rect),
                   scaled(blur(coef, fw * 0.02 * q, clamp: true, crop: rectQ), by: 1 / q, crop: rect),
               ]) {
                let p = CIVector(x: v(.smooth), y: v(.texture), z: v(.even), w: v(.acne))
                // 提拉里的眼周、面中、口周除了往上提，也顺带把那一带的纹路淡化一些。
                let w = CIVector(x: v(.foreheadLines), y: max(v(.eyeLines), v(.liftEye) * 0.5),
                                 z: max(v(.nasolabial), v(.liftMid) * 0.6), w: v(.neckLines))
                let x = CIVector(x: v(.liftMouth) * 0.7, y: max(v(.eyeBag), v(.liftEye) * 0.4), z: v(.liftForehead), w: f.reliable ? 0 : 1)
                if let textured = run("skinTexture", extent: rect, [out, fine, base, mid, wide, ma, mb, mc, p, w, x, tone]) {
                    out = textured
                }
            }
        }
        if wantsTone {
            let t = CIVector(x: v(.white), y: v(.warmth), z: v(.rosy), w: v(.deshine))
            let e = CIVector(x: v(.darkCircle), y: v(.eyeBright), z: v(.eyeWhite), w: v(.redEye))
            if let toned = run("skinTone", extent: rect, [out, mid, wide, ma, mc, mf, t, e, v(.teeth), tone]) {
                out = toned
            }
        }
        if wantsPlump {
            let a = CIVector(x: v(.plumpForehead), y: v(.plumpTear), z: v(.plumpApple), w: v(.plumpCheek))
            let b = CIVector(x: v(.plumpNoseBase), y: v(.plumpTemple), z: 0, w: 0)
            if let plumped = run("skinPlump", extent: rect, [out, mid, wide, mc, md, mf, a, b]) {
                out = plumped
            }
        }
        return out.composited(over: image).cropped(to: extent)
    }

    // MARK: - 提拉 / 丰盈的变形

    private struct Push {
        var center: CGPoint
        /// 这一带的画面整体挪多少（像素，y 朝下）。
        var shift: CGPoint = .zero
        /// 以 center 为中心往外鼓多少（比例）。
        var bulge: CGFloat = 0
        var radius: CGFloat
    }

    private static func pushes(_ f: Frame, face: SkinFace) -> [Push] {
        guard f.reliable else { return [] }
        var out: [Push] = []
        let d = f.d
        func scaled(_ p: CGPoint, _ k: CGFloat) -> CGPoint { CGPoint(x: p.x * k, y: p.y * k) }
        let up = CGPoint(x: -f.ey.x, y: -f.ey.y)
        let sides: [(Int, CGFloat)] = [(0, -1), (1, 1)]

        let contour = face[.liftContour]
        if contour > 0, f.contour.count >= 8 {
            // 下颌两侧往里、往上收，下颌线就利落了。
            let n = f.contour.count
            for (index, weight) in [(n / 6, 1.0), (n / 3, 0.75), (n - 1 - n / 3, 0.75), (n - 1 - n / 6, 1.0)] as [(Int, CGFloat)] {
                let p = f.contour[index]
                let inward = CGPoint(x: f.eyeMid.x + f.ey.x * d * 0.6 - p.x, y: f.eyeMid.y + f.ey.y * d * 0.6 - p.y)
                let length = max(hypot(inward.x, inward.y), 1)
                let dir = CGPoint(x: inward.x / length * 0.55 + up.x * 0.75, y: inward.y / length * 0.55 + up.y * 0.75)
                let shown = min(f.side[index < n / 2 ? 0 : 1], 1)
                out.append(Push(center: p, shift: scaled(dir, f.fw * 0.045 * contour * weight * shown), radius: f.fw * 0.3 * max(shown, 0.6)))
            }
        }
        let eye = face[.liftEye]
        if eye > 0 {
            for (i, sign) in sides where f.side[i] > 0.5 {
                let c = f.from(f.eyes[i], sign * 0.52 * f.side[i], -0.02)
                let dir = CGPoint(x: up.x + f.ex.x * sign * 0.3, y: up.y + f.ex.y * sign * 0.3)
                out.append(Push(center: c, shift: scaled(dir, d * 0.055 * eye * min(f.side[i], 1)), radius: d * 0.42))
            }
        }
        let mid = face[.liftMid]
        if mid > 0 {
            for (i, sign) in sides where f.side[i] > 0.5 {
                let c = f.from(f.eyes[i], sign * 0.15 * f.side[i], 0.78)
                out.append(Push(center: c, shift: scaled(up, d * 0.06 * mid), radius: d * 0.5))
            }
        }
        let mouth = face[.liftMouth]
        if mouth > 0 {
            for (i, sign) in sides {
                let dir = CGPoint(x: up.x * 0.9 + f.ex.x * sign * 0.25, y: up.y * 0.9 + f.ex.y * sign * 0.25)
                out.append(Push(center: f.mouthCorners[i], shift: scaled(dir, d * 0.045 * mouth), radius: d * 0.3))
            }
        }
        let apple = face[.plumpApple]
        if apple > 0 {
            for (i, sign) in sides where f.side[i] > 0.5 {
                out.append(Push(center: f.from(f.eyes[i], sign * 0.14 * f.side[i], 0.68), bulge: 0.10 * apple, radius: d * 0.45))
            }
        }
        let temple = face[.plumpTemple]
        if temple > 0 {
            for (i, sign) in sides where f.side[i] > 0.5 {
                out.append(Push(center: f.from(f.eyes[i], sign * 0.52 * f.side[i], -0.42), bulge: 0.06 * temple, radius: d * 0.36))
            }
        }
        return out
    }

    /// 提拉和丰盈里需要挪动画面的那一小部分。位移场画在一张小网格上，单位跟着处理范围走，和分辨率无关。
    static func lift(_ image: CIImage, face: SkinFace) -> CIImage {
        guard warpKeys.contains(where: { face[$0] != 0 }) else { return image }
        let extent = image.extent
        let f = Frame(face.face, size: extent.size)
        let list = pushes(f, face: face)
        guard !list.isEmpty, f.roi.width >= 16, f.roi.height >= 16, let kernel = warpKernel else { return image }
        let n = 96
        let roi = f.roi
        var dx = [Float](repeating: 0, count: n * n), dy = [Float](repeating: 0, count: n * n)
        var peak: Float = 0
        for j in 1..<(n - 1) {
            let y = roi.minY + (CGFloat(j) + 0.5) / CGFloat(n) * roi.height
            for i in 1..<(n - 1) {
                let x = roi.minX + (CGFloat(i) + 0.5) / CGFloat(n) * roi.width
                var sx: CGFloat = 0, sy: CGFloat = 0
                for p in list {
                    let ox = x - p.center.x, oy = y - p.center.y
                    let t2 = (ox * ox + oy * oy) / (p.radius * p.radius)
                    guard t2 < 1 else { continue }
                    let w = (1 - t2) * (1 - t2)
                    sx += (p.shift.x + ox * p.bulge) * w
                    sy += (p.shift.y + oy * p.bulge) * w
                }
                dx[j * n + i] = Float(sx)
                // Core Image 的 y 轴朝上。
                dy[j * n + i] = Float(-sy)
                peak = max(peak, abs(Float(sx)), abs(Float(sy)))
            }
        }
        guard peak > 0.05 else { return image }
        let limit = peak * 1.05
        var data = Data(count: n * n * 4)
        data.withUnsafeMutableBytes { raw in
            let p = raw.bindMemory(to: UInt8.self)
            for k in 0..<(n * n) {
                // 128 = 不动；±127 = ±limit。
                p[k * 4] = UInt8(max(1, min(255, (128 + dx[k] / limit * 127).rounded())))
                p[k * 4 + 1] = UInt8(max(1, min(255, (128 + dy[k] / limit * 127).rounded())))
                p[k * 4 + 2] = 0
                p[k * 4 + 3] = 255
            }
        }
        guard let provider = CGDataProvider(data: data as CFData),
              let cg = CGImage(width: n, height: n, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: n * 4,
                               space: CGColorSpaceCreateDeviceRGB(),
                               bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                               provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent) else { return image }
        let rect = ciRect(roi, in: extent)
        let cell = max(rect.width, rect.height) / CGFloat(n)
        let map = CIImage(cgImage: cg, options: [.colorSpace: NSNull()])
            .transformed(by: CGAffineTransform(scaleX: rect.width / CGFloat(n), y: rect.height / CGFloat(n)))
            .transformed(by: CGAffineTransform(translationX: rect.minX, y: rect.minY))
            .clampedToExtent()
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: cell * 0.8])
        let reach = CGFloat(limit) + 2
        // 和手动美体一样对整张图套内核：处理范围以外位移图是"不动"，取到的就是原来的像素。
        // （只在处理范围里套内核、再盖回原图的写法，整幅渲染时 Core Image 会把范围以外的画面拉花。）
        guard let warped = kernel.apply(extent: extent, roiCallback: { index, r in
            index == 0 ? r.insetBy(dx: -reach, dy: -reach) : r
        }, arguments: [image.clampedToExtent(), map, CGFloat(limit) * 255.0 / 127.0]) else { return image }
        return warped.cropped(to: extent)
    }

    // MARK: - 画笔

    private final class BrushLayer {
        let image: CGImage
        /// 画过的范围（归一化，原点左上角）。
        let bounds: CGRect
        let hasContour: Bool
        let hasConceal: Bool
        /// 遮瑕笔、修容笔各自的平均粗细（以长边为 1）。
        let concealWidth: CGFloat
        let contourWidth: CGFloat

        init(image: CGImage, bounds: CGRect, hasContour: Bool, hasConceal: Bool, concealWidth: CGFloat, contourWidth: CGFloat) {
            self.image = image
            self.bounds = bounds
            self.hasContour = hasContour
            self.hasConceal = hasConceal
            self.concealWidth = concealWidth
            self.contourWidth = contourWidth
        }
    }

    private static var brushCache: (strokes: [SkinStroke], aspect: CGFloat, layer: BrushLayer)?
    private static let brushLock = NSLock()

    /// 把所有笔画画到一张位图上：R = 高光，G = 阴影，B = 遮瑕。
    private static func brushLayer(_ strokes: [SkinStroke], size: CGSize) -> BrushLayer? {
        guard !strokes.isEmpty, size.width > 0, size.height > 0 else { return nil }
        let aspect = size.width / size.height
        brushLock.lock()
        if let hit = brushCache, hit.strokes == strokes, abs(hit.aspect - aspect) < 0.0005 {
            brushLock.unlock()
            return hit.layer
        }
        brushLock.unlock()
        let long = max(size.width, size.height)
        let scale = min(1, 1024 / long)
        let w = max(1, Int((size.width * scale).rounded())), h = max(1, Int((size.height * scale).rounded()))
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.setFillColor(red: 0, green: 0, blue: 0, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        var box: CGRect?
        var hasContour = false, hasConceal = false
        var widthSum: CGFloat = 0, widthCount: CGFloat = 0
        var contourSum: CGFloat = 0, contourCount: CGFloat = 0
        // 从外到里一圈圈叠上去，笔触边缘是渐隐的。
        let rings: [(CGFloat, CGFloat)] = [(1.0, 0.2), (0.85, 0.25), (0.7, 0.33), (0.55, 0.5), (0.4, 1.0)]
        for stroke in strokes {
            guard let first = stroke.points.first else { continue }
            let lw = max(1, stroke.width * CGFloat(max(w, h)))
            func map(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * CGFloat(w), y: (1 - p.y) * CGFloat(h)) }
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0
            switch stroke.kind {
            case .highlight:
                r = 1
                hasContour = true
                contourSum += stroke.width
                contourCount += 1
            case .shadow:
                g = 1
                hasContour = true
                contourSum += stroke.width
                contourCount += 1
            case .conceal:
                b = 1
                hasConceal = true
                widthSum += stroke.width
                widthCount += 1
            case .erase:
                b = 1
            }
            // 橡皮用"正片叠底"画蓝色：R、G 被清掉，遮瑕的 B 不受影响。
            ctx.setBlendMode(stroke.kind == .erase ? .multiply : .screen)
            for (k, alpha) in rings {
                let a = stroke.kind == .erase ? 1 : min(alpha * stroke.strength, 1)
                ctx.setStrokeColor(red: r, green: g, blue: b, alpha: a)
                ctx.setFillColor(red: r, green: g, blue: b, alpha: a)
                let ring = lw * (stroke.kind == .erase ? 1 : k)
                if stroke.points.count == 1 {
                    let c = map(first)
                    ctx.fillEllipse(in: CGRect(x: c.x - ring / 2, y: c.y - ring / 2, width: ring, height: ring))
                } else {
                    ctx.setLineWidth(ring)
                    ctx.beginPath()
                    ctx.move(to: map(first))
                    for p in stroke.points.dropFirst() { ctx.addLine(to: map(p)) }
                    ctx.strokePath()
                }
                if stroke.kind == .erase { break }
            }
            let half = stroke.width * long / 2
            for p in stroke.points {
                let rect = CGRect(x: (p.x * size.width - half) / size.width, y: (p.y * size.height - half) / size.height,
                                  width: half * 2 / size.width, height: half * 2 / size.height)
                box = box?.union(rect) ?? rect
            }
        }
        ctx.setBlendMode(.normal)
        guard let cg = ctx.makeImage(), let bounds = box else { return nil }
        let layer = BrushLayer(image: cg, bounds: bounds, hasContour: hasContour, hasConceal: hasConceal,
                               concealWidth: widthCount > 0 ? widthSum / widthCount : 0,
                               contourWidth: contourCount > 0 ? contourSum / contourCount : 0)
        brushLock.lock()
        brushCache = (strokes, aspect, layer)
        brushLock.unlock()
        return layer
    }

    /// 笔画覆盖的范围（Core Image 坐标），四周多留 margin 像素。
    private static func brushRect(_ layer: BrushLayer, extent: CGRect, margin: CGFloat) -> CGRect {
        let b = layer.bounds
        let r = CGRect(x: extent.minX + b.minX * extent.width, y: extent.minY + (1 - b.maxY) * extent.height,
                       width: b.width * extent.width, height: b.height * extent.height)
        return r.insetBy(dx: -margin, dy: -margin).intersection(extent).integral
    }

    private static func brushImage(_ layer: BrushLayer, extent: CGRect, crop: CGRect) -> CIImage {
        CIImage(cgImage: layer.image, options: [.colorSpace: NSNull()])
            .transformed(by: CGAffineTransform(scaleX: extent.width / CGFloat(layer.image.width),
                                               y: extent.height / CGFloat(layer.image.height)))
            .transformed(by: CGAffineTransform(translationX: extent.minX, y: extent.minY))
            .cropped(to: crop)
    }

    /// 遮瑕笔 / 点痘：涂过的地方用周围皮肤的颜色补上，再把原来的细纹理叠回去，不会留下一块糊的。
    static func conceal(_ image: CIImage, strokes: [SkinStroke]) -> CIImage {
        let extent = image.extent
        guard let layer = brushLayer(strokes, size: extent.size), layer.hasConceal else { return image }
        let long = max(extent.width, extent.height)
        let width = max(layer.concealWidth * long, 2)
        let rect = brushRect(layer, extent: extent, margin: width * 5)
        guard rect.width >= 2, rect.height >= 2 else { return image }
        let source = image.cropped(to: rect)
        let bm = brushImage(layer, extent: extent, crop: rect)
        guard let hole = run("skinHole", extent: rect, [source, bm]) else { return image }
        let near = blur(hole, width * 0.45, clamp: true, crop: rect)
        let far = blur(hole, width * 1.8, clamp: true, crop: rect)
        let fine = blur(source, max(width * 0.07, 0.6), clamp: true, crop: rect)
        guard let out = run("skinConceal", extent: rect, [source, near, far, fine, bm]) else { return image }
        return out.composited(over: image).cropped(to: extent)
    }

    /// 修容笔：高光是提亮，阴影是压暗并略微偏暖；都是乘上去的，皮肤纹理还在。
    static func contour(_ image: CIImage, strokes: [SkinStroke]) -> CIImage {
        let extent = image.extent
        guard let layer = brushLayer(strokes, size: extent.size), layer.hasContour else { return image }
        // 笔触的边缘再抹软一些，看起来像扫上去的粉，而不是画上去的线。
        let softness = max(layer.contourWidth * max(extent.width, extent.height) * 0.22, 1)
        let rect = brushRect(layer, extent: extent, margin: softness * 3 + 4)
        guard rect.width >= 2, rect.height >= 2 else { return image }
        let bm = blur(brushImage(layer, extent: extent, crop: rect), softness, clamp: true, crop: rect)
        guard let out = run("skinBrush", extent: rect, [image.cropped(to: rect), bm]) else { return image }
        return out.composited(over: image).cropped(to: extent)
    }

    // MARK: - 肤色统一

    /// 把全身颜色像皮肤的地方，往脸（修完之后）的肤色靠：颜色靠得多，明暗只靠一点，光影还在。
    static func unify(_ image: CIImage, reference: SkinFace, amount: CGFloat, body: CGImage?) -> CIImage {
        let extent = image.extent
        let f = Frame(reference.face, size: extent.size)
        let long = max(extent.width, extent.height)
        let q = min(1, 640 / long)
        let small = CGRect(x: extent.minX * q, y: extent.minY * q, width: extent.width * q, height: extent.height * q)
        let tone = vec(reference.tone)
        let target = vec(toned(reference.tone, white: reference[.white], warmth: reference[.warmth], rosy: reference[.rosy]))
        func bodyImage(_ rect: CGRect) -> CIImage {
            guard let body else { return CIImage(color: CIColor(red: 1, green: 1, blue: 1)).cropped(to: rect) }
            return place(body, in: rect, feather: 0)
        }
        let reduced = image.clampedToExtent()
            .transformed(by: CGAffineTransform(scaleX: q, y: q))
            .cropped(to: small)
        guard let weighted = run("skinBody", extent: small, [reduced, bodyImage(small), tone]) else { return image }
        let wide = scaled(blur(weighted, f.fw * 0.5 * q, clamp: false, crop: small), by: 1 / q, crop: extent)
        guard let out = run("skinUnify", extent: extent, [image, wide, bodyImage(extent), tone, target, amount]) else { return image }
        return out
    }
}
