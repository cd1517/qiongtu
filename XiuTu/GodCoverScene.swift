import Foundation

/// 封神的场景模式：封面的主角是照片里的场景本身。AI 改造的选项和指令都在本机拼，不许加人。
enum GodCoverScene {
    /// AI 改造里的几组选项。
    enum Group: String, CaseIterable, Identifiable {
        case look, light, weather, season, time, tidy

        var id: String { rawValue }

        var title: String {
            switch self {
            case .look: return "大片感"
            case .light: return "光线"
            case .weather: return "天气"
            case .season: return "季节"
            case .time: return "时间"
            case .tidy: return "清理"
            }
        }

        /// 光线、天气、季节、时间和摄影棚共用同一套。
        var shared: SceneLook.Group? {
            switch self {
            case .light: return .light
            case .weather: return .weather
            case .season: return .season
            case .time: return .time
            case .look, .tidy: return nil
            }
        }

        var options: [SceneLook.Option] {
            switch self {
            case .look: return GodCoverScene.looks
            case .tidy: return GodCoverScene.tidy
            default: return shared?.options ?? []
            }
        }

        var fallback: String {
            switch self {
            case .look: return "medium"
            default: return SceneLook.keep
            }
        }
    }

    static let looks: [SceneLook.Option] = [
        SceneLook.Option(id: "none", title: "原样", prompt: "不做风格化处理：光线、颜色和构图都保留原图，只做正常的画质整理。"),
        SceneLook.Option(id: "subtle", title: "自然", prompt: "轻度：像专业摄影师在现场认真拍的一张，曝光和白平衡准确，反差适中，横平竖直，保留现场真实的光线和颜色。"),
        SceneLook.Option(id: "medium", title: "明显", prompt: "中度：处理成专业杂志摄影师拍的大片，光线有设计感和方向感，画面干净讲究，色彩统一，质感高级。"),
        SceneLook.Option(id: "strong", title: "强烈", prompt: "强烈：处理成跨页级别的视觉大片，戏剧化的光线和影调，大胆统一的色彩风格，强烈的视觉冲击力，但仍然是一张真实的照片。"),
    ]

    static let tidy: [SceneLook.Option] = [
        SceneLook.Option(id: SceneLook.keep, title: "保持原样"),
        SceneLook.Option(id: "clean", title: "清理杂物", prompt: "【清理杂物】去掉画面里分散注意力的零碎杂物（垃圾桶、杂乱的线缆、随手放的零碎、突兀的标牌等），并自然地补全背景。只清理零碎：建筑、家具、主要物品和整体布局不变；原图里的人不算杂物，保持原样。"),
    ]

    /// 选中的风格是哪一类印刷品，决定指令里怎么称呼它、排版上要有哪些文字。
    private static func kind(category: String, name: String) -> (noun: String, elements: String) {
        if category.contains("电影") { return ("电影海报", "片名、一句宣传语、演职员表和上映信息") }
        if category.contains("专辑") { return ("音乐专辑封面", "专辑名和艺人名") }
        if category.contains("广告") { return ("品牌广告海报", "品牌标志的位置和一句广告语") }
        if category.contains("真人秀") { return ("真人秀宣传海报", "节目名、一句宣传语和播出信息") }
        if category.contains("政治") { return ("宣传海报", "大字标语和口号") }
        if category.contains("艺术") { return ("艺术展览海报", "展览名、展期和场馆信息") }
        return ("杂志封面", "刊名「\(name)」、几行封面文案、期号和日期")
    }

    /// 风格的一句话介绍里，说读者是谁的那几个词用不上（场景里没有人），带年龄暗示的不写进指令。
    private static func artDirection(_ desc: String) -> String {
        let skip = ["学生", "高中", "小学", "女生", "少女", "少年", "儿童", "制服", "JK"]
        return desc.components(separatedBy: "·")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { part in !part.isEmpty && !skip.contains { part.contains($0) } }
            .joined(separator: "、")
    }

    /// values：每组选了哪一项。texts：勾了"让 AI 用我的文字"时的四条文字（主标题、副标题、附加 1、附加 2），否则为空。
    static func prompt(styleName: String, styleDesc: String, category: String, values: [Group: String],
                       noText: Bool, texts: [String], orientation: String) -> String {
        let (noun, elements) = kind(category: category, name: styleName)
        let direction = artDirection(styleDesc)
        let short = "「\(styleName)」\(noun)"
        let named = short + (direction.isEmpty ? "" : "（\(direction)）")
        func option(_ group: Group) -> SceneLook.Option {
            let id = values[group] ?? group.fallback
            return group.options.first { $0.id == id } ?? group.options[0]
        }

        var parts = [
            "这是一张场景照片，要用它做\(named)的主图：这张\(noun)的主角就是这个场景本身，不是人。",
            SceneLook.keepScene,
            SceneLook.noPerson + "不要出现封面模特或任何人像。",
        ]
        let look = option(.look)
        parts.append("【大片感】\(look.prompt)" + (look.id == "none" ? "" : "整体的色调和气质要配得上\(short)一贯的视觉风格。"))
        var picks: [SceneLook.Group: String] = [:]
        for group in Group.allCases {
            if let shared = group.shared { picks[shared] = option(group).id }
        }
        parts.append(SceneLook.atmosphere(picks))
        let clean = option(.tidy)
        if !clean.prompt.isEmpty { parts.append(clean.prompt) }

        if noText {
            parts.append("【输出】只要一张干净的照片，留给后面排版用：不要刊名、标题、文字、logo、条形码、价格、水印和任何排版元素。真实的照片质感，\(orientation)画面，取景范围和原图一致。")
        } else {
            var design = "【封面设计】把它设计成一张完整的\(short)，竖幅：加上\(elements)，字体和版式是这类\(noun)典型的样子。文字排在画面的留白处（天空、墙面、地面等），不要遮住场景里最重要的部分。原图是横幅时，取场景里最有代表性的部分做竖幅构图，可以把上下的天空、天花板或地面自然延伸，不要拉伸变形。除了排版文字，不要加别的图形和人物。"
            let labels = ["主标题", "副标题", "附加文字", "附加文字"]
            let custom = zip(labels, texts).filter { !$0.1.isEmpty }.map { "\($0.0)「\($0.1)」" }
            if !custom.isEmpty {
                design += "上面的文字必须用下面这几条，一字不差，代替你自己拟的文案：\(custom.joined(separator: "；"))。"
            }
            parts.append(design)
        }
        return parts.joined(separator: "\n")
    }
}
