import CoreGraphics
import CoreImage
import Foundation
import simd

// MARK: - 项目清单

/// 面部重塑的分类（底部确认栏里的那一排标签）。
enum ReshapeCategory: String, CaseIterable, Identifiable {
    case expression, pose, proportion, shape, eyes, lips, nose, brows

    var id: String { rawValue }

    var title: String {
        switch self {
        case .expression: return "表情管理"
        case .pose: return "3D塑颜"
        case .proportion: return "比例"
        case .shape: return "脸型"
        case .eyes: return "眼睛"
        case .lips: return "嘴唇"
        case .nose: return "鼻子"
        case .brows: return "眉毛"
        }
    }

    /// 这一类里在本机完成的项目（不含 AI 项目）。
    var keys: [ReshapeKey] { ReshapeKey.allCases.filter { $0.category == self } }
}

/// 一只眼 / 一条眉可以单独调。左右按画面上的左右算。
enum ReshapeSide: String, CaseIterable, Identifiable {
    case both, left, right

    var id: String { rawValue }

    var title: String {
        switch self {
        case .both: return "整体"
        case .left: return "左"
        case .right: return "右"
        }
    }
}

/// 在本机完成的重塑项目。除了去眼袋、祛红血丝、白牙是调颜色，其余都是按五官关键点做局部变形。
enum ReshapeKey: String, CaseIterable, Identifiable, Codable {
    // 3D 塑颜
    case headTilt
    // 比例
    case smallHead, faceInHead, crown, forehead, midFace, philtrum, lowerFace, shortFace
    // 脸型
    case smartShape, faceWidth, smallFace, smoothFace, cheekbone, chinLength, pointedChin, vFace, jawAngle, jawLine, hairline
    // 眼睛
    case eyeSize, eyeVertical, eyeHeight, eyeLength, eyeDistance, eyeLift, irisSize, lowerLid, eyeTail, eyeTilt
    case eyeBags, redEye, innerCorner, outerCorner
    // 嘴唇
    case lipSize, lipWidth, lipVertical, lipTilt, lipHorizontal, mLip, fullLip, smile, teeth
    // 鼻子
    case noseSize, noseLift, noseWing, noseRoot, noseBridge, noseTip
    // 眉毛
    case browVertical, browThickness, browLength, browDistance, browHeadDistance, browTilt, browPeak

    var id: String { rawValue }

    var category: ReshapeCategory {
        switch self {
        case .headTilt: return .pose
        case .smallHead, .faceInHead, .crown, .forehead, .midFace, .philtrum, .lowerFace, .shortFace: return .proportion
        case .smartShape, .faceWidth, .smallFace, .smoothFace, .cheekbone, .chinLength, .pointedChin, .vFace,
             .jawAngle, .jawLine, .hairline: return .shape
        case .eyeSize, .eyeVertical, .eyeHeight, .eyeLength, .eyeDistance, .eyeLift, .irisSize, .lowerLid, .eyeTail,
             .eyeTilt, .eyeBags, .redEye, .innerCorner, .outerCorner: return .eyes
        case .lipSize, .lipWidth, .lipVertical, .lipTilt, .lipHorizontal, .mLip, .fullLip, .smile, .teeth: return .lips
        case .noseSize, .noseLift, .noseWing, .noseRoot, .noseBridge, .noseTip: return .nose
        case .browVertical, .browThickness, .browLength, .browDistance, .browHeadDistance, .browTilt, .browPeak: return .brows
        }
    }

    var title: String {
        switch self {
        case .headTilt: return "倾斜"
        case .smallHead: return "小头"
        case .faceInHead: return "头包脸"
        case .crown: return "颅顶"
        case .forehead: return "额头"
        case .midFace: return "中庭"
        case .philtrum: return "人中"
        case .lowerFace: return "下庭"
        case .shortFace: return "短脸"
        case .smartShape: return "智能脸型"
        case .faceWidth: return "脸宽"
        case .smallFace: return "小脸"
        case .smoothFace: return "面部流畅"
        case .cheekbone: return "颧骨"
        case .chinLength: return "下巴长短"
        case .pointedChin: return "尖下巴"
        case .vFace: return "V脸"
        case .jawAngle: return "下颌角"
        case .jawLine: return "下颌线"
        case .hairline: return "发际线"
        case .eyeSize: return "大小"
        case .eyeVertical: return "上下"
        case .eyeHeight: return "眼高"
        case .eyeLength: return "长度"
        case .eyeDistance: return "眼距"
        case .eyeLift: return "提肌"
        case .irisSize: return "眼瞳大小"
        case .lowerLid: return "眼睑下至"
        case .eyeTail: return "眼尾上扬"
        case .eyeTilt: return "倾斜"
        case .eyeBags: return "去眼袋"
        case .redEye: return "祛红血丝"
        case .innerCorner: return "内眼角"
        case .outerCorner: return "外眼角"
        case .lipSize: return "大小"
        case .lipWidth: return "宽度"
        case .lipVertical: return "上下"
        case .lipTilt: return "倾斜"
        case .lipHorizontal: return "左右"
        case .mLip: return "M唇"
        case .fullLip: return "丰唇"
        case .smile: return "微笑"
        case .teeth: return "白牙"
        case .noseSize: return "大小"
        case .noseLift: return "提升"
        case .noseWing: return "鼻翼"
        case .noseRoot: return "山根"
        case .noseBridge: return "鼻梁"
        case .noseTip: return "鼻尖"
        case .browVertical: return "上下"
        case .browThickness: return "粗细"
        case .browLength: return "长短"
        case .browDistance: return "间距"
        case .browHeadDistance: return "眉头间距"
        case .browTilt: return "倾斜"
        case .browPeak: return "眉峰"
        }
    }

    var symbol: String {
        switch self {
        case .headTilt: return "rotate.right"
        case .smallHead: return "arrow.down.right.and.arrow.up.left"
        case .faceInHead: return "face.dashed"
        case .crown: return "arrow.up.to.line"
        case .forehead: return "rectangle.tophalf.inset.filled"
        case .midFace: return "rectangle.center.inset.filled"
        case .philtrum: return "arrow.up.and.down"
        case .lowerFace: return "rectangle.bottomhalf.inset.filled"
        case .shortFace: return "arrow.down.and.line.horizontal.and.arrow.up"
        case .smartShape: return "face.smiling"
        case .faceWidth: return "arrow.left.and.right"
        case .smallFace: return "arrow.down.right.and.arrow.up.left"
        case .smoothFace: return "circle.dashed"
        case .cheekbone: return "arrow.right.and.line.vertical.and.arrow.left"
        case .chinLength: return "arrow.up.and.down"
        case .pointedChin: return "arrowtriangle.down"
        case .vFace: return "chevron.down"
        case .jawAngle: return "angle"
        case .jawLine: return "line.diagonal"
        case .hairline: return "arrow.down.to.line"
        case .eyeSize: return "arrow.up.left.and.arrow.down.right"
        case .eyeVertical: return "arrow.up.and.down"
        case .eyeHeight: return "eye"
        case .eyeLength: return "arrow.left.and.right"
        case .eyeDistance: return "arrow.left.and.line.vertical.and.arrow.right"
        case .eyeLift: return "eye.fill"
        case .irisSize: return "circle.circle"
        case .lowerLid: return "arrow.down.to.line"
        case .eyeTail: return "arrow.up.right"
        case .eyeTilt: return "rotate.right"
        case .eyeBags: return "eye.trianglebadge.exclamationmark"
        case .redEye: return "drop"
        case .innerCorner: return "arrow.right.to.line"
        case .outerCorner: return "arrow.left.to.line"
        case .lipSize: return "arrow.up.left.and.arrow.down.right"
        case .lipWidth: return "arrow.left.and.right"
        case .lipVertical: return "arrow.up.and.down"
        case .lipTilt: return "rotate.right"
        case .lipHorizontal: return "arrow.left.arrow.right"
        case .mLip: return "mouth"
        case .fullLip: return "mouth.fill"
        case .smile: return "face.smiling"
        case .teeth: return "sparkles"
        case .noseSize: return "arrow.up.left.and.arrow.down.right"
        case .noseLift: return "arrow.up"
        case .noseWing: return "arrow.right.and.line.vertical.and.arrow.left"
        case .noseRoot: return "arrow.up.to.line"
        case .noseBridge: return "line.diagonal"
        case .noseTip: return "nose"
        case .browVertical: return "arrow.up.and.down"
        case .browThickness: return "lineweight"
        case .browLength: return "arrow.left.and.right"
        case .browDistance: return "arrow.left.and.line.vertical.and.arrow.right"
        case .browHeadDistance: return "arrow.left.arrow.right"
        case .browTilt: return "rotate.right"
        case .browPeak: return "eyebrow"
        }
    }

    /// 调颜色的三项只有 0...100，其余都是 -100...100。
    var bipolar: Bool {
        switch self {
        case .eyeBags, .redEye, .teeth: return false
        default: return true
        }
    }

    /// 可以分左右单独调的项目（眼睛和眉毛）。
    var paired: Bool {
        switch self {
        case .eyeSize, .eyeVertical, .eyeHeight, .eyeLength, .eyeDistance, .eyeLift, .irisSize, .lowerLid, .eyeTail,
             .eyeTilt, .eyeBags, .redEye, .innerCorner, .outerCorner,
             .browVertical, .browThickness, .browLength, .browDistance, .browHeadDistance, .browTilt, .browPeak:
            return true
        default:
            return false
        }
    }

    /// 滑块两头各代表什么（左, 右）。
    var ends: (String, String) {
        switch self {
        case .headTilt: return ("左歪", "右歪")
        case .smallHead: return ("大", "小")
        case .faceInHead: return ("脸大", "脸小")
        case .crown: return ("低", "高")
        case .forehead: return ("短", "长")
        case .midFace, .philtrum, .lowerFace: return ("长", "短")
        case .shortFace: return ("长", "短")
        case .smartShape: return ("宽", "瘦")
        case .faceWidth: return ("窄", "宽")
        case .smallFace: return ("大", "小")
        case .smoothFace: return ("", "流畅")
        case .cheekbone: return ("宽", "收")
        case .chinLength: return ("短", "长")
        case .pointedChin: return ("圆", "尖")
        case .vFace: return ("", "V")
        case .jawAngle: return ("宽", "收")
        case .jawLine: return ("松", "紧")
        case .hairline: return ("高", "低")
        case .eyeSize, .lipSize, .noseSize: return ("小", "大")
        case .eyeVertical, .lipVertical, .browVertical: return ("下", "上")
        case .eyeHeight: return ("扁", "圆")
        case .eyeLength, .browLength: return ("短", "长")
        case .eyeDistance, .browDistance, .browHeadDistance: return ("近", "远")
        case .eyeLift: return ("", "提")
        case .irisSize: return ("小", "大")
        case .lowerLid: return ("", "下至")
        case .eyeTail: return ("下垂", "上扬")
        case .eyeTilt, .browTilt: return ("下垂", "上扬")
        case .eyeBags, .redEye, .teeth: return ("", "")
        case .innerCorner, .outerCorner: return ("收", "开")
        case .lipWidth: return ("窄", "宽")
        case .lipTilt: return ("左高", "右高")
        case .lipHorizontal: return ("左", "右")
        case .mLip: return ("平", "M")
        case .fullLip: return ("薄", "丰")
        case .smile: return ("下", "上扬")
        case .noseLift: return ("长", "提")
        case .noseWing, .noseRoot, .noseBridge: return ("宽", "窄")
        case .noseTip: return ("垂", "翘")
        case .browThickness: return ("细", "粗")
        case .browPeak: return ("平", "高")
        }
    }

    var isColor: Bool { !bipolar }
}

// MARK: - 数值

/// 一个项目的数值：-1...1。能分左右的项目两边各存一个，其余两个值相同。
struct ReshapeValue: Equatable {
    var left: Float = 0
    var right: Float = 0

    var isZero: Bool { left == 0 && right == 0 }
}

/// 一张脸的全部调整。
struct ReshapeFace: Equatable {
    var info: FaceInfo
    var values: [ReshapeKey: ReshapeValue] = [:]

    var isIdentity: Bool { values.isEmpty }

    func value(_ key: ReshapeKey, side: ReshapeSide = .both) -> Float {
        guard let v = values[key] else { return 0 }
        switch side {
        case .both: return abs(v.left) >= abs(v.right) ? v.left : v.right
        case .left: return v.left
        case .right: return v.right
        }
    }

    mutating func set(_ key: ReshapeKey, side: ReshapeSide, to raw: Float) {
        let value = min(max(raw, key.bipolar ? -1 : 0), 1)
        var v = values[key] ?? ReshapeValue()
        switch key.paired ? side : .both {
        case .both: v.left = value; v.right = value
        case .left: v.left = value
        case .right: v.right = value
        }
        values[key] = v.isZero ? nil : v
    }
}

/// "变形还原"的一笔：涂过的地方变形被抹回去，颜色类的调整不受影响。
struct ReshapeRestoreStroke: Equatable {
    /// 归一化图片坐标（原点左上角）。
    var points: [CGPoint]
    /// 笔刷半径，以图片长边为 1。
    var radius: CGFloat
}

// MARK: - 脸的几何

private typealias V2 = SIMD2<Float>

/// 一张脸自己的坐标系和各个部位的位置。
/// 全部以"图片长边 = 1"为单位（y 朝下），所以同一组数值在预览图和原图上效果一致。
private struct FaceGeometry {
    struct Eye {
        var c: V2
        var inner: V2
        var outer: V2
        /// 从内眼角指向外眼角的单位向量，以及和它垂直、朝下的单位向量。
        var a: V2
        var n: V2
        var w: Float
        var pupil: V2
        var polygon: [V2]
    }

    struct Brow {
        var c: V2
        var inner: V2
        var outer: V2
        var a: V2
        var n: V2
        var w: Float
    }

    struct Mouth {
        var c: V2
        var left: V2
        var right: V2
        var a: V2
        var n: V2
        var w: Float
        /// 上唇最高处、下唇最低处在脸坐标系里的 y。
        var top: Float
        var bottom: Float
        var inner: [V2]
    }

    /// 两眼中点。
    let origin: V2
    /// 脸的横轴（沿两眼连线）和纵轴（朝下巴）。
    let ex: V2
    let ey: V2
    /// 脸宽、两眼间距、眼睛到下巴的距离。
    let fw: Float
    let ed: Float
    let hc: Float
    /// 画面左边的那只眼在前。
    var eyes: [Eye] = []
    var brows: [Brow?] = [nil, nil]
    var mouth: Mouth?
    var noseBase: V2
    var noseTip: V2
    var noseRoot: V2
    var noseWidth: Float
    /// 鼻子的关键点是不是落在该在的位置；不是的话鼻子的项目不做，上面几个值只是按比例估的。
    var noseValid = true
    /// 下颌轮廓，从画面左到右。
    var jaw: [V2] = []
    var chin: V2
    /// 下半张脸中线的横坐标（脸坐标系）。侧着脸时它不在两眼正中。
    var axisX: Float

    func world(_ x: Float, _ y: Float) -> V2 { origin + ex * x + ey * y }

    func local(_ p: V2) -> V2 {
        let d = p - origin
        return V2(simd_dot(d, ex), simd_dot(d, ey))
    }

    /// 下颌线上的点：t = 0 是画面左侧与眼睛齐平的那一端，0.5 在下巴，1 是右侧那一端。
    func jawPoint(_ t: Float) -> V2 {
        guard jaw.count >= 2 else { return chin }
        let f = min(max(t, 0), 1) * Float(jaw.count - 1)
        let i = min(Int(f), jaw.count - 2)
        return jaw[i] + (jaw[i + 1] - jaw[i]) * (f - Float(i))
    }

    init?(_ face: FaceInfo, aspect: V2) {
        guard face.hasReliableLandmarks else { return nil }
        func P(_ p: CGPoint) -> V2 { V2(Float(p.x) * aspect.x, Float(p.y) * aspect.y) }
        func centroid(_ points: [V2]) -> V2 {
            points.reduce(V2(0, 0), +) / Float(max(points.count, 1))
        }
        var a = face.leftEye.map(P), b = face.rightEye.map(P)
        var browA = face.leftBrow.map(P), browB = face.rightBrow.map(P)
        var pupilA = face.leftPupil.map(P), pupilB = face.rightPupil.map(P)
        let contour = face.contour.map(P)
        var ca = centroid(a), cb = centroid(b)
        if ca.x > cb.x {
            swap(&a, &b); swap(&ca, &cb); swap(&browA, &browB); swap(&pupilA, &pupilB)
        }
        let distance = simd_length(cb - ca)
        guard distance > 1e-5 else { return nil }
        var x = (cb - ca) / distance
        var y = V2(-x.y, x.x)
        let o = (ca + cb) / 2
        // 下巴应该在纵轴的正方向；倒过来的脸把两根轴都翻一下。
        let below = contour.reduce(Float(0)) { $0 + simd_dot($1 - o, y) }
        if below < 0 {
            x = -x; y = -y
            swap(&a, &b); swap(&ca, &cb); swap(&browA, &browB); swap(&pupilA, &pupilB)
        }
        origin = o
        ex = x
        ey = y
        ed = distance
        func L(_ p: V2) -> V2 { V2(simd_dot(p - o, x), simd_dot(p - o, y)) }

        // 下颌线：按脸坐标系从左到右排。
        var line = contour
        if let first = line.first, let last = line.last, L(first).x > L(last).x { line.reverse() }
        jaw = line
        let xs = line.map { L($0).x }
        fw = max((xs.max() ?? 0) - (xs.min() ?? 0), distance * 1.6)
        let lowest = line.max { L($0).y < L($1).y } ?? o + y * distance * 2
        chin = lowest
        hc = max(L(lowest).y, distance * 1.2)
        // 整体比例先过一遍：眼距、脸宽、眼睛到下巴的距离对不上，说明这组关键点是乱的（比如在模拟器里），整张脸都不做。
        let chinDepth = L(lowest).y
        guard chinDepth > distance * 0.9, chinDepth < distance * 3.2,
              fw > distance * 1.25, fw < distance * 4.5 else { return nil }

        func eye(_ points: [V2], center: V2, pupil: V2?) -> Eye {
            let sorted = points.sorted { L($0).x < L($1).x }
            let p0 = sorted.first ?? center, p1 = sorted.last ?? center
            // 离脸中线近的是内眼角。
            let innerFirst = abs(L(p0).x) < abs(L(p1).x)
            let inner = innerFirst ? p0 : p1, outer = innerFirst ? p1 : p0
            let w = max(simd_length(outer - inner), distance * 0.2)
            let axis = (outer - inner) / max(simd_length(outer - inner), 1e-6)
            var normal = V2(-axis.y, axis.x)
            if simd_dot(normal, y) < 0 { normal = -normal }
            return Eye(c: center, inner: inner, outer: outer, a: axis, n: normal, w: w,
                       pupil: pupil ?? center, polygon: points)
        }
        eyes = [eye(a, center: ca, pupil: pupilA), eye(b, center: cb, pupil: pupilB)]
        guard eyes.allSatisfy({ $0.w > distance * 0.12 && $0.w < distance * 0.95 }) else { return nil }

        func brow(_ points: [V2]) -> Brow? {
            guard points.count >= 3 else { return nil }
            let sorted = points.sorted { L($0).x < L($1).x }
            let p0 = sorted[0], p1 = sorted[sorted.count - 1]
            let innerFirst = abs(L(p0).x) < abs(L(p1).x)
            let inner = innerFirst ? p0 : p1, outer = innerFirst ? p1 : p0
            let length = simd_length(outer - inner)
            guard length > distance * 0.15 else { return nil }
            let axis = (outer - inner) / length
            var normal = V2(-axis.y, axis.x)
            if simd_dot(normal, y) < 0 { normal = -normal }
            return Brow(c: centroid(points), inner: inner, outer: outer, a: axis, n: normal, w: length)
        }
        brows = [brow(browA), brow(browB)]
        // 眉毛应该在同一侧眼睛的正上方不远处，否则这条眉毛不做。
        for i in 0..<2 {
            guard let item = brows[i] else { continue }
            let lb = L(item.c), le = L(eyes[i].c)
            let above = le.y - lb.y
            if lb.x * le.x <= 0 || above < distance * 0.06 || above > distance * 0.9
                || item.w < eyes[i].w * 0.4 || item.w > eyes[i].w * 3 {
                brows[i] = nil
            }
        }

        let lips = face.outerLips.map(P)
        if lips.count >= 6 {
            let sorted = lips.sorted { L($0).x < L($1).x }
            let left = sorted[0], right = sorted[sorted.count - 1]
            let w = simd_length(right - left)
            if w > distance * 0.2 {
                let axis = (right - left) / w
                var normal = V2(-axis.y, axis.x)
                if simd_dot(normal, y) < 0 { normal = -normal }
                let ys = lips.map { L($0).y }
                let top = ys.min() ?? 0, bottom = ys.max() ?? 0
                let mid = (left + right) / 2
                let c = o + x * L(mid).x + y * ((top + bottom) / 2)
                // 嘴应该在鼻子下面、下巴上面、靠近脸的中线，否则嘴唇的项目不做。
                let lc = L(c)
                if lc.y > hc * 0.4, lc.y < hc * 0.97, abs(lc.x) < fw * 0.4, w > fw * 0.12, w < fw * 0.8 {
                    mouth = Mouth(c: c, left: left, right: right, a: axis, n: normal, w: w, top: top, bottom: bottom,
                                  inner: face.innerLips.map(P))
                }
            }
        }

        let nose = face.nose.map(P)
        if nose.count >= 5 {
            let ys = nose.map { L($0).y }
            let top = ys.min() ?? 0, bottom = ys.max() ?? 0
            let low = nose.filter { L($0).y >= bottom - (bottom - top) * 0.3 }
            let base = centroid(low)
            let nx = nose.map { L($0).x }
            let width = (nx.max() ?? 0) - (nx.min() ?? 0)
            let lb = L(base)
            if lb.y > hc * 0.18, lb.y < hc * 0.75, abs(lb.x) < fw * 0.35, width > fw * 0.06, width < fw * 0.6 {
                noseBase = base
                noseWidth = max(width, distance * 0.3)
            } else {
                noseBase = o + y * (hc * 0.42)
                noseWidth = distance * 0.55
                noseValid = false
            }
        } else {
            noseBase = o + y * (hc * 0.42)
            noseWidth = distance * 0.55
            noseValid = false
        }
        noseRoot = o + y * (hc * 0.03)
        var tip = noseBase - y * (hc * 0.06)
        if noseValid, let crest = face.noseCrest?.map(P), crest.count >= 4 {
            // 鼻梁线的最后两个点在鼻尖两侧，鼻尖取前面那一段里最低的点。
            let ridge = crest.dropLast(2)
            if let lowestRidge = ridge.max(by: { L($0).y < L($1).y }) { tip = lowestRidge }
            if let first = ridge.first { noseRoot = first }
        }
        noseTip = tip
        axisX = (L(noseBase).x + L(chin).x) / 2
    }
}

// MARK: - 变形的基本动作

/// 局部变形：在一个椭圆范围里平移、缩放、单向拉伸或旋转，往外平滑衰减到 0。
private struct LocalWarp {
    enum Kind {
        case translate(V2)
        /// 围绕 c 等比缩放。
        case scale(Float)
        /// 只沿这根轴（单位向量）缩放。
        case stretch(V2, Float)
        /// 围绕 c 旋转（弧度，画面上顺时针为正）。
        case rotate(Float)
    }

    /// 权重椭圆：中心、长轴方向（单位向量）、两个半径。
    var wc: V2
    var ax: V2
    var rx: Float
    var ry: Float
    /// 缩放和旋转的中心。
    var c: V2
    var kind: Kind
    /// 椭圆里这么大比例的中间部分整体一起动（权重 = 1），之外才开始衰减。
    var plateau: Float = 0

    var radius: Float { max(rx, ry) }

    /// 这个动作让位于 p 的像素从哪里取样（返回相对 p 的偏移）。
    @inline(__always)
    func offset(_ p: V2) -> V2 {
        let d = p - wc
        let u = simd_dot(d, ax) / rx
        let v = (d.y * ax.x - d.x * ax.y) / ry
        let t2 = u * u + v * v
        if t2 >= 1 { return V2(0, 0) }
        let t = t2.squareRoot()
        var w: Float = 1
        if t > plateau {
            let s = (t - plateau) / (1 - plateau)
            w = 1 - s * s * (3 - 2 * s)
        }
        switch kind {
        case .translate(let shift):
            return shift * (-w)
        case .scale(let factor):
            let k = 1 + (factor - 1) * w
            return (p - c) * (1 / k - 1)
        case .stretch(let axis, let factor):
            let k = 1 + (factor - 1) * w
            return axis * (simd_dot(p - c, axis) * (1 / k - 1))
        case .rotate(let angle):
            let r = p - c
            let sn = sinf(-angle * w), cs = cosf(-angle * w)
            return V2(r.x * cs - r.y * sn, r.x * sn + r.y * cs) - r
        }
    }
}

/// 一段一段挪：纵向在 y0→y1 之间从不动过渡到整体挪动，一直保持到 y2，再在 y2→y3 之间还原；
/// 横向离中线 x0 以内完全生效，到 x1 衰减为 0。用来改中庭、人中、下庭、下巴这些"长度"。
private struct BandShift {
    var origin: V2
    var ex: V2
    var ey: V2
    var y0: Float
    var y1: Float
    var y2: Float
    var y3: Float
    var xc: Float
    var x0: Float
    var x1: Float
    var shift: V2

    var center: V2 { origin + ex * xc + ey * ((y0 + y3) / 2) }
    var radius: Float { hypotf((y3 - y0) / 2, x1) }

    @inline(__always)
    private func smooth(_ a: Float, _ b: Float, _ v: Float) -> Float {
        let s = min(max((v - a) / max(b - a, 1e-6), 0), 1)
        return s * s * (3 - 2 * s)
    }

    @inline(__always)
    func offset(_ p: V2) -> V2 {
        let d = p - origin
        let y = simd_dot(d, ey)
        if y <= y0 || y >= y3 { return V2(0, 0) }
        let x = abs(simd_dot(d, ex) - xc)
        if x >= x1 { return V2(0, 0) }
        let g = smooth(y0, y1, y) * (1 - smooth(y2, y3, y)) * (1 - smooth(x0, x1, x))
        return shift * (-g)
    }
}

private enum Warp {
    case local(LocalWarp)
    case band(BandShift)

    var center: V2 {
        switch self {
        case .local(let w): return w.wc
        case .band(let b): return b.center
        }
    }

    var radius: Float {
        switch self {
        case .local(let w): return w.radius
        case .band(let b): return b.radius
        }
    }
}

// MARK: - 把滑块数值变成变形动作

private enum ReshapePlan {
    /// 一张脸上所有起作用的变形动作。
    static func warps(for face: ReshapeFace, geo g: FaceGeometry) -> [Warp] {
        var out: [Warp] = []
        let fw = g.fw, hc = g.hc, ed = g.ed
        func v(_ key: ReshapeKey) -> Float { face.values[key]?.left ?? 0 }
        func side(_ key: ReshapeKey, _ index: Int) -> Float {
            guard let value = face.values[key] else { return 0 }
            return index == 0 ? value.left : value.right
        }
        func local(_ wc: V2, axis: V2? = nil, rx: Float, ry: Float, c: V2? = nil, plateau: Float = 0,
                   _ kind: LocalWarp.Kind) {
            out.append(.local(LocalWarp(wc: wc, ax: axis ?? g.ex, rx: max(rx, 1e-5), ry: max(ry, 1e-5),
                                        c: c ?? wc, kind: kind, plateau: plateau)))
        }
        func band(y0: Float, y1: Float, y2: Float, y3: Float, x0: Float, x1: Float, shift: Float, xc: Float? = nil) {
            guard y1 > y0, y3 > y2 else { return }
            out.append(.band(BandShift(origin: g.origin, ex: g.ex, ey: g.ey, y0: y0, y1: y1, y2: max(y2, y1), y3: y3,
                                       xc: xc ?? g.axisX, x0: x0, x1: x1, shift: g.ey * shift)))
        }
        let noseY = g.local(g.noseBase).y
        let chinY = g.local(g.chin).y
        let mouthTop = g.mouth?.top ?? noseY + (chinY - noseY) * 0.3
        let mouthBottom = g.mouth?.bottom ?? noseY + (chinY - noseY) * 0.55
        let neck0 = chinY + hc * 0.12, neck1 = chinY + hc * 0.75
        /// 把下颌线上的一点朝脸里面推。
        func pushJaw(_ t: Float, amount: Float, radius: Float, toward: V2? = nil) {
            guard amount != 0 else { return }
            let p = g.jawPoint(t)
            let target = toward ?? g.world(g.axisX, g.local(p).y)
            let d = target - p
            let length = simd_length(d)
            guard length > 1e-6 else { return }
            local(p, rx: radius, ry: radius, plateau: 0.15, .translate(d / length * amount))
        }

        // ---- 3D 塑颜
        let tilt = v(.headTilt)
        if tilt != 0 {
            // 以脖子为轴转头；权重盖住整个头（连头发），到肩膀衰减为 0。
            local(g.world(g.axisX * 0.5, hc * 0.05), rx: fw * 1.5, ry: hc * 2.0,
                  c: g.world(g.axisX, chinY + hc * 0.25), plateau: 0.4, .rotate(tilt * 0.09))
        }

        // ---- 比例
        // 这一组和下面的脸型，范围都盖到头发、耳朵外面，再很宽地过渡到 0：
        // 脸动的时候头的轮廓跟着一起动，而不是脸在一个不动的头里面滑。
        if v(.smallHead) != 0 {
            // 整个头朝脖子收，肩膀不动。
            local(g.world(g.axisX * 0.5, hc * 0.15), rx: fw * 1.6, ry: hc * 2.3,
                  c: g.world(g.axisX, chinY + hc * 0.3), plateau: 0.4, .scale(1 - 0.08 * v(.smallHead)))
        }
        if v(.faceInHead) != 0 {
            // 只收五官和脸颊这一圈，头发的外轮廓基本不动。
            local(g.world(g.axisX * 0.5, hc * 0.35), rx: fw * 0.74, ry: hc * 1.2, plateau: 0.2,
                  .scale(1 - 0.07 * v(.faceInHead)))
        }
        if v(.crown) != 0 {
            local(g.world(0, -hc * 1.0), rx: fw * 1.15, ry: hc * 0.8, plateau: 0.15,
                  .translate(g.ey * (-0.08 * hc * v(.crown))))
        }
        if v(.forehead) != 0 {
            // 发际线以上整体挪，眉毛到发际线之间均匀拉长或压短。
            band(y0: -hc * 1.95, y1: -hc * 1.3, y2: -hc * 0.7, y3: -hc * 0.2, x0: fw * 0.75, x1: fw * 1.5,
                 shift: -0.07 * hc * v(.forehead), xc: 0)
        }
        if v(.midFace) != 0 {
            band(y0: hc * 0.06, y1: noseY, y2: neck0, y3: neck1, x0: fw * 0.7, x1: fw * 1.5,
                 shift: -0.06 * hc * v(.midFace))
        }
        if v(.philtrum) != 0 {
            band(y0: noseY, y1: mouthTop, y2: neck0, y3: neck1, x0: fw * 0.7, x1: fw * 1.5,
                 shift: -0.3 * (mouthTop - noseY) * v(.philtrum))
        }
        if v(.lowerFace) != 0 {
            band(y0: noseY, y1: chinY, y2: neck0, y3: neck1, x0: fw * 0.7, x1: fw * 1.5,
                 shift: -0.07 * hc * v(.lowerFace))
        }
        if v(.shortFace) != 0 {
            band(y0: -hc * 0.5, y1: chinY, y2: neck0, y3: neck1, x0: fw * 0.75, x1: fw * 1.5,
                 shift: -0.09 * hc * v(.shortFace))
        }

        // ---- 脸型
        let smart = v(.smartShape), flow = v(.smoothFace)
        let width = v(.faceWidth) - 0.45 * smart
        if width != 0 {
            // 整个头一起变窄 / 变宽：脸颊到下颌这一段最明显，额头、太阳穴、耳朵和头发跟着走，
            // 往头顶和脖子逐渐减弱。拉满是头宽的 8% 左右。
            let c = g.world(g.axisX * 0.5, hc * 0.4)
            local(c, rx: fw * 1.6, ry: hc * 2.0, c: c, plateau: 0.35, .stretch(g.ex, 1 + 0.08 * width))
        }
        let small = v(.smallFace) + 0.25 * smart
        if small != 0 {
            // 整张脸往中间收一点，头发的外轮廓跟着收一半左右。
            local(g.world(g.axisX * 0.5, hc * 0.35), rx: fw * 1.2, ry: hc * 1.6, c: g.world(g.axisX * 0.6, hc * 0.25),
                  plateau: 0.3, .scale(1 - 0.07 * small))
        }
        let cheek = v(.cheekbone) + 0.25 * smart + 0.6 * flow
        if cheek != 0 {
            pushJaw(0.07, amount: 0.035 * fw * cheek, radius: fw * 0.42)
            pushJaw(0.93, amount: 0.035 * fw * cheek, radius: fw * 0.42)
        }
        if flow != 0 {
            // 太阳穴稍微往外补一点，轮廓从额头到脸颊就顺了。
            for sign in [Float(-1), 1] {
                let p = g.world(sign * fw * 0.5, -hc * 0.3)
                local(p, rx: fw * 0.32, ry: fw * 0.32, plateau: 0.15, .translate(g.ex * (sign * 0.018 * fw * flow)))
            }
        }
        let vface = v(.vFace) + 0.45 * smart
        if vface != 0 {
            let target = g.world(g.axisX, hc * 0.3)
            pushJaw(0.3, amount: 0.045 * fw * vface, radius: fw * 0.38, toward: target)
            pushJaw(0.7, amount: 0.045 * fw * vface, radius: fw * 0.38, toward: target)
            pushJaw(0.4, amount: 0.02 * fw * vface, radius: fw * 0.3, toward: target)
            pushJaw(0.6, amount: 0.02 * fw * vface, radius: fw * 0.3, toward: target)
        }
        let angle = v(.jawAngle) + 0.3 * smart + 0.5 * flow
        if angle != 0 {
            pushJaw(0.2, amount: 0.045 * fw * angle, radius: fw * 0.34)
            pushJaw(0.8, amount: 0.045 * fw * angle, radius: fw * 0.34)
        }
        if v(.jawLine) != 0 {
            let target = g.world(g.axisX, hc * 0.45)
            for t in [Float(0.3), 0.38, 0.62, 0.7] {
                pushJaw(t, amount: 0.022 * fw * v(.jawLine), radius: fw * 0.24, toward: target)
            }
        }
        if v(.chinLength) != 0 {
            band(y0: mouthBottom, y1: chinY, y2: neck0, y3: neck1, x0: fw * 0.2, x1: fw * 0.8,
                 shift: 0.06 * hc * v(.chinLength))
        }
        if v(.pointedChin) != 0 {
            let c = g.chin - g.ey * (hc * 0.08)
            local(c, rx: fw * 0.42, ry: hc * 0.36, .stretch(g.ex, 1 - 0.22 * v(.pointedChin)))
            local(g.chin, rx: fw * 0.24, ry: fw * 0.24, plateau: 0.15, .translate(g.ey * (0.015 * hc * v(.pointedChin))))
        }
        if v(.hairline) != 0 {
            local(g.world(0, -hc * 0.72), rx: fw * 0.75, ry: hc * 0.42, plateau: 0.2,
                  .translate(g.ey * (0.05 * hc * v(.hairline))))
        }

        // ---- 眼睛（画面左、右各一只）
        for (i, e) in g.eyes.enumerated() {
            let w = e.w
            let outward: V2 = g.local(e.c).x < 0 ? -g.ex : g.ex
            var s = side(.eyeSize, i)
            if s != 0 { local(e.c, axis: e.a, rx: w * 0.95, ry: w * 0.72, .scale(1 + 0.16 * s)) }
            s = side(.eyeVertical, i)
            if s != 0 { local(e.c, axis: e.a, rx: w, ry: w * 0.62, plateau: 0.45, .translate(g.ey * (-0.10 * w * s))) }
            s = side(.eyeHeight, i)
            if s != 0 { local(e.c, axis: e.a, rx: w * 0.8, ry: w * 0.5, .stretch(e.n, 1 + 0.25 * s)) }
            s = side(.eyeLength, i)
            if s != 0 { local(e.c, axis: e.a, rx: w * 1.05, ry: w * 0.5, .stretch(e.a, 1 + 0.14 * s)) }
            s = side(.eyeDistance, i)
            if s != 0 { local(e.c, axis: e.a, rx: w, ry: w * 0.62, plateau: 0.45, .translate(outward * (0.08 * w * s))) }
            s = side(.eyeLift, i)
            if s != 0 {
                local(e.c - e.n * (w * 0.17), axis: e.a, rx: w * 0.58, ry: w * 0.3, plateau: 0.1,
                      .translate(e.n * (-0.08 * w * s)))
            }
            s = side(.irisSize, i)
            if s != 0 { local(e.pupil, rx: w * 0.3, ry: w * 0.3, .scale(1 + 0.22 * s)) }
            s = side(.lowerLid, i)
            if s != 0 {
                local(e.c + e.a * (w * 0.2) + e.n * (w * 0.14), axis: e.a, rx: w * 0.45, ry: w * 0.3, plateau: 0.1,
                      .translate(e.n * (0.08 * w * s)))
            }
            s = side(.eyeTail, i)
            if s != 0 { local(e.outer, rx: w * 0.36, ry: w * 0.36, plateau: 0.15, .translate(e.n * (-0.10 * w * s))) }
            s = side(.eyeTilt, i)
            if s != 0 {
                // 让外眼角往上走：按内→外的轴和"朝下"的关系决定转的方向。
                let perp = V2(-e.a.y, e.a.x)
                let direction: Float = simd_dot(perp, e.n) > 0 ? -1 : 1
                local(e.c, axis: e.a, rx: w * 0.85, ry: w * 0.6, .rotate(direction * 0.12 * s))
            }
            s = side(.innerCorner, i)
            if s != 0 { local(e.inner, rx: w * 0.3, ry: w * 0.3, plateau: 0.1, .translate(e.a * (-0.09 * w * s))) }
            s = side(.outerCorner, i)
            if s != 0 { local(e.outer, rx: w * 0.32, ry: w * 0.32, plateau: 0.1, .translate(e.a * (0.10 * w * s))) }
        }

        // ---- 嘴唇
        if let m = g.mouth {
            let w = m.w
            if v(.lipSize) != 0 { local(m.c, axis: m.a, rx: w * 0.78, ry: w * 0.55, .scale(1 + 0.16 * v(.lipSize))) }
            if v(.lipWidth) != 0 { local(m.c, axis: m.a, rx: w * 0.85, ry: w * 0.45, .stretch(m.a, 1 + 0.15 * v(.lipWidth))) }
            if v(.lipVertical) != 0 {
                local(m.c, axis: m.a, rx: w * 0.8, ry: w * 0.5, plateau: 0.4, .translate(m.n * (-0.07 * w * v(.lipVertical))))
            }
            if v(.lipTilt) != 0 { local(m.c, axis: m.a, rx: w * 0.8, ry: w * 0.55, .rotate(-0.09 * v(.lipTilt))) }
            if v(.lipHorizontal) != 0 {
                local(m.c, axis: m.a, rx: w * 0.8, ry: w * 0.5, plateau: 0.4, .translate(m.a * (0.06 * w * v(.lipHorizontal))))
            }
            if v(.mLip) != 0 {
                let top = m.c - m.n * ((m.bottom - m.top) / 2)
                for sign in [Float(-1), 1] {
                    local(top + m.a * (sign * w * 0.17), rx: w * 0.15, ry: w * 0.15, plateau: 0.1,
                          .translate(m.n * (-0.05 * w * v(.mLip))))
                }
                local(top, rx: w * 0.09, ry: w * 0.09, .translate(m.n * (0.02 * w * v(.mLip))))
            }
            if v(.fullLip) != 0 { local(m.c, axis: m.a, rx: w * 0.62, ry: w * 0.36, .stretch(m.n, 1 + 0.28 * v(.fullLip))) }
            if v(.smile) != 0 {
                for (corner, sign) in [(m.left, Float(-1)), (m.right, 1)] {
                    local(corner, rx: w * 0.3, ry: w * 0.3, plateau: 0.15,
                          .translate((m.n * (-0.085 * w) + m.a * (sign * 0.025 * w)) * v(.smile)))
                }
            }
        }

        // ---- 鼻子
        let nw = g.noseWidth
        if g.noseValid, v(.noseSize) != 0 {
            local(g.noseBase - g.ey * (hc * 0.1), rx: nw * 0.8, ry: hc * 0.28, .scale(1 + 0.13 * v(.noseSize)))
        }
        if g.noseValid, v(.noseLift) != 0 {
            local(g.noseBase - g.ey * (hc * 0.03), rx: nw * 0.72, ry: hc * 0.16, plateau: 0.3,
                  .translate(g.ey * (-0.04 * hc * v(.noseLift))))
        }
        if g.noseValid, v(.noseWing) != 0 {
            local(g.noseBase - g.ey * (hc * 0.03), rx: nw * 0.85, ry: hc * 0.15, c: g.noseBase,
                  .stretch(g.ex, 1 - 0.17 * v(.noseWing)))
        }
        if g.noseValid, v(.noseRoot) != 0 {
            local(g.noseRoot, rx: ed * 0.3, ry: hc * 0.17, .stretch(g.ex, 1 - 0.25 * v(.noseRoot)))
        }
        if g.noseValid, v(.noseBridge) != 0 {
            local((g.noseRoot + g.noseTip) / 2, rx: ed * 0.32, ry: hc * 0.22, .stretch(g.ex, 1 - 0.25 * v(.noseBridge)))
        }
        if g.noseValid, v(.noseTip) != 0 {
            local(g.noseTip, rx: nw * 0.36, ry: nw * 0.36, plateau: 0.15, .translate(g.ey * (-0.03 * hc * v(.noseTip))))
        }

        // ---- 眉毛
        for (i, item) in g.brows.enumerated() {
            guard let b = item else { continue }
            let w = b.w
            let outward: V2 = g.local(b.c).x < 0 ? -g.ex : g.ex
            var s = side(.browVertical, i)
            if s != 0 {
                // 范围往上偏一点、收窄一点，免得把上眼皮也带着动。
                local(b.c - b.n * (w * 0.06), axis: b.a, rx: w * 0.75, ry: w * 0.34, plateau: 0.35,
                      .translate(g.ey * (-0.06 * w * s)))
            }
            s = side(.browThickness, i)
            if s != 0 { local(b.c, axis: b.a, rx: w * 0.62, ry: w * 0.2, .stretch(b.n, 1 + 0.3 * s)) }
            s = side(.browLength, i)
            if s != 0 {
                local(b.c + b.a * (w * 0.1), axis: b.a, rx: w * 0.8, ry: w * 0.24, c: b.inner, .stretch(b.a, 1 + 0.12 * s))
            }
            s = side(.browDistance, i)
            if s != 0 { local(b.c, axis: b.a, rx: w * 0.72, ry: w * 0.3, plateau: 0.4, .translate(outward * (0.08 * w * s))) }
            s = side(.browHeadDistance, i)
            if s != 0 { local(b.inner, rx: w * 0.3, ry: w * 0.3, plateau: 0.1, .translate(b.a * (0.09 * w * s))) }
            s = side(.browTilt, i)
            if s != 0 {
                let perp = V2(-b.a.y, b.a.x)
                let direction: Float = simd_dot(perp, b.n) > 0 ? -1 : 1
                local(b.c, axis: b.a, rx: w * 0.75, ry: w * 0.32, .rotate(direction * 0.11 * s))
            }
            s = side(.browPeak, i)
            if s != 0 {
                local(b.c + b.a * (w * 0.2) - b.n * (w * 0.06), axis: b.a, rx: w * 0.42, ry: w * 0.3,
                      .translate(b.n * (-0.06 * w * s)))
            }
        }
        return out
    }
}

// MARK: - 位移场

/// 一张脸附近的位移场：一小块网格，每格记录"这里的画面要从哪边取"。
/// 存的是"位移 ÷ 这张网格里最大的位移"，0.5 是不动：Core Image 中间结果是半精度浮点，
/// 这样存能把精度都用在实际用到的范围里，原图分辨率下位移也能精确到零点几个像素。
private struct ReshapeField {
    /// 网格盖住的范围（长边为 1 的坐标，y 朝下）。
    let minX: Float
    let minY: Float
    let width: Float
    let height: Float
    let gw: Int
    let gh: Int
    let data: Data
    /// 最大位移（长边为 1）。
    let maxOffset: Float

    static func make(_ warps: [Warp], limit: V2, restore: [ReshapeRestoreStroke] = []) -> ReshapeField? {
        guard !warps.isEmpty else { return nil }
        var lo = V2(.greatestFiniteMagnitude, .greatestFiniteMagnitude)
        var hi = V2(-.greatestFiniteMagnitude, -.greatestFiniteMagnitude)
        for w in warps {
            let r = V2(w.radius, w.radius) * 1.06
            lo = simd_min(lo, w.center - r)
            hi = simd_max(hi, w.center + r)
        }
        lo = simd_max(lo, V2(0, 0))
        hi = simd_min(hi, limit)
        let size = hi - lo
        guard size.x > 1e-4, size.y > 1e-4 else { return nil }
        let long = max(size.x, size.y)
        let cells: Float = 128
        let gw = max(4, Int((size.x / long * cells).rounded(.up)))
        let gh = max(4, Int((size.y / long * cells).rounded(.up)))
        var offsets = [V2](repeating: V2(0, 0), count: gw * gh)
        var maxOffset: Float = 0
        offsets.withUnsafeMutableBufferPointer { buffer in
            // 每个动作只算它的范围盖到的那些格子。最外面一圈保持不动，这样网格之外和原图自然接上。
            for warp in warps {
                let c = warp.center, r = warp.radius
                let i0 = max(1, Int(((c.x - r - lo.x) / size.x * Float(gw)).rounded(.down)))
                let i1 = min(gw - 2, Int(((c.x + r - lo.x) / size.x * Float(gw)).rounded(.up)))
                let j0 = max(1, Int(((c.y - r - lo.y) / size.y * Float(gh)).rounded(.down)))
                let j1 = min(gh - 2, Int(((c.y + r - lo.y) / size.y * Float(gh)).rounded(.up)))
                guard i0 <= i1, j0 <= j1 else { continue }
                switch warp {
                case .local(let w):
                    for j in j0...j1 {
                        let y = lo.y + (Float(j) + 0.5) / Float(gh) * size.y
                        for i in i0...i1 {
                            buffer[j * gw + i] += w.offset(V2(lo.x + (Float(i) + 0.5) / Float(gw) * size.x, y))
                        }
                    }
                case .band(let b):
                    for j in j0...j1 {
                        let y = lo.y + (Float(j) + 0.5) / Float(gh) * size.y
                        for i in i0...i1 {
                            buffer[j * gw + i] += b.offset(V2(lo.x + (Float(i) + 0.5) / Float(gw) * size.x, y))
                        }
                    }
                }
            }
            // 变形还原：笔刷涂过的格子把位移按比例抹掉。
            for stroke in restore {
                let r = Float(stroke.radius)
                guard r > 1e-5, let first = stroke.points.first else { continue }
                func P(_ p: CGPoint) -> V2 { V2(Float(p.x) * limit.x, Float(p.y) * limit.y) }
                var stamps = [P(first)]
                for point in stroke.points.dropFirst() {
                    let a = stamps[stamps.count - 1], b = P(point)
                    let steps = max(1, Int((simd_length(b - a) / (r * 0.35)).rounded(.up)))
                    for n in 1...steps { stamps.append(a + (b - a) * (Float(n) / Float(steps))) }
                }
                for c in stamps {
                    let i0 = max(0, Int(((c.x - r - lo.x) / size.x * Float(gw)).rounded(.down)))
                    let i1 = min(gw - 1, Int(((c.x + r - lo.x) / size.x * Float(gw)).rounded(.up)))
                    let j0 = max(0, Int(((c.y - r - lo.y) / size.y * Float(gh)).rounded(.down)))
                    let j1 = min(gh - 1, Int(((c.y + r - lo.y) / size.y * Float(gh)).rounded(.up)))
                    guard i0 <= i1, j0 <= j1 else { continue }
                    for j in j0...j1 {
                        let y = lo.y + (Float(j) + 0.5) / Float(gh) * size.y - c.y
                        for i in i0...i1 {
                            let x = lo.x + (Float(i) + 0.5) / Float(gw) * size.x - c.x
                            let t2 = (x * x + y * y) / (r * r)
                            if t2 >= 1 { continue }
                            // 中间完全还原，往边缘平滑过渡；多次盖印只会越抹越干净。
                            let w = min(1, (1 - t2) * (1 - t2) * 1.6)
                            buffer[j * gw + i] *= 1 - w
                        }
                    }
                }
            }
            // 很多项一起拉满时，叠加出来的位移可能把画面挤得太狠甚至折过去。
            // 找出挤得过头的格子，把那一小片位移抹平一点，直到不再折叠。
            let hx = size.x / Float(gw), hy = size.y / Float(gh)
            var bad = [Bool](repeating: false, count: gw * gh)
            for _ in 0..<12 {
                var found = false
                for n in 0..<(gw * gh) { bad[n] = false }
                for j in 0..<(gh - 1) {
                    for i in 0..<(gw - 1) {
                        let d0 = buffer[j * gw + i], dx = buffer[j * gw + i + 1], dy = buffer[(j + 1) * gw + i]
                        let a = 1 + (dx.x - d0.x) / hx, b = (dy.x - d0.x) / hy
                        let c = (dx.y - d0.y) / hx, d = 1 + (dy.y - d0.y) / hy
                        if a * d - b * c < 0.3 {
                            found = true
                            for jj in max(0, j - 1)...min(gh - 1, j + 2) {
                                for ii in max(0, i - 1)...min(gw - 1, i + 2) { bad[jj * gw + ii] = true }
                            }
                        }
                    }
                }
                guard found else { break }
                let before = Array(buffer)
                for j in 1..<(gh - 1) {
                    for i in 1..<(gw - 1) where bad[j * gw + i] {
                        var sum = V2(0, 0)
                        for jj in (j - 1)...(j + 1) {
                            for ii in (i - 1)...(i + 1) { sum += before[jj * gw + ii] }
                        }
                        buffer[j * gw + i] = sum / 9
                    }
                }
            }
            for n in 0..<(gw * gh) {
                maxOffset = max(maxOffset, abs(buffer[n].x), abs(buffer[n].y))
            }
        }
        guard maxOffset > 1e-6 else { return nil }
        var values = [Float](repeating: 0, count: gw * gh * 4)
        let k = 0.5 / maxOffset
        values.withUnsafeMutableBufferPointer { buffer in
            for n in 0..<(gw * gh) {
                let d = offsets[n]
                // Core Image 的 y 轴朝上，所以 G 通道存的是取反后的 y。
                buffer[n * 4] = 0.5 + d.x * k
                buffer[n * 4 + 1] = 0.5 - d.y * k
                buffer[n * 4 + 3] = 1
            }
        }
        return ReshapeField(minX: lo.x, minY: lo.y, width: size.x, height: size.y, gw: gw, gh: gh,
                            data: values.withUnsafeBufferPointer { Data(buffer: $0) }, maxOffset: maxOffset)
    }
}

// MARK: - 编辑步骤

/// 面部重塑：按人脸关键点对五官和脸型做局部变形，作为编辑历史里的一步。
struct ReshapeParams: Equatable {
    var faces: [ReshapeFace] = []
    /// 背景保护用的人像蒙版；nil = 不保护。
    var lockMask: CGImage? = nil
    /// 变形还原涂过的地方。
    var restore: [ReshapeRestoreStroke] = []

    static func == (a: ReshapeParams, b: ReshapeParams) -> Bool {
        a.faces == b.faces && a.lockMask === b.lockMask && a.restore == b.restore
    }

    var isIdentity: Bool { !faces.contains { !$0.isIdentity } }

    func apply(to image: CIImage) -> CIImage {
        apply(to: image, plate: nil)
    }

    /// plate 是提前补好的"没有人的背景"（实时预览用）；不传的话开了背景保护就现算。
    func apply(to image: CIImage, plate: CGImage?) -> CIImage {
        guard !isIdentity else { return image }
        let extent = image.extent
        let long = Float(max(extent.width, extent.height))
        guard long > 1 else { return image }
        let aspect = V2(Float(extent.width) / long, Float(extent.height) / long)
        var out = image
        for face in faces where !face.isIdentity {
            guard let geo = FaceGeometry(face.info, aspect: aspect) else { continue }
            // 先调颜色（蒙版和五官位置还对得上），再变形。
            out = Self.color(out, face: face, geo: geo, extent: extent)
            let warps = ReshapePlan.warps(for: face, geo: geo)
            if let field = ReshapeField.make(warps, limit: aspect, restore: restore) {
                out = Self.warp(out, field: field, extent: extent, lock: lockMask, plate: plate)
            }
        }
        return out
    }

    /// 这张脸的关键点能不能用（整体比例对得上）。size 是图片的像素尺寸。
    static func usable(_ face: FaceInfo, size: CGSize) -> Bool {
        geometry(face, size: size) != nil
    }

    /// 这张脸上这一类项目调不调得了：嘴唇、鼻子、眉毛要各自的关键点落在合理的位置才行。
    static func supports(_ category: ReshapeCategory, face: FaceInfo, size: CGSize) -> Bool {
        guard let geo = geometry(face, size: size) else { return false }
        switch category {
        case .lips: return geo.mouth != nil
        case .nose: return geo.noseValid
        case .brows: return geo.brows.contains { $0 != nil }
        default: return true
        }
    }

    private static func geometry(_ face: FaceInfo, size: CGSize) -> FaceGeometry? {
        let long = Float(max(size.width, size.height))
        guard long > 0 else { return nil }
        return FaceGeometry(face, aspect: V2(Float(size.width) / long, Float(size.height) / long))
    }

    // MARK: 变形

    /// 按位移图取样的内核。位移图 R、G 通道以 0.5 为零点，乘上 scale 得到像素位移。
    private static let kernel = CIKernel(source: """
    kernel vec4 reshapeWarp(sampler image, sampler map, float scale) {
        vec2 d = destCoord();
        vec4 m = sample(map, samplerTransform(map, d));
        vec2 p = d + (m.rg - vec2(0.5)) * scale;
        return sample(image, samplerTransform(image, p));
    }
    """)

    private static func warp(_ image: CIImage, field: ReshapeField, extent: CGRect, lock: CGImage?, plate: CGImage?) -> CIImage {
        guard let provider = CGDataProvider(data: field.data as CFData),
              let space = CGColorSpace(name: CGColorSpace.extendedLinearSRGB),
              let cg = CGImage(width: field.gw, height: field.gh, bitsPerComponent: 32, bitsPerPixel: 128,
                               bytesPerRow: field.gw * 16, space: space,
                               bitmapInfo: CGBitmapInfo(rawValue: CGBitmapInfo.floatComponents.rawValue
                                                        | CGBitmapInfo.byteOrder32Little.rawValue
                                                        | CGImageAlphaInfo.noneSkipLast.rawValue),
                               provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent),
              let kernel else { return image }
        let scale = max(extent.width, extent.height)
        // 网格在图片上的位置（Core Image 坐标，y 朝上）。
        let region = CGRect(x: extent.minX + CGFloat(field.minX) * scale,
                            y: extent.maxY - CGFloat(field.minY + field.height) * scale,
                            width: CGFloat(field.width) * scale, height: CGFloat(field.height) * scale)
        let cell = max(region.width / CGFloat(field.gw), region.height / CGFloat(field.gh))
        // 先把网格往四周无限延伸（最外一圈是"不动"），再放大到图片上：
        // 反过来做的话，放大时网格边缘半格会和透明混在一起，位移值被拉偏，画面上出现一圈细框。
        let map = CIImage(cgImage: cg, options: [.colorSpace: NSNull()])
            .clampedToExtent()
            .transformed(by: CGAffineTransform(scaleX: region.width / CGFloat(field.gw),
                                               y: region.height / CGFloat(field.gh)))
            .transformed(by: CGAffineTransform(translationX: region.minX, y: region.minY))
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: cell * 0.8])
        // 网格盖住的那一块（四周多留几格，那里位移已经是 0）。
        let area = region.insetBy(dx: -cell * 3, dy: -cell * 3).integral.intersection(extent)
        guard area.width >= 1, area.height >= 1 else { return image }
        let reach = CGFloat(field.maxOffset) * scale + 2
        // 网格里的 ±0.5 对应 ±最大位移。
        let amplitude = CGFloat(field.maxOffset) * 2 * scale
        // 内核一律铺满整张图来算：只给它一小块范围再贴回原图的话，整张渲染时范围外面会被拉出条纹。
        // 网格之外位移是 0，取到的就是原来的像素；最后再用一张明确的蒙版只保留网格这一块，其余用原图。
        func displaced(_ source: CIImage) -> CIImage {
            guard let moved = kernel.apply(extent: extent, roiCallback: { index, rect in
                index == 0 ? rect.insetBy(dx: -reach, dy: -reach) : rect
            }, arguments: [source.clampedToExtent(), map, amplitude]) else { return source }
            return moved.cropped(to: extent)
        }
        let inside = CIImage(color: CIColor(red: 1, green: 1, blue: 1)).cropped(to: area)
        func limited(_ result: CIImage) -> CIImage {
            result.applyingFilter("CIBlendWithMask", parameters: [
                kCIInputBackgroundImageKey: image,
                kCIInputMaskImageKey: inside,
            ]).cropped(to: extent)
        }
        let warped = displaced(image)
        guard let lock else { return limited(warped) }

        // 背景保护：人单独变形，背景保持原图；人让出来的地方用补好的背景填。
        // 人像蒙版边缘很淡的那一圈不算人，否则离人很远的背景也会被带着动一点点。
        let person = CIImage(cgImage: lock, options: [.colorSpace: NSNull()])
            .transformed(by: CGAffineTransform(scaleX: extent.width / CGFloat(lock.width),
                                               y: extent.height / CGFloat(lock.height)))
            .transformed(by: CGAffineTransform(translationX: extent.minX, y: extent.minY))
            .applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": CIVector(x: 1.5, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: 0, y: 1.5, z: 0, w: 0),
                "inputBVector": CIVector(x: 0, y: 0, z: 1.5, w: 0),
                "inputBiasVector": CIVector(x: -0.2, y: -0.2, z: -0.2, w: 0),
            ])
            .applyingFilter("CIColorClamp")
            .cropped(to: extent)
        let moved = displaced(person)
        let background: CIImage
        if let plate {
            background = CIImage(cgImage: plate)
                .transformed(by: CGAffineTransform(scaleX: extent.width / CGFloat(plate.width),
                                                   y: extent.height / CGFloat(plate.height)))
                .transformed(by: CGAffineTransform(translationX: extent.minX, y: extent.minY))
                .cropped(to: extent)
        } else {
            background = Self.backgroundPlate(image, lock: lock)
        }
        // 只有人的轮廓真的动过的地方才换成补好的背景，其余地方一个像素都不动。
        let changed = person
            .applyingFilter("CIDifferenceBlendMode", parameters: [kCIInputBackgroundImageKey: moved])
            .clampedToExtent()
            .applyingFilter("CIMorphologyMaximum", parameters: [kCIInputRadiusKey: scale * 0.01])
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: scale * 0.005])
            .applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": CIVector(x: 2.5, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: 0, y: 2.5, z: 0, w: 0),
                "inputBVector": CIVector(x: 0, y: 0, z: 2.5, w: 0),
            ])
            .applyingFilter("CIColorClamp")
            .cropped(to: extent)
        let behind = background.applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: image,
            kCIInputMaskImageKey: changed,
        ]).cropped(to: extent)
        return limited(warped.applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: behind,
            kCIInputMaskImageKey: moved,
        ]).cropped(to: extent))
    }

    /// "没有人的背景"。App 里直接用手动美体那一套；在 Mac 上跑测试时没有那个文件，就用原图顶替。
    static func backgroundPlate(_ image: CIImage, lock: CGImage) -> CIImage {
        #if canImport(UIKit)
        return LiquifyGrid.backgroundPlate(image, lock: lock)
        #else
        return image
        #endif
    }

    // MARK: 调颜色

    /// 牙齿：只动偏黄、够亮、不发红的像素（嘴唇和舌头是红的，不碰）。
    private static let teethKernel = CIColorKernel(source: """
    kernel vec4 reshapeTeeth(__sample s, __sample m, float amount) {
        float l = dot(s.rgb, vec3(0.2126, 0.7152, 0.0722));
        float redness = s.r - max(s.g, s.b);
        float w = m.r * amount * smoothstep(0.08, 0.25, l) * (1.0 - smoothstep(0.03, 0.12, redness));
        vec3 c = mix(s.rgb, vec3(l), 0.65 * w);
        c = c + (vec3(1.0) - c) * 0.22 * w;
        return vec4(c, s.a);
    }
    """)

    /// 眼白：把红色压回去，再稍微提亮一点。
    private static let redEyeKernel = CIColorKernel(source: """
    kernel vec4 reshapeRedEye(__sample s, __sample m, float amount) {
        float l = dot(s.rgb, vec3(0.2126, 0.7152, 0.0722));
        float redness = max(s.r - max(s.g, s.b), 0.0);
        float w = m.r * amount * smoothstep(0.1, 0.3, l);
        vec3 c = s.rgb;
        c.r = c.r - redness * 0.9 * w;
        c = c + (vec3(1.0) - c) * 0.06 * w;
        return vec4(c, s.a);
    }
    """)

    /// 在脸附近的一小块灰度位图上画蒙版，再放到图片上对应的位置。points 用长边为 1 的坐标（y 朝下）。
    private static func mask(extent: CGRect, center: V2, radius: Float, feather: Float,
                             draw: (CGContext, (V2) -> CGPoint, CGFloat) -> Void) -> CIImage? {
        let scale = max(extent.width, extent.height)
        let side = CGFloat(radius * 2) * scale
        guard side >= 2 else { return nil }
        let pixels = max(8, min(512, Int(side.rounded(.up))))
        guard let ctx = CGContext(data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
        ctx.setFillColor(gray: 0, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: pixels, height: pixels))
        let k = CGFloat(pixels) / side
        // 位图坐标 y 朝上，和 Core Image 一致。
        func point(_ p: V2) -> CGPoint {
            CGPoint(x: (CGFloat(p.x - center.x + radius) * scale) * k,
                    y: CGFloat(pixels) - (CGFloat(p.y - center.y + radius) * scale) * k)
        }
        draw(ctx, point, scale * k)
        guard let cg = ctx.makeImage() else { return nil }
        let origin = CGPoint(x: extent.minX + CGFloat(center.x - radius) * scale,
                             y: extent.maxY - CGFloat(center.y + radius) * scale)
        var m = CIImage(cgImage: cg, options: [.colorSpace: NSNull()])
            .transformed(by: CGAffineTransform(scaleX: side / CGFloat(pixels), y: side / CGFloat(pixels)))
            .transformed(by: CGAffineTransform(translationX: origin.x, y: origin.y))
        if feather > 0 {
            m = m.clampedToExtent()
                .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: CGFloat(feather) * scale])
        }
        // 位图之外都是黑的。
        let black = CIImage(color: CIColor(red: 0, green: 0, blue: 0)).cropped(to: extent)
        return m.cropped(to: CGRect(x: origin.x, y: origin.y, width: side, height: side).insetBy(dx: -CGFloat(feather) * scale * 3,
                                                                                               dy: -CGFloat(feather) * scale * 3))
            .composited(over: black).cropped(to: extent)
    }

    private static func fill(_ ctx: CGContext, _ points: [CGPoint]) {
        guard points.count >= 3 else { return }
        ctx.beginPath()
        ctx.addLines(between: points)
        ctx.closePath()
        ctx.fillPath()
    }

    private static func color(_ image: CIImage, face: ReshapeFace, geo g: FaceGeometry, extent: CGRect) -> CIImage {
        var out = image
        // 祛红血丝：眼白范围，避开瞳孔。
        if let value = face.values[.redEye], let kernel = redEyeKernel {
            for (i, e) in g.eyes.enumerated() {
                let amount = i == 0 ? value.left : value.right
                guard amount > 0, e.polygon.count >= 3 else { continue }
                guard let m = mask(extent: extent, center: e.c, radius: e.w, feather: e.w * 0.04, draw: { ctx, point, k in
                    ctx.setFillColor(gray: 1, alpha: 1)
                    fill(ctx, e.polygon.map(point))
                    ctx.setFillColor(gray: 0, alpha: 1)
                    let r = CGFloat(e.w * 0.24) * k
                    let p = point(e.pupil)
                    ctx.fillEllipse(in: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2))
                }) else { continue }
                if let done = kernel.apply(extent: extent, arguments: [out, m, amount]) { out = done.cropped(to: extent) }
            }
        }
        // 去眼袋：眼睛下面那一条抹匀、提亮一点。
        if let value = face.values[.eyeBags] {
            for (i, e) in g.eyes.enumerated() {
                let amount = i == 0 ? value.left : value.right
                guard amount > 0 else { continue }
                let c = e.c + e.n * (e.w * 0.5)
                guard let m = mask(extent: extent, center: c, radius: e.w * 1.2, feather: e.w * 0.09, draw: { ctx, point, k in
                    ctx.setFillColor(gray: 1, alpha: 1)
                    ctx.saveGState()
                    let p = point(c)
                    ctx.translateBy(x: p.x, y: p.y)
                    // 位图的 y 朝上，所以角度取反。
                    ctx.rotate(by: CGFloat(-atan2f(e.a.y, e.a.x)))
                    let rx = CGFloat(e.w * 0.62) * k, ry = CGFloat(e.w * 0.2) * k
                    ctx.fillEllipse(in: CGRect(x: -rx, y: -ry, width: rx * 2, height: ry * 2))
                    ctx.restoreGState()
                    // 眼睛本身不动。
                    ctx.setFillColor(gray: 0, alpha: 1)
                    ctx.setStrokeColor(gray: 0, alpha: 1)
                    ctx.setLineWidth(CGFloat(e.w * 0.1) * k)
                    ctx.setLineJoin(.round)
                    let polygon = e.polygon.map(point)
                    if polygon.count >= 3 {
                        ctx.beginPath()
                        ctx.addLines(between: polygon)
                        ctx.closePath()
                        ctx.drawPath(using: .fillStroke)
                    }
                }) else { continue }
                let scale = max(extent.width, extent.height)
                let soft = out.clampedToExtent()
                    .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: CGFloat(e.w * 0.06) * scale])
                    .applyingFilter("CIExposureAdjust", parameters: [kCIInputEVKey: 0.22 * amount])
                    .cropped(to: extent)
                let weighted = m.applyingFilter("CIColorMatrix", parameters: [
                    "inputRVector": CIVector(x: CGFloat(amount) * 0.85, y: 0, z: 0, w: 0),
                    "inputGVector": CIVector(x: 0, y: CGFloat(amount) * 0.85, z: 0, w: 0),
                    "inputBVector": CIVector(x: 0, y: 0, z: CGFloat(amount) * 0.85, w: 0),
                ])
                out = soft.applyingFilter("CIBlendWithMask", parameters: [
                    kCIInputBackgroundImageKey: out,
                    kCIInputMaskImageKey: weighted,
                ]).cropped(to: extent)
            }
        }
        // 白牙：嘴唇内圈（张着嘴才有）。
        if let amount = face.values[.teeth]?.left, amount > 0, let m = g.mouth, m.inner.count >= 3, let kernel = teethKernel {
            if let teeth = mask(extent: extent, center: m.c, radius: m.w * 0.8, feather: m.w * 0.02, draw: { ctx, point, k in
                ctx.setFillColor(gray: 1, alpha: 1)
                ctx.setStrokeColor(gray: 1, alpha: 1)
                ctx.setLineWidth(CGFloat(m.w * 0.02) * k)
                ctx.beginPath()
                ctx.addLines(between: m.inner.map(point))
                ctx.closePath()
                ctx.drawPath(using: .fillStroke)
            }), let done = kernel.apply(extent: extent, arguments: [out, teeth, amount]) {
                out = done.cropped(to: extent)
            }
        }
        return out
    }

    #if DEBUG
    /// 测试用：实际用来变形的那张网格（已经做过防折叠处理）里雅可比行列式的最小值。
    static func minimumGridJacobian(for face: ReshapeFace, size: CGSize) -> Float? {
        let long = Float(max(size.width, size.height))
        let aspect = V2(Float(size.width) / long, Float(size.height) / long)
        guard let geo = FaceGeometry(face.info, aspect: aspect),
              let field = ReshapeField.make(ReshapePlan.warps(for: face, geo: geo), limit: aspect) else { return nil }
        let hx = field.width / Float(field.gw), hy = field.height / Float(field.gh)
        let amp = field.maxOffset * 2
        return field.data.withUnsafeBytes { raw -> Float in
            let v = raw.bindMemory(to: Float.self)
            func at(_ i: Int, _ j: Int) -> V2 {
                let o = (j * field.gw + i) * 4
                return V2((v[o] - 0.5) * amp, -(v[o + 1] - 0.5) * amp)
            }
            var minimum: Float = 1
            for j in 0..<(field.gh - 1) {
                for i in 0..<(field.gw - 1) {
                    let d0 = at(i, j), dx = at(i + 1, j), dy = at(i, j + 1)
                    let a = 1 + (dx.x - d0.x) / hx, b = (dy.x - d0.x) / hy
                    let c = (dx.y - d0.y) / hx, d = 1 + (dy.y - d0.y) / hy
                    minimum = min(minimum, a * d - b * c)
                }
            }
            return minimum
        }
    }

    /// 测试用：这张脸的变形映射里雅可比行列式的最小值。小于等于 0 说明画面被折叠了。
    static func minimumJacobian(for face: ReshapeFace, size: CGSize) -> Float? {
        let long = Float(max(size.width, size.height))
        let aspect = V2(Float(size.width) / long, Float(size.height) / long)
        guard let geo = FaceGeometry(face.info, aspect: aspect) else { return nil }
        let warps = ReshapePlan.warps(for: face, geo: geo)
        guard !warps.isEmpty else { return 1 }
        func offset(_ p: V2) -> V2 {
            var d = V2(0, 0)
            for w in warps {
                switch w {
                case .local(let l): d += l.offset(p)
                case .band(let b): d += b.offset(p)
                }
            }
            return d
        }
        var lo = V2(.greatestFiniteMagnitude, .greatestFiniteMagnitude), hi = -lo
        for w in warps {
            lo = simd_min(lo, w.center - V2(w.radius, w.radius))
            hi = simd_max(hi, w.center + V2(w.radius, w.radius))
        }
        let n = 220
        let h = max(hi.x - lo.x, hi.y - lo.y) / Float(n)
        var minimum: Float = 1
        for j in 0..<n {
            for i in 0..<n {
                let p = lo + V2(Float(i) + 0.5, Float(j) + 0.5) * h
                let d0 = offset(p), dx = offset(p + V2(h, 0)), dy = offset(p + V2(0, h))
                let a = 1 + (dx.x - d0.x) / h, b = (dy.x - d0.x) / h
                let c = (dx.y - d0.y) / h, d = 1 + (dy.y - d0.y) / h
                minimum = min(minimum, a * d - b * c)
            }
        }
        return minimum
    }
    #endif

    /// 内核有没有编译成功（写错了内核不会报错，只会悄悄不起作用，测试时用它检查）。
    static var kernelsReady: Bool { kernel != nil && teethKernel != nil && redEyeKernel != nil }
}
