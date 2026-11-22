import SwiftUI
import UIKit

// MARK: - 特效

struct EffectPanel: View {
    @Bindable var model: EditorModel

    private var state: EffectState { model.effectState }

    var body: some View {
        let kind = state.params.kind
        VStack(spacing: 10) {
            if let error = state.error {
                Text(error)
                    .font(.system(size: 13))
                    .foregroundStyle(Color(red: 1, green: 0.45, blue: 0.4))
            }
            HStack(spacing: 12) {
                Text(kind == nil ? "–" : "\(Int((state.params.strength * 100).rounded()))")
                    .font(.system(size: 15, weight: .semibold))
                    .monospacedDigit()
                    .frame(width: 44, alignment: .leading)
                Slider(value: Binding(get: { Double(state.params.strength) },
                                      set: { model.setEffectStrength(CGFloat($0)) }), in: 0...1)
            }
            .opacity(kind == nil ? 0.3 : 1)
            .disabled(kind == nil)

            variantRow(kind)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(EffectCategory.allCases) { category in
                        Chip(title: category.title, selected: state.category == category,
                             badge: category == .sky ? "AI" : nil) {
                            model.effectState.category = category
                        }
                    }
                }
                .padding(.top, 4)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 10) {
                    if state.category == .sky {
                        ForEach(EffectSky.all) { sky in
                            ImageTile(title: sky.title, image: Self.skyThumb(sky), badge: "AI") {
                                model.startAIPreset(sky.preset, group: .remove)
                            }
                        }
                    } else {
                        ImageTile(title: "无", image: state.original, selected: kind == nil, symbol: "circle.slash") {
                            model.selectEffect(nil)
                        }
                        ForEach(EffectKind.list(state.category)) { item in
                            ImageTile(title: item.title, image: state.thumbs[item.id], selected: kind == item,
                                      symbol: "sparkles") {
                                model.selectEffect(item)
                            }
                            .overlay(alignment: .top) {
                                if item.needsMask, state.maskStatus == .working, kind == item {
                                    ProgressView().tint(.white).frame(width: 68, height: 84)
                                }
                            }
                        }
                    }
                }
                .padding(.vertical, 2)
            }
            .id(state.category)
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 6)
    }

    /// 颜色 / 变体，随机排布的效果多一个"换一换"。没有时留出同样的高度，面板不跳。
    @ViewBuilder
    private func variantRow(_ kind: EffectKind?) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if let kind {
                    ForEach(Array(kind.variants.enumerated()), id: \.offset) { i, v in
                        Chip(title: v.title, selected: state.params.variant == i) { model.setEffectVariant(i) }
                    }
                    if kind.shuffles {
                        Button { model.shuffleEffect() } label: {
                            Image(systemName: "dice")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 34, height: 30)
                                .background(Theme.chip, in: Capsule())
                        }
                        .buttonStyle(PressStyle())
                    }
                }
            }
        }
        .frame(height: 32)
    }

    // MARK: 换天的缩略图：渐变色块 + 图标

    private static var skyThumbs: [String: UIImage] = [:]

    private static func skyThumb(_ sky: EffectSky) -> UIImage {
        if let hit = skyThumbs[sky.id] { return hit }
        let size = CGSize(width: 68, height: 84)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 3
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            let colors = sky.colors.map { v in
                UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
                        blue: CGFloat(v & 0xFF) / 255, alpha: 1).cgColor
            }
            if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: nil) {
                context.cgContext.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: size.height), options: [])
            }
            let config = UIImage.SymbolConfiguration(pointSize: 24, weight: .regular)
            if let symbol = UIImage(systemName: sky.symbol, withConfiguration: config)?
                .withTintColor(.white.withAlphaComponent(0.92), renderingMode: .alwaysOriginal) {
                let s = symbol.size
                symbol.draw(in: CGRect(x: (size.width - s.width) / 2, y: size.height * 0.36 - s.height / 2,
                                       width: s.width, height: s.height))
            }
        }
        skyThumbs[sky.id] = image
        return image
    }
}
