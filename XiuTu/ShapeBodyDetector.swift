import CoreGraphics
import Foundation
import Vision

/// 用系统自带的人体姿态识别找出肩、肘、腕、胯、膝、踝和头的位置，全部在本机完成。
enum ShapeBodyDetector {
    /// 返回画面里的人，最大的排最前。坐标是单位坐标（原图长边为 1，原点左上角）。
    static func detect(in image: CGImage) -> [ShapeBody] {
        let request = VNDetectHumanBodyPoseRequest()
        #if targetEnvironment(simulator)
        request.usesCPUOnly = true
        #endif
        do {
            try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        } catch {
            return []
        }
        let width = CGFloat(image.width), height = CGFloat(image.height)
        let long = max(width, height, 1)
        let bodies: [ShapeBody] = (request.results ?? []).compactMap { observation in
            guard let points = try? observation.recognizedPoints(.all) else { return nil }
            func point(_ name: VNHumanBodyPoseObservation.JointName) -> CGPoint? {
                guard let p = points[name], p.confidence > 0.25 else { return nil }
                return CGPoint(x: p.location.x * width / long, y: (1 - p.location.y) * height / long)
            }
            guard let personLeft = point(.leftShoulder), let personRight = point(.rightShoulder) else { return nil }
            // 面对镜头时，这个人自己的右边在画面左边；背对镜头时反过来。
            let facing = personRight.x <= personLeft.x
            func limb(_ useRight: Bool) -> ShapeBody.Limb {
                ShapeBody.Limb(shoulder: point(useRight ? .rightShoulder : .leftShoulder),
                               elbow: point(useRight ? .rightElbow : .leftElbow),
                               wrist: point(useRight ? .rightWrist : .leftWrist),
                               hip: point(useRight ? .rightHip : .leftHip),
                               knee: point(useRight ? .rightKnee : .leftKnee),
                               ankle: point(useRight ? .rightAnkle : .leftAnkle))
            }
            var body = ShapeBody(left: limb(facing), right: limb(!facing))
            guard let ls = body.left.shoulder, let rs = body.right.shoulder else { return nil }
            let shoulderWidth = hypot(rs.x - ls.x, rs.y - ls.y)
            guard shoulderWidth > 0.01 else { return nil }
            let mid = CGPoint(x: (ls.x + rs.x) / 2, y: (ls.y + rs.y) / 2)

            // 头：用鼻子、眼睛、耳朵估出中心和大小。
            let nose = point(.nose), leftEar = point(.leftEar), rightEar = point(.rightEar)
            let leftEye = point(.leftEye), rightEye = point(.rightEye)
            let face = [nose, leftEar, rightEar, leftEye, rightEye].compactMap { $0 }
            if !face.isEmpty {
                var center = nose ?? CGPoint(x: face.map(\.x).reduce(0, +) / CGFloat(face.count),
                                             y: face.map(\.y).reduce(0, +) / CGFloat(face.count))
                var radius = shoulderWidth * 0.42
                if let leftEar, let rightEar {
                    radius = hypot(leftEar.x - rightEar.x, leftEar.y - rightEar.y) * 0.72
                } else if let leftEye, let rightEye {
                    radius = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y) * 1.7
                }
                radius = min(max(radius, shoulderWidth * 0.28), shoulderWidth * 0.75)
                // 鼻子在脸的中间偏下，整个头的中心还要再往上一点。
                let away = CGPoint(x: center.x - mid.x, y: center.y - mid.y)
                let length = max(hypot(away.x, away.y), 0.0001)
                center = CGPoint(x: center.x + away.x / length * radius * 0.2,
                                 y: center.y + away.y / length * radius * 0.2)
                body.head = center
                body.headRadius = radius
            }
            return body
        }
        return bodies.sorted { $0.bounds.width * $0.bounds.height > $1.bounds.width * $1.bounds.height }
    }
}
