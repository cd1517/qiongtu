import UIKit

/// 场景模式（封神、摄影棚共用）的氛围选项，和每条场景指令都要带的两条规矩。
enum SceneLook {
    struct Option: Identifiable, Hashable {
        let id: String
        let title: String
        /// 写进指令的那句话；"保持原样"是空的。
        var prompt = ""
    }

    /// "保持原样"这一项的 id。
    static let keep = "keep"
    private static let keepOption = Option(id: keep, title: "保持原样")

    enum Group: String, CaseIterable, Identifiable {
        case time, weather, season, light

        var id: String { rawValue }

        var title: String {
            switch self {
            case .time: return "时间"
            case .weather: return "天气"
            case .season: return "季节"
            case .light: return "光线"
            }
        }

        var options: [Option] {
            switch self {
            case .time:
                return [
                    SceneLook.keepOption,
                    Option(id: "dawn", title: "清晨", prompt: "清晨日出前后：天空从靛蓝过渡到淡淡的粉橙色，光线清冷柔和，空气里有一点薄雾。"),
                    Option(id: "morning", title: "上午", prompt: "上午：干净明亮的斜射阳光，影子长而柔和，空气通透。"),
                    Option(id: "noon", title: "正午", prompt: "正午：头顶的直射阳光，影子短而硬，颜色鲜明。"),
                    Option(id: "afternoon", title: "下午", prompt: "下午：偏低的暖色斜阳，蜜色的光，影子拉长。"),
                    Option(id: "golden", title: "黄昏金光", prompt: "日落前的黄金时刻：低角度的金橙色阳光，长长的影子，高光偏暖、阴影微冷。"),
                    Option(id: "blue", title: "蓝调时刻", prompt: "日落后的蓝调时刻：天空是深邃的钴蓝色，环境光偏冷，场景里原有的灯和窗亮起暖光。"),
                    Option(id: "night", title: "夜景", prompt: "夜晚：天空全黑，场景由它自己原有的灯光照亮（路灯、窗内的灯、室内灯具），明暗对比大。"),
                    Option(id: "latenight", title: "深夜", prompt: "深夜：只剩零星几处灯光，大片深沉的暗部，安静空旷。"),
                ]
            case .weather:
                return [
                    SceneLook.keepOption,
                    Option(id: "sunny", title: "晴天", prompt: "晴天：蓝天，阳光直射，阴影清晰。"),
                    Option(id: "cloudy", title: "多云", prompt: "多云：天上有层次分明的云，阳光时隐时现。"),
                    Option(id: "overcast", title: "阴天", prompt: "阴天：均匀柔和的漫射光，几乎没有硬阴影，颜色偏淡。"),
                    Option(id: "rain", title: "雨天", prompt: "雨天：正在下雨，室外的地面和物体表面湿润反光，有积水和倒影。"),
                    Option(id: "afterrain", title: "雨后", prompt: "雨后初晴：室外的地面还是湿的、有倒影，空气通透，云缝里透出阳光。"),
                    Option(id: "snow", title: "雪天", prompt: "雪天：正在下雪，室外的地面、屋顶和枝叶上有积雪，光线偏冷而明亮。"),
                    Option(id: "fog", title: "雾天", prompt: "雾天：浓雾，远处的东西逐渐隐入灰白，光线弥散。"),
                ]
            case .season:
                return [
                    SceneLook.keepOption,
                    Option(id: "spring", title: "春", prompt: "春天：植物是新绿，有花开，光线清新。"),
                    Option(id: "summer", title: "夏", prompt: "夏天：植物浓绿茂盛，阳光强烈，天空高远。"),
                    Option(id: "autumn", title: "秋", prompt: "秋天：树叶转成金黄和红色，光线偏暖偏低。"),
                    Option(id: "winter", title: "冬", prompt: "冬天：落叶树只剩枝干，色调清冷，空气干净。"),
                ]
            case .light:
                return [
                    SceneLook.keepOption,
                    Option(id: "soft", title: "柔光", prompt: "柔和的漫射光：明暗过渡细腻，没有硬阴影。"),
                    Option(id: "hard", title: "硬光", prompt: "硬朗的直射光：阴影清晰、边缘锐利，反差大。"),
                    Option(id: "side", title: "侧光", prompt: "低角度的侧光：强调材质和立体感，影子拉长。"),
                    Option(id: "back", title: "逆光", prompt: "逆光：光从场景后方照过来，物体边缘有一圈亮边，带一点眩光和空气感。"),
                    Option(id: "window", title: "窗光", prompt: "从一侧窗户进来的自然光：亮面到暗面过渡柔和，暗部保留层次。"),
                    Option(id: "beam", title: "丁达尔光", prompt: "丁达尔光束：阳光穿过空气形成一道道看得见的光束，带薄雾感。"),
                    Option(id: "neon", title: "霓虹", prompt: "像被附近的霓虹灯照亮：洋红和青蓝的彩色光染在场景表面，暗部深沉；不要凭空加招牌。"),
                    Option(id: "lowkey", title: "低调暗光", prompt: "低调暗光：大面积深沉的暗部，只有一小部分被一束光照亮。"),
                    Option(id: "highkey", title: "高调明亮", prompt: "高调明亮：整体明亮通透，阴影很浅，高光不溢出。"),
                ]
            }
        }

        func option(_ id: String?) -> Option {
            options.first { $0.id == id } ?? SceneLook.keepOption
        }
    }

    /// 场景里有什么、在哪里，都以原图为准。
    static let keepScene = "【场景保持】地点、建筑和空间结构、家具陈设、物品以及它们之间的位置关系都以原图为准，要能一眼认出是同一个地方。不要把场景搬到别的地点，不要添加原图里没有的显眼物体，也不要拿掉原有的主要物体。"

    /// 不许加人；原图里本来就有的人留着不动。
    static let noPerson = "【不要加人】画面里不许新增任何人物：不要模特，不要路人，不要人影和剪影，镜子、玻璃的倒影里也不要出现人。原图里如果本来就有人（比如远处的路人），让他们留在原处、保持原样，不要放大成主角，也不要再多加。"

    /// 选了的写成一段"氛围"；时间、天气、季节没选的注明按原图。没选光线时不提，交给摄影风格去处理。
    static func atmosphere(_ picks: [Group: String]) -> String {
        var changed: [String] = []
        var kept: [String] = []
        for group in Group.allCases {
            let option = group.option(picks[group])
            if !option.prompt.isEmpty {
                changed.append("\(group.title)改成\(option.prompt)")
            } else if group != .light {
                kept.append(group.title)
            }
        }
        var text = "【氛围】" + changed.joined()
        if !kept.isEmpty { text += "\(kept.joined(separator: "、"))按原图，不要改。" }
        if !changed.isEmpty { text += "这些变化只落在天空、植物、光线和室外的地面上；室内场景通过窗外的景色和照进来的光体现。建筑、陈设和物品不变。" }
        return text
    }

    /// 画幅怎么说。
    static func orientation(of image: UIImage) -> String {
        switch AIOneShot.size(matching: image) {
        case "1536x1024": return "横幅"
        case "1024x1536": return "竖幅"
        default: return "方形"
        }
    }
}
