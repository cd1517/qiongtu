import Photos
import SwiftUI

/// 相册选图：点缩略图直接进编辑，点右下角的小按钮先放大预览。
struct PhotoPickerView: View {
    var onPick: (String) -> Void
    var onClose: () -> Void

    @State private var library = PhotoLibrary()
    @State private var preview: PreviewStart?

    private struct PreviewStart: Identifiable {
        let id: Int
    }

    private let spacing: CGFloat = 2
    private let columnCount = 4

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                content
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
        .task { await library.requestAccess() }
        .fullScreenCover(item: $preview) { start in
            PreviewPager(library: library, startIndex: start.id) { id in
                preview = nil
                onPick(id)
            }
        }
    }

    private var header: some View {
        ZStack {
            Menu {
                ForEach(library.albums) { album in
                    Button("\(album.title)（\(album.count)）") { library.select(album) }
                }
            } label: {
                HStack(spacing: 6) {
                    Text(library.current?.title ?? "相册")
                        .font(.system(size: 17, weight: .semibold))
                    Image(systemName: "chevron.down").font(.system(size: 12, weight: .bold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 9)
                .background(Theme.chip, in: Capsule())
            }
            HStack {
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                }
                Spacer()
            }
        }
        .padding(.horizontal, 8)
        .frame(height: 56)
    }

    @ViewBuilder
    private var content: some View {
        switch library.access {
        case .unknown:
            Spacer()
            ProgressView().tint(.white)
            Spacer()
        case .denied:
            Spacer()
            VStack(spacing: 14) {
                Text("没有相册权限").font(.headline)
                Text("请在系统设置里允许访问照片。")
                    .font(.subheadline)
                    .foregroundStyle(Theme.dim)
                Button("打开设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                .buttonStyle(.borderedProminent)
            }
            Spacer()
        case .granted:
            if library.count == 0 {
                Spacer()
                Text("这个相簿里没有图片").foregroundStyle(Theme.dim)
                Spacer()
            } else {
                grid
            }
        }
    }

    private var grid: some View {
        GeometryReader { geo in
            let side = (geo.size.width - spacing * CGFloat(columnCount - 1)) / CGFloat(columnCount)
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.fixed(side), spacing: spacing), count: columnCount),
                          spacing: spacing) {
                    ForEach(0..<library.count, id: \.self) { index in
                        if let asset = library.asset(at: index) {
                            cell(asset: asset, index: index, side: side)
                        }
                    }
                }
            }
        }
    }

    private func cell(asset: PHAsset, index: Int, side: CGFloat) -> some View {
        ZStack(alignment: .bottomTrailing) {
            AssetThumb(asset: asset, side: side)
                .contentShape(Rectangle())
                .onTapGesture { onPick(asset.localIdentifier) }
            Button { preview = PreviewStart(id: index) } label: {
                Image(systemName: "arrow.up.left.and.arrow.down.right")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 26, height: 26)
                    .background(Color.black.opacity(0.55), in: Circle())
                    .padding(5)
            }
            .buttonStyle(.plain)
        }
    }
}

/// 全屏预览，可以左右滑动换图。
private struct PreviewPager: View {
    let library: PhotoLibrary
    let startIndex: Int
    var onEdit: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var current: Int?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            ScrollView(.horizontal) {
                LazyHStack(spacing: 0) {
                    ForEach(0..<library.count, id: \.self) { index in
                        PreviewPage(asset: library.asset(at: index))
                            .containerRelativeFrame([.horizontal, .vertical])
                            .id(index)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $current)
            .scrollIndicators(.hidden)
            .ignoresSafeArea()

            VStack {
                HStack {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 40, height: 40)
                            .background(Color.black.opacity(0.5), in: Circle())
                    }
                    Spacer()
                    Text("\((current ?? startIndex) + 1) / \(library.count)")
                        .font(.system(size: 14, weight: .medium))
                        .monospacedDigit()
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.black.opacity(0.5), in: Capsule())
                }
                Spacer()
                Button {
                    if let asset = library.asset(at: current ?? startIndex) {
                        onEdit(asset.localIdentifier)
                    }
                } label: {
                    Text("编辑这张")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(.black)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(Color.white, in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
        }
        .onAppear { current = startIndex }
    }
}

private struct PreviewPage: View {
    let asset: PHAsset?
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color.black
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
            } else {
                ProgressView().tint(.white)
            }
        }
        .task(id: asset?.localIdentifier) {
            guard let asset else { return }
            image = await PhotoLibrary.image(for: asset, pixelSize: CGSize(width: 1600, height: 1600), fill: false)
        }
    }
}
