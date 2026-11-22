import SwiftUI
import UIKit

// 漫画人生的角色库。画风、年龄段、性别的定义和给 AI 的指令原样取自 LifeManga。

enum CharacterArtStyle: String, CaseIterable, Codable, Identifiable, Hashable {
    case jpAnime
    case usComics
    case krManhwa
    case kawaii
    case chibi
    case render3D
    case semiReal
    case watercolor
    case pixelArt

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .jpAnime:    return "日漫风"
        case .usComics:   return "美漫风"
        case .krManhwa:   return "韩漫风"
        case .kawaii:     return "可爱风"
        case .chibi:      return "Q 版"
        case .render3D:   return "3D 渲染"
        case .semiReal:   return "皮克斯风"
        case .watercolor: return "水彩风"
        case .pixelArt:   return "像素风"
        }
    }

    var subtitle: String {
        switch self {
        case .jpAnime:    return "京阿尼 / 新海诚 现代动画风"
        case .usComics:   return "Marvel / DC 美式漫画"
        case .krManhwa:   return "顶级 webtoon · 精致细腻"
        case .kawaii:     return "粉嫩、大眼、软萌"
        case .chibi:      return "2~3 头身 SD 缩小可爱"
        case .render3D:   return "皮克斯 / 平行宇宙 3D 渲染"
        case .semiReal:   return "欧美动画电影角色设定感"
        case .watercolor: return "水彩晕染 + 纸纹"
        case .pixelArt:   return "16-bit JRPG / 复古游戏立绘"
        }
    }

    var symbolName: String {
        switch self {
        case .jpAnime:    return "sparkles"
        case .usComics:   return "bolt.fill"
        case .krManhwa:   return "crown.fill"
        case .kawaii:     return "heart.fill"
        case .chibi:      return "face.smiling.fill"
        case .render3D:   return "cube.fill"
        case .semiReal:   return "movieclapper.fill"
        case .watercolor: return "drop.fill"
        case .pixelArt:   return "square.grid.3x3.fill"
        }
    }

    /// 给 gpt-image-2 的英文风格 prompt
    /// 注意：避免提及具体品牌（Marvel/DC）、武打作品（镖人）、儿童相关词汇等可能触发安全系统的词
    var prompt: String {
        switch self {
        case .jpAnime:
            return """
            Modern Japanese anime/manga character illustration style. Clean cel-shaded \
            coloring with crisp confident lineart. Large expressive anime eyes with \
            highlights, stylized hair with shine, vibrant clean color palette. \
            Polished modern TV-anime production aesthetic.
            """
        case .usComics:
            return """
            Modern American graphic-novel character illustration style. Bold confident \
            inking with strong line-weight variation. Realistic body proportions with \
            slightly stylized features. Strong cel-shading with dramatic but tasteful \
            shadows. Polished, painterly, professional comic illustration aesthetic. \
            (No specific franchise; original character design.)
            """
        case .krManhwa:
            return """
            Modern Korean webtoon character illustration style — polished glossy digital \
            illustration with clean painterly cel-shading. Realistic body proportions \
            with softly anime-influenced features, beautifully detailed expressive eyes, \
            sharp confident lineart, vibrant modern color palette, smooth gradient \
            shading on skin and hair. High-production-value webtoon aesthetic with \
            cinematic lighting and dramatic poses. Original character design.
            """
        case .kawaii:
            return """
            Soft kawaii illustration style with a pastel palette (pinks, peaches, \
            lavenders, mint). Large sparkly round eyes, simplified soft features, \
            gentle rounded shapes, dreamy peaceful atmosphere. Cute mascot-friendly \
            aesthetic. (Adult character; cute style only — keep adult facial structure \
            and proportions.)
            """
        case .chibi:
            return """
            Stylized chibi / super-deformed cartoon ART STYLE applied to an ADULT \
            character — playfully exaggerated proportions (about 3-4 heads tall), \
            simplified rounded body, large expressive eyes, simplified hands. \
            IMPORTANT: this is an adult character rendered in chibi STYLE (like an \
            adult mascot or comedic short-form animation spinoff). NOT a depiction of a \
            child, baby, or minor — keep the adult outfit, adult bearing, and any adult \
            props (work bag, glasses, etc) visible. Cute, comedic cartoon aesthetic.
            """
        case .render3D:
            return """
            Modern 3D-animated film character illustration style — soft realistic \
            lighting with subtle sub-surface skin scattering, painterly fabric textures, \
            stylized but rendered look, gentle depth of field. Polished feature-film CG \
            aesthetic. Original character design (no specific franchise).
            """
        case .semiReal:
            return """
            Stylized western animated feature-film character design style. Friendly \
            family-animation appeal with large expressive eyes, rounded appealing facial \
            features, confident eyebrow shapes, clean stylized anatomy, and polished soft \
            3D-like shading. Feels like a premium animation-studio character sheet with \
            expressive turnarounds, expression heads, and pose-friendly silhouette design. \
            Warm, charming, original animated-movie aesthetic.
            """
        case .watercolor:
            return """
            Traditional watercolor illustration style. Soft loose washes of color, \
            organic blooming edges, visible paper texture, painterly imperfection. \
            Controlled detail in the face with looser strokes elsewhere. Romantic, \
            gentle, hand-painted feel. Studio-Ghibli-inspired concept-art aesthetic.
            """
        case .pixelArt:
            return """
            16-bit JRPG pixel-art character illustration style. Clean visible square \
            pixels, limited vivid retro color palette, color blocking with deliberate \
            dithering for shading. Distinct chunky pixel shapes for hair, eyes, and \
            clothing. SNES / Game Boy Advance era role-playing game character portrait \
            aesthetic. Render every element (character, callouts, expressions, items) \
            in pixel-art form with a clearly visible pixel grid. Do NOT smooth the \
            pixels into a normal illustration.
            """
        }
    }
}

enum CharacterAgeGroup: String, CaseIterable, Codable, Identifiable, Hashable {
    case baby
    case child
    case teen
    case youngAdult
    case middleAged
    case elderly

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .baby: return "小宝宝"
        case .child: return "小孩子"
        case .teen: return "少年"
        case .youngAdult: return "青年"
        case .middleAged: return "中年"
        case .elderly: return "老年"
        }
    }

    var promptPhrase: String {
        switch self {
        case .baby: return "a baby"
        case .child: return "a young child"
        case .teen: return "a teenager"
        case .youngAdult: return "a young adult"
        case .middleAged: return "a middle-aged adult"
        case .elderly: return "an elderly adult"
        }
    }

    var isMinor: Bool {
        self == .baby || self == .child
    }
}

enum CharacterGender: String, CaseIterable, Codable, Identifiable, Hashable {
    case male
    case female

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .male: return "男"
        case .female: return "女"
        }
    }

    var promptPhrase: String {
        switch self {
        case .male: return "male"
        case .female: return "female"
        }
    }
}

/// 角色库里的一个角色：一张由照片生成的标准立绘，加上名字和设定。
struct MangaCharacter: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var bio: String
    var ageGroup: CharacterAgeGroup
    var gender: CharacterGender
    var artStyle: CharacterArtStyle
    var imageFile: String
    var createdAt = Date()
    /// 三视图（正面、侧面、背面画在一张横图上）。没生成过就是 nil。
    var sheetFile: String? = nil
}

enum CharacterPrompts {
    static func buildCharacterPortraitPrompt(name: String,
                                              bio: String?,
                                              ageGroup: CharacterAgeGroup,
                                              gender: CharacterGender,
                                              artStyle: CharacterArtStyle) -> String {
        var lines: [String] = []
        lines.append("Create a polished full-body CHARACTER PORTRAIT based on the reference photo.")
        lines.append("This is NOT a collage or design board. It must be a single clean standing character illustration.")
        lines.append("")
        lines.append("Composition:")
        lines.append("• One \(ageGroup.promptPhrase) \(gender.promptPhrase) character only, full body visible from head to shoes.")
        lines.append("• Plain pure white or very light neutral studio background.")
        lines.append("• Clean silhouette, natural relaxed standing pose, fashion-illustration presentation.")
        lines.append("• No extra props, no text, no labels, no callout boxes, no duplicate heads.")
        lines.append("")
        lines.append("Character: \(name).\(bio.flatMap { $0.isEmpty ? nil : " \($0)." } ?? "")")
        lines.append("")
        lines.append("Art style: \(artStyle.prompt)")
        lines.append("")
        lines.append("Rules: The character should clearly read as \(ageGroup.promptPhrase) and \(gender.promptPhrase), not another life stage. Match face, hair, body build and vibe from the source photo as closely as the policy allows. Make the outfit feel signature and coherent with the bio. Render in full color. Premium clean character reference illustration quality.")
        return lines.joined(separator: "\n")
    }

    /// 三视图：以角色库里的标准立绘为准，把同一个角色的正面、侧面、背面画在一张横图上。
    /// 三个视图的描述取自 LifeManga 的 defaultCharacterViews，版式要求取自它的角色设定稿指令。
    static func turnaroundPrompt(for character: MangaCharacter) -> String {
        var lines: [String] = []
        lines.append("Create a CHARACTER TURNAROUND SHEET of the character shown in the reference image.")
        lines.append("")
        lines.append("CHARACTER:")
        lines.append("- Name: \(character.name)")
        if !character.bio.isEmpty { lines.append("- Background: \(character.bio)") }
        lines.append("- The reference image is this character's canonical design. Keep the face, hairstyle, hair color, body build, outfit, colors and accessories IDENTICAL in every view. Do not redesign anything.")
        lines.append("- The character is \(character.ageGroup.promptPhrase) and \(character.gender.promptPhrase).")
        lines.append("")
        lines.append("THREE VIEWS, side by side in one wide image, same scale, feet on the same ground line, evenly spaced:")
        lines.append("1. LEFT — FRONT: Standing facing camera, full body, neutral expression, neutral A-pose.")
        lines.append("2. CENTER — SIDE: Standing in profile, side view, full body silhouette readable.")
        lines.append("3. RIGHT — BACK: Standing facing AWAY from camera, full body view of the back of the character.")
        lines.append("")
        lines.append("LAYOUT:")
        lines.append("- Plain neutral background (off-white or very light gray), no scenery, no extra props.")
        lines.append("- Each figure fully visible from head to feet, not overlapping the others.")
        lines.append("- No text, no labels, no caption, no arrows, no extra heads or detail callouts. Just the three figures on plain background.")
        lines.append("- Composition reads cleanly as a turnaround/character-sheet illustration.")
        lines.append("")
        lines.append("Art style: \(character.artStyle.prompt)")
        return lines.joined(separator: "\n")
    }

    /// 画漫画时载入了角色：告诉 AI 最后几张参考图是角色设定。
    static func directive(for characters: [MangaCharacter]) -> String? {
        guard !characters.isEmpty else { return nil }
        let names = characters.map { $0.name }.joined(separator: "、")
        return """
        CHARACTER REFERENCE:
        I have attached \(characters.count) character reference image(s) at the end of \
        the input set. These represent the recurring characters that should appear in this \
        page (\(names)). Match their faces, hair, outfits and overall design EXACTLY as shown. \
        Do not redesign them.

        IMPORTANT REFERENCE-USE RULE:
        Use ONLY the main standing character figure from each reference as the canonical design. \
        Ignore any side expression heads, accessory callouts, prop layouts, object boards, arrows, \
        labels, multi-panel sheet layout, background decorations, or extra design-sheet elements \
        that may appear around the character in a character sheet. Those surrounding helper elements \
        are reference aids only and must NOT be copied into the manga unless the user explicitly \
        asked for that object or prop in the scene.
        """
    }
}

/// 角色库：存在本机（App 的资料目录），不上传。
@MainActor
@Observable
final class CharacterStore {
    static let shared = CharacterStore()

    private(set) var characters: [MangaCharacter] = []
    @ObservationIgnored private let folder: URL
    @ObservationIgnored private var cache: [String: UIImage] = [:]

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        folder = base.appendingPathComponent("characters", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: folder.appendingPathComponent("index.json")),
           let list = try? JSONDecoder().decode([MangaCharacter].self, from: data) {
            characters = list
        }
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(characters) {
            try? data.write(to: folder.appendingPathComponent("index.json"), options: .atomic)
        }
    }

    func image(for character: MangaCharacter) -> UIImage? {
        if let hit = cache[character.imageFile] { return hit }
        let image = UIImage(contentsOfFile: folder.appendingPathComponent(character.imageFile).path)
        cache[character.imageFile] = image
        return image
    }

    @discardableResult
    func add(name: String, bio: String, ageGroup: CharacterAgeGroup, gender: CharacterGender,
             artStyle: CharacterArtStyle, image: UIImage) -> MangaCharacter? {
        let file = UUID().uuidString + ".jpg"
        guard let data = image.jpegData(compressionQuality: 0.93),
              (try? data.write(to: folder.appendingPathComponent(file), options: .atomic)) != nil else { return nil }
        let character = MangaCharacter(name: name, bio: bio, ageGroup: ageGroup, gender: gender, artStyle: artStyle, imageFile: file)
        characters.insert(character, at: 0)
        cache[file] = image
        persist()
        return character
    }

    func sheet(for character: MangaCharacter) -> UIImage? {
        guard let file = character.sheetFile else { return nil }
        if let hit = cache[file] { return hit }
        let image = UIImage(contentsOfFile: folder.appendingPathComponent(file).path)
        cache[file] = image
        return image
    }

    /// 给角色存上（或换掉）三视图。
    func setSheet(_ image: UIImage, for id: UUID) {
        guard let index = characters.firstIndex(where: { $0.id == id }),
              let data = image.jpegData(compressionQuality: 0.93) else { return }
        let file = UUID().uuidString + "-sheet.jpg"
        guard (try? data.write(to: folder.appendingPathComponent(file), options: .atomic)) != nil else { return }
        if let old = characters[index].sheetFile {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(old))
            cache[old] = nil
        }
        characters[index].sheetFile = file
        cache[file] = image
        persist()
    }

    /// 生成三视图：把标准立绘发给 AI，拿回一张横图。返回这次的费用（算不出来就是 nil）。
    func generateSheet(for id: UUID) async throws -> Double? {
        guard let character = characters.first(where: { $0.id == id }), let portrait = image(for: character) else {
            throw AIError(text: "找不到这个角色的立绘。")
        }
        let result = try await AIOneShot.run(prompt: CharacterPrompts.turnaroundPrompt(for: character),
                                             images: [portrait], size: "1536x1024")
        setSheet(result.image, for: id)
        return result.usage?.cost
    }

    func remove(_ character: MangaCharacter) {
        characters.removeAll { $0.id == character.id }
        try? FileManager.default.removeItem(at: folder.appendingPathComponent(character.imageFile))
        cache[character.imageFile] = nil
        if let sheet = character.sheetFile {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(sheet))
            cache[sheet] = nil
        }
        persist()
    }
}
