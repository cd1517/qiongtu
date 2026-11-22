import SwiftUI
import UIKit

/// 单指涂抹的回调。坐标是归一化的图片坐标（原点左上角）。
/// displayScale = 图片长边当前在屏幕上占多少点，用来把"屏幕上的笔刷大小"换算成图片比例。
struct DrawHandlers {
    var began: (CGPoint, CGFloat) -> Void
    var moved: (CGPoint) -> Void
    var ended: () -> Void
    var cancelled: () -> Void
}

/// 可双指缩放、拖动的图片画布。传入 handlers 时单指变成画笔，双指仍然缩放/移动。
struct ZoomableCanvas: UIViewRepresentable {
    var image: UIImage?
    var handlers: DrawHandlers?
    /// 要放大显示的区域（归一化图片坐标）。nil = 显示整张图。
    var focus: CGRect? = nil
    /// 放大后的高清贴片，以及它对应图片的哪一块（归一化坐标）。
    var detail: UIImage? = nil
    var detailRect: CGRect? = nil
    /// 缩放或移动停下来时，报告当前看到的是图片的哪一块，以及这一块在屏幕上有多少像素宽。
    var onViewport: ((CGRect?, CGFloat) -> Void)? = nil
    /// 单击画面：给出点到的位置（归一化图片坐标）。
    var onTap: ((CGPoint) -> Void)? = nil

    func makeUIView(context: Context) -> CanvasScrollView {
        CanvasScrollView()
    }

    func updateUIView(_ view: CanvasScrollView, context: Context) {
        view.setImage(image)
        view.handlers = handlers
        view.setFocus(focus)
        view.onViewport = onViewport
        view.onTap = onTap
        view.setDetail(detail, rect: detailRect)
    }
}

final class CanvasImageView: UIImageView {
    var handlers: DrawHandlers?
    var displayScale: () -> CGFloat = { 1 }
    private var tracking = false

    private func normalized(_ touch: UITouch) -> CGPoint {
        let p = touch.location(in: self)
        guard bounds.width > 0, bounds.height > 0 else { return .zero }
        return CGPoint(x: p.x / bounds.width, y: p.y / bounds.height)
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let handlers, let touch = touches.first else {
            super.touchesBegan(touches, with: event)
            return
        }
        if (event?.allTouches?.count ?? 1) > 1 {
            // 第二根手指落下：这是缩放手势，放弃刚开始的这一笔。
            if tracking {
                tracking = false
                handlers.cancelled()
            }
            return
        }
        tracking = true
        handlers.began(normalized(touch), displayScale())
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let handlers, tracking, let touch = touches.first else {
            super.touchesMoved(touches, with: event)
            return
        }
        handlers.moved(normalized(touch))
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let handlers, tracking else {
            super.touchesEnded(touches, with: event)
            return
        }
        tracking = false
        handlers.ended()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let handlers, tracking else {
            super.touchesCancelled(touches, with: event)
            return
        }
        tracking = false
        handlers.cancelled()
    }
}

final class CanvasScrollView: UIScrollView, UIScrollViewDelegate {
    private let imageView = CanvasImageView()
    private var imagePixelSize: CGSize = .zero
    private var lastBoundsSize: CGSize = .zero
    private lazy var doubleTap = UITapGestureRecognizer(target: self, action: #selector(onDoubleTap(_:)))
    private lazy var singleTap = UITapGestureRecognizer(target: self, action: #selector(onSingleTap(_:)))
    var onTap: ((CGPoint) -> Void)? {
        didSet { singleTap.isEnabled = onTap != nil }
    }

    @objc private func onSingleTap(_ recognizer: UITapGestureRecognizer) {
        let size = imageView.bounds.size
        guard size.width > 0, size.height > 0 else { return }
        let p = recognizer.location(in: imageView)
        onTap?(CGPoint(x: p.x / size.width, y: p.y / size.height))
    }

    var handlers: DrawHandlers? {
        didSet { configureInput() }
    }

    private var focus: CGRect?
    private let detailView = UIImageView()
    var onViewport: ((CGRect?, CGFloat) -> Void)?

    func setDetail(_ image: UIImage?, rect: CGRect?) {
        let size = imageView.bounds.size
        guard let image, let rect, size.width > 0 else {
            if !detailView.isHidden {
                detailView.isHidden = true
                detailView.image = nil
            }
            return
        }
        detailView.frame = CGRect(x: rect.minX * size.width, y: rect.minY * size.height,
                                  width: rect.width * size.width, height: rect.height * size.height)
        detailView.image = image
        detailView.isHidden = false
    }

    private func reportViewport() {
        guard let onViewport else { return }
        let size = imageView.bounds.size
        var rect: CGRect?
        var pixels: CGFloat = 0
        if size.width > 0, size.height > 0, zoomScale > 1.05 {
            let visible = convert(bounds, to: imageView).intersection(imageView.bounds)
            if visible.width > 1, visible.height > 1 {
                rect = CGRect(x: visible.minX / size.width, y: visible.minY / size.height,
                              width: visible.width / size.width, height: visible.height / size.height)
                pixels = visible.width * zoomScale * (window?.screen.scale ?? traitCollection.displayScale)
            }
        }
        // 可能是在 SwiftUI 刷新过程中被调到的，推迟一拍再改状态。
        DispatchQueue.main.async { onViewport(rect, pixels) }
    }

    func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
        reportViewport()
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !decelerate { reportViewport() }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        reportViewport()
    }

    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
        reportViewport()
    }

    func setFocus(_ rect: CGRect?) {
        guard rect != focus else { return }
        focus = rect
        applyFocus(animated: true)
    }

    private func applyFocus(animated: Bool) {
        let size = imageView.bounds.size
        guard size.width > 0, size.height > 0 else { return }
        guard let focus else {
            if zoomScale > 1.01 { setZoomScale(1, animated: animated) }
            return
        }
        let rect = CGRect(x: focus.minX * size.width, y: focus.minY * size.height,
                          width: focus.width * size.width, height: focus.height * size.height)
        zoom(to: rect, animated: animated)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        backgroundColor = .clear
        showsVerticalScrollIndicator = false
        showsHorizontalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        minimumZoomScale = 1
        maximumZoomScale = 8
        bouncesZoom = true
        imageView.contentMode = .scaleToFill
        imageView.isUserInteractionEnabled = true
        detailView.contentMode = .scaleToFill
        detailView.isHidden = true
        imageView.addSubview(detailView)
        imageView.displayScale = { [weak self] in
            guard let self else { return 1 }
            return max(self.imageView.bounds.width, self.imageView.bounds.height) * self.zoomScale
        }
        addSubview(imageView)
        doubleTap.numberOfTapsRequired = 2
        addGestureRecognizer(doubleTap)
        singleTap.require(toFail: doubleTap)
        singleTap.isEnabled = false
        addGestureRecognizer(singleTap)
        configureInput()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func configureInput() {
        let drawing = handlers != nil
        panGestureRecognizer.minimumNumberOfTouches = drawing ? 2 : 1
        delaysContentTouches = !drawing
        doubleTap.isEnabled = !drawing
        imageView.handlers = handlers
    }

    func setImage(_ image: UIImage?) {
        imageView.image = image
        let size = image?.size ?? .zero
        if size != imagePixelSize {
            imagePixelSize = size
            resetLayout()
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != lastBoundsSize {
            lastBoundsSize = bounds.size
            resetLayout()
        }
        centerContent()
    }

    private func resetLayout() {
        guard bounds.width > 0, bounds.height > 0, imagePixelSize.width > 0, imagePixelSize.height > 0 else { return }
        zoomScale = 1
        let fitted = aspectFit(imagePixelSize, in: bounds.size)
        imageView.frame = CGRect(origin: .zero, size: fitted)
        contentSize = fitted
        contentOffset = .zero
        centerContent()
        detailView.isHidden = true
        reportViewport()
        // 面板高度变化会重新排版，排完再回到要放大的位置。
        if focus != nil {
            DispatchQueue.main.async { [weak self] in self?.applyFocus(animated: true) }
        }
    }

    private func centerContent() {
        let dx = max(0, (bounds.width - contentSize.width) / 2)
        let dy = max(0, (bounds.height - contentSize.height) / 2)
        let inset = UIEdgeInsets(top: dy, left: dx, bottom: dy, right: dx)
        if contentInset != inset { contentInset = inset }
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        imageView
    }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        centerContent()
    }

    @objc private func onDoubleTap(_ recognizer: UITapGestureRecognizer) {
        if zoomScale > 1.01 {
            setZoomScale(1, animated: true)
        } else {
            let p = recognizer.location(in: imageView)
            let target: CGFloat = 2.5
            let size = CGSize(width: bounds.width / target, height: bounds.height / target)
            zoom(to: CGRect(x: p.x - size.width / 2, y: p.y - size.height / 2,
                            width: size.width, height: size.height), animated: true)
        }
    }
}
