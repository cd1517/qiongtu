import SwiftUI

/// 手动美体：用手指推拉画面，全部在本机完成。
struct LiquifyPanel: View {
    @Bindable var model: EditorModel

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                BodyModeChips(model: model)
                Spacer()
            }
            sliderRow(title: "大小", value: $model.liquifyBrush, range: 24...200)
            sliderRow(title: "力度", value: $model.liquifyStrength, range: 0.15...1)
            HStack(spacing: 4) {
                IconTile(symbol: "hand.draw", title: "推", selected: !model.liquifyRestore) { model.liquifyRestore = false }
                IconTile(symbol: "bandage", title: "恢复", selected: model.liquifyRestore) { model.liquifyRestore = true }
                IconTile(symbol: model.liquifyLockBusy ? "hourglass" : "lock.rectangle", title: "固定背景",
                         selected: model.liquifyLock) { model.setLiquifyLock(!model.liquifyLock) }
                Spacer()
                IconTile(symbol: "arrow.uturn.backward", title: "撤销一笔") { model.undoStroke() }
                    .opacity(model.liquify.segments.isEmpty ? 0.35 : 1)
                IconTile(symbol: "arrow.counterclockwise", title: "全部还原") { model.resetLiquify() }
                    .opacity(model.liquify.segments.isEmpty ? 0.35 : 1)
            }
            if let note = model.liquifyNote {
                Text(note)
                    .font(.system(size: 12))
                    .foregroundStyle(Color(red: 1, green: 0.45, blue: 0.4))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 4)
    }

    private func sliderRow(title: String, value: Binding<CGFloat>, range: ClosedRange<Double>) -> some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.system(size: 13))
                .foregroundStyle(Theme.dim)
                .frame(width: 44, alignment: .leading)
            Slider(value: Binding(get: { Double(value.wrappedValue) }, set: { value.wrappedValue = CGFloat($0) }),
                   in: range)
        }
    }
}
