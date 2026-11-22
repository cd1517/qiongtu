import CoreGraphics
import Foundation

/// 美妆内部用的二维向量。单独起一个类型，免得给 CGPoint 加运算符和别处冲突。
struct MKVec: Equatable, Hashable {
    var x: CGFloat
    var y: CGFloat

    init(_ x: CGFloat, _ y: CGFloat) {
        self.x = x
        self.y = y
    }

    init(_ p: CGPoint) {
        x = p.x
        y = p.y
    }

    var cg: CGPoint { CGPoint(x: x, y: y) }
    var length: CGFloat { hypot(x, y) }
    var unit: MKVec { let l = max(length, 1e-9); return MKVec(x / l, y / l) }
    /// 顺时针转 90°（y 轴朝下的坐标里）。
    var perp: MKVec { MKVec(-y, x) }

    func dot(_ o: MKVec) -> CGFloat { x * o.x + y * o.y }
    func dist(_ o: MKVec) -> CGFloat { hypot(x - o.x, y - o.y) }
    func lerp(_ o: MKVec, _ t: CGFloat) -> MKVec { MKVec(x + (o.x - x) * t, y + (o.y - y) * t) }

    static func + (a: MKVec, b: MKVec) -> MKVec { MKVec(a.x + b.x, a.y + b.y) }
    static func - (a: MKVec, b: MKVec) -> MKVec { MKVec(a.x - b.x, a.y - b.y) }
    static func * (a: MKVec, k: CGFloat) -> MKVec { MKVec(a.x * k, a.y * k) }
    static prefix func - (a: MKVec) -> MKVec { MKVec(-a.x, -a.y) }
}

func mkCentroid(_ points: [MKVec]) -> MKVec {
    guard !points.isEmpty else { return MKVec(0, 0) }
    var sx: CGFloat = 0, sy: CGFloat = 0
    for p in points { sx += p.x; sy += p.y }
    return MKVec(sx / CGFloat(points.count), sy / CGFloat(points.count))
}

func mkSmoothstep(_ a: CGFloat, _ b: CGFloat, _ x: CGFloat) -> CGFloat {
    guard a != b else { return x < a ? 0 : 1 }
    let t = min(max((x - a) / (b - a), 0), 1)
    return t * t * (3 - 2 * t)
}

/// 穿过若干点的平滑曲线，可以按 0...1（沿弧长）取点和切线方向。
struct MKCurve {
    private(set) var samples: [MKVec] = []
    private var lengths: [CGFloat] = []

    var length: CGFloat { lengths.last ?? 0 }
    var isValid: Bool { samples.count >= 2 && length > 0 }

    init(through points: [MKVec], perSegment: Int = 10) {
        guard points.count >= 2 else {
            samples = points
            lengths = points.map { _ in 0 }
            return
        }
        var out: [MKVec] = []
        let n = points.count
        for i in 0..<(n - 1) {
            // Catmull-Rom：两端用相邻点外推。
            let p0 = i > 0 ? points[i - 1] : points[i] + (points[i] - points[i + 1])
            let p1 = points[i]
            let p2 = points[i + 1]
            let p3 = i + 2 < n ? points[i + 2] : points[i + 1] + (points[i + 1] - points[i])
            for s in 0..<perSegment {
                let t = CGFloat(s) / CGFloat(perSegment)
                let t2 = t * t, t3 = t2 * t
                let x = 0.5 * ((2 * p1.x) + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2
                    + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3)
                let y = 0.5 * ((2 * p1.y) + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2
                    + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3)
                out.append(MKVec(x, y))
            }
        }
        out.append(points[n - 1])
        samples = out
        var acc: CGFloat = 0
        lengths = [0]
        for i in 1..<out.count {
            acc += out[i].dist(out[i - 1])
            lengths.append(acc)
        }
    }

    private func locate(_ t: CGFloat) -> (Int, CGFloat) {
        let target = min(max(t, 0), 1) * length
        var lo = 0, hi = lengths.count - 1
        while hi - lo > 1 {
            let mid = (lo + hi) / 2
            if lengths[mid] <= target { lo = mid } else { hi = mid }
        }
        let span = lengths[hi] - lengths[lo]
        return (lo, span > 0 ? (target - lengths[lo]) / span : 0)
    }

    func at(_ t: CGFloat) -> MKVec {
        guard samples.count >= 2 else { return samples.first ?? MKVec(0, 0) }
        let (i, f) = locate(t)
        return samples[i].lerp(samples[min(i + 1, samples.count - 1)], f)
    }

    /// 单位切线方向（沿 t 增大的方向）。
    func tangent(_ t: CGFloat) -> MKVec {
        guard samples.count >= 2 else { return MKVec(1, 0) }
        let (i, _) = locate(t)
        let a = samples[max(i - 1, 0)]
        let b = samples[min(i + 2, samples.count - 1)]
        return (b - a).unit
    }
}

/// 一只眼睛。坐标都在这张脸的方框里（0...1，原点左上角，y 朝下）。
struct MKEye {
    var inner: MKVec
    var outer: MKVec
    /// 上、下眼睑，都是从内眼角到外眼角。
    var upper: MKCurve
    var lower: MKCurve
    /// 瞳孔位置（没有就用眼睛中心）。
    var center: MKVec
    var width: CGFloat
    /// 内眼角 → 外眼角的单位方向。
    var axis: MKVec
    /// 垂直于 axis、指向眉毛的单位方向。
    var up: MKVec
    /// 这只眼睛到同侧脸边缘的距离：这半边脸露出来多宽。
    var side: CGFloat = 0
    /// 侧脸时远离镜头的那半边太窄，腮红、修容就不画这一边。
    var visible = true
    /// 上眼睑中点到眉毛下缘的距离。
    var browGap: CGFloat = 0
    /// 眼睛张开的范围（闭合多边形）。
    var outline: [MKVec] = []
}

/// 一条眉毛：中线（眉头 → 眉尾）和每一处的粗细。
struct MKBrow {
    var center: MKCurve
    /// 中线上均匀取 9 个点处，眉毛原本的粗细。
    var thickness: [CGFloat]
    var length: CGFloat
    /// 眉头 → 眉尾的单位方向，以及垂直于它、朝额头的方向。
    var axis: MKVec
    var up: MKVec

    func thickness(at t: CGFloat) -> CGFloat {
        guard thickness.count >= 2 else { return thickness.first ?? 0 }
        let f = min(max(t, 0), 1) * CGFloat(thickness.count - 1)
        let i = min(Int(f), thickness.count - 2)
        return thickness[i] + (thickness[i + 1] - thickness[i]) * (f - CGFloat(i))
    }
}

/// 一张脸的几何信息。所有坐标都换算到"这张脸的方框"里：0...1，原点左上角，y 朝下，
/// 方框按像素是正方形，所以这里面的长度和角度都是真实比例，和图片分辨率无关。
struct MKFace {
    /// 方框在图片里的位置（像素，原点左上角）。
    let roi: CGRect

    var eyes: [MKEye] = []          // [画面左边的眼睛, 画面右边的眼睛]
    var brows: [MKBrow?] = [nil, nil]
    var right = MKVec(1, 0)         // 左眼 → 右眼
    var down = MKVec(0, 1)          // 眼睛 → 嘴
    var eyeMid = MKVec(0.5, 0.4)
    var eyeDist: CGFloat = 0.2
    /// 两眼连线到嘴的距离。
    var mouthDist: CGFloat = 0.25
    var faceWidth: CGFloat = 0.5
    var contour = MKCurve(through: [])
    var chin = MKVec(0.5, 0.8)

    var lipsOuter: [MKVec] = []
    var lipsInner: [MKVec] = []
    var mouthCenter = MKVec(0.5, 0.65)
    var mouthWidth: CGFloat = 0.15
    var lipTop = MKVec(0.5, 0.62)       // 唇峰中间
    var lipBottom = MKVec(0.5, 0.69)    // 下唇最低处

    /// 鼻梁中线：眉心 → 鼻尖。
    var ridge = MKCurve(through: [])
    var noseTip = MKVec(0.5, 0.55)
    var noseBase = MKVec(0.5, 0.58)
    var noseWidth: CGFloat = 0.1

    var up: MKVec { -down }

    init?(face: FaceInfo, extent: CGSize) {
        guard face.hasReliableLandmarks, face.outerLips.count >= 6, extent.width > 0, extent.height > 0 else { return nil }
        let w = extent.width, h = extent.height
        let bw = face.box.width * w, bh = face.box.height * h
        let side = max(bw, bh) * 2.1
        let cx = face.box.midX * w, cy = face.box.midY * h - bh * 0.1
        roi = CGRect(x: cx - side / 2, y: cy - side / 2, width: side, height: side)
        let origin = roi.origin
        func unit(_ p: CGPoint) -> MKVec { MKVec((p.x * w - origin.x) / side, (p.y * h - origin.y) / side) }

        let lp = face.leftEye.map(unit), rp = face.rightEye.map(unit)
        var a = mkCentroid(lp), b = mkCentroid(rp)
        var leftPts = lp, rightPts = rp
        var leftPupil = face.leftPupil.map(unit), rightPupil = face.rightPupil.map(unit)
        var leftBrowPts = face.leftBrow.map(unit), rightBrowPts = face.rightBrow.map(unit)
        if a.x > b.x {
            swap(&a, &b); swap(&leftPts, &rightPts); swap(&leftPupil, &rightPupil); swap(&leftBrowPts, &rightBrowPts)
        }
        let mouth = mkCentroid(face.outerLips.map(unit))
        eyeMid = a.lerp(b, 0.5)
        eyeDist = max(a.dist(b), 1e-4)
        right = (b - a).unit
        var d = right.perp
        if d.dot(mouth - eyeMid) < 0 { d = -d }
        down = d
        mouthDist = max((mouth - eyeMid).dot(down), eyeDist * 0.5)

        let contourPts = face.contour.map(unit)
        contour = MKCurve(through: contourPts, perSegment: 6)
        faceWidth = max(contourPts[0].dist(contourPts[contourPts.count - 1]), eyeDist * 1.2)
        chin = contourPts.max { ($0 - eyeMid).dot(down) < ($1 - eyeMid).dot(down) } ?? contourPts[contourPts.count / 2]

        // 眼睛
        let ends = [contourPts[0], contourPts[contourPts.count - 1]]
        var built: [MKEye] = []
        for (points, pupil, other, outward) in [(leftPts, leftPupil, b, -right), (rightPts, rightPupil, a, right)] {
            guard var eye = Self.makeEye(points, pupil: pupil, other: other, faceUp: -down) else { return nil }
            eye.side = ends.map { $0.dist(eye.center) }.min() ?? 0
            _ = outward
            built.append(eye)
        }
        let widest = max(built[0].side, built[1].side, 1e-4)
        for i in 0..<2 { built[i].visible = built[i].side / widest > 0.5 }

        // 眉毛
        let nosePoint = eyeMid
        for (i, pts) in [leftBrowPts, rightBrowPts].enumerated() {
            brows[i] = Self.makeBrow(pts, faceUp: -down, medial: nosePoint)
            if let brow = brows[i] {
                let mid = built[i].upper.at(0.5)
                let lowEdge = brow.center.at(0.5) - brow.up * (brow.thickness(at: 0.5) / 2)
                built[i].browGap = max((mid - lowEdge).dot(down), built[i].width * 0.25)
            } else {
                built[i].browGap = built[i].width * 0.55
            }
        }
        eyes = built

        // 嘴
        lipsOuter = face.outerLips.map(unit)
        lipsInner = face.innerLips.map(unit)
        mouthCenter = lipsInner.count >= 3 ? mkCentroid(lipsInner) : mouth
        let proj = lipsOuter.map { ($0 - mouth).dot(right) }
        mouthWidth = max((proj.max() ?? 0) - (proj.min() ?? 0), eyeDist * 0.3)
        // 唇峰中间：中线附近最靠上的点；下唇最低处：最靠下的点。
        let central = lipsOuter.filter { abs(($0 - mouth).dot(right)) < mouthWidth * 0.2 }
        let pool = central.isEmpty ? lipsOuter : central
        lipTop = pool.min { ($0 - mouth).dot(down) < ($1 - mouth).dot(down) } ?? mouth
        lipBottom = pool.max { ($0 - mouth).dot(down) < ($1 - mouth).dot(down) } ?? mouth

        // 鼻子
        let nosePts = face.nose.map(unit)
        if nosePts.count >= 3 {
            let low = nosePts.sorted { ($0 - eyeMid).dot(down) > ($1 - eyeMid).dot(down) }
            noseBase = mkCentroid(Array(low.prefix(max(2, nosePts.count / 2))))
            let lateral = nosePts.map { ($0 - eyeMid).dot(right) }
            noseWidth = max((lateral.max() ?? 0) - (lateral.min() ?? 0), eyeDist * 0.4)
        } else {
            noseBase = eyeMid + down * (mouthDist * 0.62)
            noseWidth = eyeDist * 0.55
        }
        var ridgePts: [MKVec] = []
        if let crest = face.noseCrest?.map(unit), crest.count >= 4 {
            // 前几个点沿鼻梁往下；最后两个是鼻尖两侧，不要。
            var last = -CGFloat.greatestFiniteMagnitude
            for p in crest.prefix(crest.count - 2) {
                let v = (p - eyeMid).dot(down)
                if v > last { ridgePts.append(p); last = v }
            }
        }
        if ridgePts.count < 2 {
            let tip = noseBase - down * (mouthDist * 0.08)
            ridgePts = [eyeMid, eyeMid.lerp(tip, 0.5), tip]
        }
        // 往上补到眉心。
        let top = ridgePts[0] - down * (eyeDist * 0.16)
        ridge = MKCurve(through: [top] + ridgePts, perSegment: 8)
        noseTip = ridgePts[ridgePts.count - 1]
    }

    private static func farthestPair(_ points: [MKVec]) -> (Int, Int) {
        var best = (0, 1)
        var bestDistance: CGFloat = -1
        for i in 0..<points.count {
            for j in (i + 1)..<points.count {
                let d = points[i].dist(points[j])
                if d > bestDistance { bestDistance = d; best = (i, j) }
            }
        }
        return best
    }

    private static func makeEye(_ points: [MKVec], pupil: MKVec?, other: MKVec, faceUp: MKVec) -> MKEye? {
        guard points.count >= 4 else { return nil }
        let (i, j) = farthestPair(points)
        // 离另一只眼睛近的那个角是内眼角。
        let innerIndex = points[i].dist(other) < points[j].dist(other) ? i : j
        let outerIndex = innerIndex == i ? j : i
        let inner = points[innerIndex], outer = points[outerIndex]
        let width = inner.dist(outer)
        guard width > 1e-4 else { return nil }
        let axis = (outer - inner).unit
        var up = axis.perp
        if up.dot(faceUp) < 0 { up = -up }
        var upperPts: [(CGFloat, MKVec)] = []
        var lowerPts: [(CGFloat, MKVec)] = []
        for (k, p) in points.enumerated() where k != innerIndex && k != outerIndex {
            let t = (p - inner).dot(axis)
            if (p - inner).dot(up) >= 0 { upperPts.append((t, p)) } else { lowerPts.append((t, p)) }
        }
        upperPts.sort { $0.0 < $1.0 }
        lowerPts.sort { $0.0 < $1.0 }
        // 关键点偶尔全落在一侧：给另一侧补一个对称的点，保证上下眼睑都有弧度。
        if upperPts.isEmpty { upperPts = [(width / 2, inner.lerp(outer, 0.5) + up * (width * 0.14))] }
        if lowerPts.isEmpty { lowerPts = [(width / 2, inner.lerp(outer, 0.5) - up * (width * 0.1))] }
        let upper = MKCurve(through: [inner] + upperPts.map(\.1) + [outer])
        let lower = MKCurve(through: [inner] + lowerPts.map(\.1) + [outer])
        var outline: [MKVec] = []
        for s in 0...12 { outline.append(upper.at(CGFloat(s) / 12)) }
        for s in stride(from: 11, through: 1, by: -1) { outline.append(lower.at(CGFloat(s) / 12)) }
        var center = pupil ?? mkCentroid(points)
        // 瞳孔点偶尔会飘到眼睛外面，这时退回眼睛中心。
        if center.dist(mkCentroid(points)) > width * 0.45 { center = mkCentroid(points) }
        return MKEye(inner: inner, outer: outer, upper: upper, lower: lower, center: center, width: width,
                     axis: axis, up: up, outline: outline)
    }

    private static func makeBrow(_ points: [MKVec], faceUp: MKVec, medial: MKVec) -> MKBrow? {
        guard points.count >= 3 else { return nil }
        let (i, j) = farthestPair(points)
        // 离脸中线近的那头是眉头。
        let headIndex = points[i].dist(medial) < points[j].dist(medial) ? i : j
        let tailIndex = headIndex == i ? j : i
        let head = points[headIndex], tail = points[tailIndex]
        let length = head.dist(tail)
        guard length > 1e-4 else { return nil }
        let axis = (tail - head).unit
        var up = axis.perp
        if up.dot(faceUp) < 0 { up = -up }
        // 把点按沿眉毛方向的位置排好，分成上缘和下缘两条折线。
        var upperPts: [(CGFloat, CGFloat)] = [(0, 0), (length, 0)]
        var lowerPts: [(CGFloat, CGFloat)] = [(0, 0), (length, 0)]
        for (k, p) in points.enumerated() where k != headIndex && k != tailIndex {
            let t = (p - head).dot(axis), v = (p - head).dot(up)
            if v >= 0 { upperPts.append((t, v)) } else { lowerPts.append((t, v)) }
        }
        upperPts.sort { $0.0 < $1.0 }
        lowerPts.sort { $0.0 < $1.0 }
        func value(_ list: [(CGFloat, CGFloat)], at t: CGFloat) -> CGFloat {
            guard var previous = list.first else { return 0 }
            for item in list.dropFirst() {
                if t <= item.0 {
                    let span = item.0 - previous.0
                    return span > 0 ? previous.1 + (item.1 - previous.1) * (t - previous.0) / span : item.1
                }
                previous = item
            }
            return previous.1
        }
        var centerPts: [MKVec] = []
        var thickness: [CGFloat] = []
        let count = 9
        // 关键点只有一条线（老版本的关键点没有上下缘）时，粗细按眉长估一个。
        let fallback = length * 0.11
        for s in 0..<count {
            let f = CGFloat(s) / CGFloat(count - 1)
            let t = f * length
            let hi = value(upperPts, at: t), lo = value(lowerPts, at: t)
            centerPts.append(head + axis * t + up * ((hi + lo) / 2))
            thickness.append(hi - lo)
        }
        let measured = thickness.max() ?? 0
        if measured < length * 0.04 {
            thickness = (0..<count).map { s in
                let f = CGFloat(s) / CGFloat(count - 1)
                return fallback * (1 - 0.75 * mkSmoothstep(0.45, 1, f))
            }
        }
        return MKBrow(center: MKCurve(through: centerPts, perSegment: 4), thickness: thickness,
                      length: length, axis: axis, up: up)
    }
}
