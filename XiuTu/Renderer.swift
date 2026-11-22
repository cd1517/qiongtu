import CoreImage
import UIKit

/// Core Image 渲染的统一出口。
final class Renderer {
    static let shared = Renderer()
    static let displaySpace = CGColorSpace(name: CGColorSpace.displayP3) ?? CGColorSpaceCreateDeviceRGB()

    let context = CIContext(options: [.cacheIntermediates: false])
    /// RAW 冲洗专用：中间结果强制用浮点，超过白点的高光和很暗的暗部在各步之间不会被截断或量化。
    let deepContext = CIContext(options: [.cacheIntermediates: false, .workingFormat: CIFormat.RGBAh])

    func cgImage(_ image: CIImage, deep: Bool = false) -> CGImage? {
        let extent = image.extent
        guard !extent.isInfinite, extent.width >= 1, extent.height >= 1 else { return nil }
        return (deep ? deepContext : context).createCGImage(image, from: extent, format: .RGBA8, colorSpace: Self.displaySpace)
    }

    /// 长边超过 maxDimension 时等比缩小，结果原点在 (0,0)。
    func downscaled(_ image: CIImage, maxDimension: CGFloat) -> CIImage {
        let e = image.extent
        let m = max(e.width, e.height)
        guard m > maxDimension else { return image }
        let s = maxDimension / m
        let scaled = image.applyingFilter("CILanczosScaleTransform", parameters: [
            kCIInputScaleKey: s,
            kCIInputAspectRatioKey: 1.0,
        ])
        let target = CGRect(x: 0, y: 0,
                            width: max(1, (e.width * s).rounded(.down)),
                            height: max(1, (e.height * s).rounded(.down)))
        return scaled.cropped(to: target)
    }

    /// 缩小并渲染成位图，返回位图以及基于它的 CIImage（后续处理更快）。
    func rasterize(_ image: CIImage, maxDimension: CGFloat) -> (ci: CIImage, cg: CGImage)? {
        let small = downscaled(image, maxDimension: maxDimension)
        guard let cg = cgImage(small) else { return nil }
        return (CIImage(cgImage: cg), cg)
    }
}

/// 拖动滑块、涂抹时的实时预览任务。全部是值，方便放到后台线程算。
enum LiveJob {
    case adjust(CIImage, AdjustParams)
    case look(CIImage, String?, CGFloat)
    case crop(CIImage, CropParams)
    case mosaic(CIImage, [MosaicMask])
    case aiRegion(CIImage, CGImage?)
    case aiResult(CIImage, AIEdit)
    case beauty(CIImage, [AIEdit], [BeautyParams])
    case liquify(CIImage, LiquifyField, CGImage?, CGImage?)
    /// 已经搭好的图，直接渲染（RAW 冲洗用，中间结果走浮点）。
    case plain(CIImage)
    /// 已经搭好的图，直接渲染（普通精度）。
    case direct(CIImage)

    func image() -> CIImage {
        switch self {
        case .adjust(let base, let params):
            return params.apply(to: base)
        case .look(let base, let id, let intensity):
            guard let id, let look = FilmLook.byID[id] else { return base }
            return look.apply(to: base, intensity: intensity)
        case .crop(let base, let params):
            return params.oriented(base)
        case .mosaic(let base, let masks):
            return MosaicParams.composite(base: base, masks: masks)
        case .aiRegion(let base, let mask):
            guard let mask else { return base }
            return AIRegion.overlay(base: base, mask: mask)
        case .aiResult(let base, let edit):
            return edit.apply(to: base)
        case .beauty(let base, let edits, let params):
            var out = base
            for edit in edits { out = edit.apply(to: out) }
            for p in params { out = p.apply(to: out) }
            return out
        case .plain(let image):
            return image
        case .direct(let image):
            return image
        case .liquify(let base, let field, let lock, let plate):
            return LiquifyGrid.warp(base, field: field, lock: lock, plate: plate)
        }
    }
}

/// 只渲染最新的一帧：渲染跟不上手指时，中间帧直接丢掉。
final class LiveRenderer {
    private let queue = DispatchQueue(label: "xiutu.live", qos: .userInteractive)
    private let lock = NSLock()
    private var latest: LiveJob?
    private var busy = false

    func submit(_ job: LiveJob, completion: @escaping @MainActor (UIImage) -> Void) {
        lock.lock()
        latest = job
        let shouldStart = !busy
        if shouldStart { busy = true }
        lock.unlock()
        guard shouldStart else { return }
        queue.async { [self] in
            while true {
                lock.lock()
                guard let next = latest else {
                    busy = false
                    lock.unlock()
                    return
                }
                latest = nil
                lock.unlock()
                var deep = false
                if case .plain = next { deep = true }
                if let cg = Renderer.shared.cgImage(next.image(), deep: deep) {
                    let ui = UIImage(cgImage: cg)
                    Task { @MainActor in completion(ui) }
                }
            }
        }
    }
}
