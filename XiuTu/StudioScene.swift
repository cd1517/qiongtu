import UIKit

/// 摄影棚的场景模式：照片里的场景就是拍摄对象，请摄影师到原地重拍一组，不加人、不换地方。
/// 指令全部在本机拼，不经过网页引擎里那套给人物用的排片。
enum StudioScene {
    /// 一种拍法；takes 是同一种拍法拍第二、第三张时换的说法。
    struct Variation: Identifiable, Hashable {
        let id: String
        let title: String
        let takes: [String]
    }

    static let counts = [1, 3, 6, 10]
    static let defaultVariations: Set<String> = ["keep", "wide", "detail", "angle"]

    static let variations: [Variation] = [
        Variation(id: "keep", title: "原构图", takes: [
            "机位、角度、焦段和取景范围都和原图一致，画面里每样东西都在原来的位置上。",
        ]),
        Variation(id: "wide", title: "更广全景", takes: [
            "退后几步，换更广的镜头，把这个场景周围更多的环境收进来，交代整个空间的格局。",
            "超广角的大全景，强调空间的纵深和开阔感，原图的内容在画面中部。",
            "站得更远、略微抬高一点的全景，像给这个地方拍的一张定场照。",
        ]),
        Variation(id: "detail", title: "细节特写", takes: [
            "走近，拍这个场景里最有质感的一处细节（材质、物件，或者光影落下的地方），浅景深，背景仍然是这个场景。",
            "换另一处细节：选一件能代表这个地方的物品或一个角落做近景，画面简洁。",
            "一张静物式的局部：几样原本就在场景里的东西和它们的光影，构图讲究。",
        ]),
        Variation(id: "angle", title: "换个机位", takes: [
            "机位往一侧移动，从斜侧方大约 45 度的角度拍同一个场景，透视和前后景的关系随之变化。",
            "走到场景的另一侧，朝相反的方向拍回来，要能看出还是这个地方。",
            "换一个更靠近场景中心的站位，用标准镜头拍出人眼平视的自然视角。",
        ]),
        Variation(id: "low", title: "低机位", takes: [
            "把相机放到接近地面的高度仰拍，前景的地面或物体被放大，线条向上汇聚。",
            "低机位贴近一件前景物体，越过它看向整个场景。",
        ]),
        Variation(id: "high", title: "高处俯拍", takes: [
            "从高处俯拍，像站在楼上往下看，交代场景的布局。",
            "接近正上方的俯视，强调平面上的几何关系。",
        ]),
        Variation(id: "symmetric", title: "对称正面", takes: [
            "正对场景的主立面或主轴线，水平线和垂直线拉正，严格的对称居中构图。",
        ]),
        Variation(id: "frame", title: "前景框景", takes: [
            "透过场景里已有的前景（门框、窗框、栏杆、枝叶等）去拍，前景虚化成画框，把视线引向场景深处。",
        ]),
    ]

    /// 29 位摄影师各自处理光线、影调、色彩和构图的方式（只写画面，不写人）。key 和摄影棚目录里的 id 一致。
    static let photographerStyle: [String: String] = [
        "newton": "赫尔穆特·牛顿（Helmut Newton）：高反差黑白，硬光，深重锐利的阴影，戏剧化的明暗，冷峻、光洁而有张力。输出黑白。",
        "avedon": "理查德·阿维顿（Richard Avedon）：极简、直接、锐利的黑白，均匀而毫不留情的光线交代每一处细节，画面干净克制，没有多余的东西分散注意力。输出黑白。",
        "lindbergh": "彼得·林德伯格（Peter Lindbergh）：不加修饰的黑白，柔和的自然光，电影剧照般的情绪，细腻的灰阶和经典的胶片颗粒。输出黑白。",
        "leibovitz": "安妮·莱博维茨（Annie Leibovitz）：有方向感的戏剧性布光，立体的明暗，浓郁沉稳的色彩，绘画般的构图和叙事感。",
        "klein": "史蒂文·克莱因（Steven Klein）：阴郁的电影感，单一光源的硬光和浓重的阴影，深而饱和的暗色调，神秘压抑的气氛。",
        "lachapelle": "大卫·拉切贝尔（David LaChapelle）：超饱和的糖果色，光洁到近乎超现实的质感，强烈的色彩对撞和高光泽；只改色彩和光线，不往场景里加道具。",
        "walker": "蒂姆·沃克（Tim Walker）：梦幻的氛围，柔和的粉彩和暖光，绘画感的色调，带一点超现实的戏剧感，但不改变场景里有什么。",
        "knight": "尼克·奈特（Nick Knight）：实验性的色彩和质感，大胆前卫的构图，鲜明的颜色，带一点动态模糊或数码感的处理。",
        "mertmarcus": "Mert & Marcus：极致光洁的当代高光泽质感，浓烈饱和的色彩和强烈的调色，画面锐利、精致、有抛光感。",
        "testino": "马里奥·特斯蒂诺（Mario Testino）：明亮、温暖的阳光感，金色调，色彩鲜活，轻松而奢华的气氛。",
        "demarchelier": "帕特里克·德马舍利耶（Patrick Demarchelier）：经典优雅，柔美的光线，黑白或淡淡的棕褐色调，含蓄而耐看。",
        "inezvinoodh": "Inez & Vinoodh：干净的当代编辑风，精确克制的构图，简洁直接，色彩纯净，带一点图形化的艺术感。",
        "weber": "布鲁斯·韦伯（Bruce Weber）：阳光充足的美式怀旧，黑白或暖棕褐色调，黄金时刻的暖意，自然而经典。",
        "teller": "于尔根·泰勒（Juergen Teller）：机顶闪光灯直闪的粗粝快照感，硬光和生硬的阴影，不修饰，像随手一拍那样真实。",
        "ninagawa": "蜷川实花：极高饱和的红、粉、蓝，浓烈艳丽，梦幻而强烈的色彩对撞，光洁的日式波普质感；场景里原有的花草和彩色物体格外浓艳，但不要凭空加花和金鱼。",
        "okuyama": "奥山由之：柔和的自然光，35mm 胶片的细颗粒，低饱和的暖粉彩色，带一点怀旧的日记感和夏日午后的静止感。",
        "hamada": "滨田英明：通透柔和的自然光，低反差，略微过曝的空气感，淡淡的青绿和暖色，安静的日常诗意。",
        "kawauchi": "川内伦子：明亮柔和的漫射光，浅淡的粉彩色，轻微过曝的梦幻感，对细小事物安静的凝视。",
        "moriyama": "森山大道：高反差黑白，粗颗粒，暗部深重、高光烧白，带一点晃动和失焦的粗粝感。输出黑白。",
        "uedashoji": "植田正治：超现实的舞台感，几何化的极简构图，大片留白和清晰的地平线，黑白或淡雅的色调，诗意的孤寂。",
        "araki": "荒木经惟（《感伤之旅》那一面）：私摄影式的日常，暖调的彩色胶片颗粒，偏红偏橙，柔和的阴天光，带一点感伤。",
        "uedayoshihiko": "上田义彦：安静克制的侧面光，柔和的暗部，低饱和，留白，沉静的质感。",
        "wongdoyle": "王家卫 × 杜可风：浓郁的宝石色调，青绿和橙红的对撞，钨丝灯般的暖光，暗部偏青，带一点慢门拖影的情绪和 35mm 胶片颗粒；不要凭空加霓虹招牌。",
        "shya": "夏永康（Wing Shya）：电影定格般的画面，褪色的柔和粉彩，轻微的失焦和动态模糊，带划痕的胶片颗粒，边缘有一点漏光。",
        "hou": "侯孝贤：固定机位远观的长镜头感，柔和的自然光或温暖的钨丝灯光，朴素低饱和的大地色，深景深，安静而有分量。",
        "yang": "杨德昌：现代都市的疏离感，利用场景里已有的窗、玻璃和梁柱做几何式取景，冷静的蓝灰色调，古典而克制的构图。",
        "tsai": "蔡明亮：极简的固定长镜头感，空旷冷清的空间，单一方向的低角度光，深沉的寂静。",
        "anglee": "李安：细腻的自然光，冷暖平衡、带绘画感的色彩，古典均衡的构图，含蓄克制的情绪。",
        "kwan": "关锦鹏：像透过纱帘进来的柔和窗光，温暖的琥珀色调，浓郁的暗部，老电影般细腻含蓄的气氛；不改变场景里的陈设。",
    ]

    /// 器材和胶片的成像特点。key 和摄影棚目录里的 id 一致；"自动"不写。
    static let equipmentLook: [String: String] = [
        "portra400": "Kodak Portra 400 彩色负片：柔和偏暖的粉彩色调，低反差，奶油般的高光，细腻可见的颗粒。",
        "portra800": "Kodak Portra 800 彩色负片：比 Portra 400 更浓一点的暖色，暗处颗粒明显，暗部有电影感。",
        "gold200": "Kodak Gold 200 彩色负片：怀旧的金黄暖调，高光偏黄橙，中等饱和，九十年代家庭快照的味道。",
        "ektar100": "Kodak Ektar 100 彩色负片：颗粒极细，颜色非常鲜艳，红和蓝尤其浓，反差高，像风光明信片。",
        "fuji400h": "Fuji Pro 400H 彩色负片：淡淡的青绿色偏，低反差，奶油般的高光，清淡的日系色调。",
        "velvia50": "Fuji Velvia 50 反转片：颜色极其浓艳，红、绿和天空格外饱和，反差高，幻灯片般通透。",
        "cinestill800t": "CineStill 800T 灯光片：青橙色调，亮着的灯周围有一圈红色光晕，暗处有电影胶片的颗粒。",
        "cinestill50d": "CineStill 50D 日光片：细腻的电影感色彩，柔和的暖色日光，高光过渡平滑。",
        "ilfordhp5": "Ilford HP5 黑白胶片：丰富的灰阶，自然的颗粒，深沉的黑和明亮的中间调。输出黑白。",
        "ilforddelta3200": "Ilford Delta 3200 高感黑白胶片：粗大的颗粒，粗粝的高反差，天鹅绒般的黑。输出黑白。",
        "trix400": "Kodak Tri-X 400 黑白胶片：经典的纪实黑白，自然的颗粒，均衡的影调。输出黑白。",
        "canon5d": "佳能 5D 配 L 镜头：偏暖讨喜的色彩，柔滑的焦外。",
        "sonya7": "索尼 A7 配 GM 镜头：干净中性的色彩，宽容度大，解析力高，现代数码的锐利。",
        "fujifilmx": "富士 X 系列的 Classic Chrome 胶片模拟：低饱和，略偏青，复古杂志般克制的颜色。",
        "leicam": "徕卡 M 配 Summilux 镜头：微反差高、立体感强，颜色克制，暗部浓郁，过渡有绘画感。",
        "hasselblad": "哈苏中画幅数码：中画幅的空间感，过渡和细节极其细腻，色彩自然高级。",
        "phaseone": "飞思中画幅数码：超高解析力，细节极致，商业大片那种干净的质感。",
        "polaroidsx70": "宝丽来 SX-70：奶白的粉彩色偏，柔和的暗角，褪色的七十年代即时成像感（不要加白边）。",
        "polaroid600": "宝丽来 600：饱和怀旧的八九十年代即时成像色调，略有颗粒和色偏（不要加白边）。",
        "instaxmini": "富士 Instax Mini 拍立得：略微发白的淡彩色，清淡的即时成像感（不要加白边）。",
        "instaxwide": "富士 Instax Wide 拍立得：淡雅的低饱和色调，怀旧的即时成像感（不要加白边）。",
        "disposable": "一次性胶卷相机：机内闪光灯的硬光和生硬的阴影，粗颗粒，颜色略淡偏冷，镜头略软。",
        "lomo": "LOMO / Holga 玩具相机：浓重的暗角，夸张的色偏，画面边缘有漏光，粗粝的实验感。",
        "iphone": "iPhone Pro：计算摄影的干净锐利，轻微的 HDR 感，颜色鲜艳准确。",
        "vhs": "九十年代 VHS 录像带里的一帧：横向扫描线，低分辨率的柔软质感，青品红色偏。",
    ]

    struct Planned {
        let prompt: String
        /// 这一张是哪种拍法（显示在缩略图上）。
        let variation: String
        let photographer: String
    }

    /// 排出一组照片。photographers / equipment 是目录里的列表（含"随机""自动"），用来取名字和抽随机。
    /// explore = 试拍：6 张都是原构图，每张换一位摄影师，方便比较风格。
    static func plan(photographer: String, equipment: String, photographers: [StudioModel.Option], equipmentList: [StudioModel.Option],
                     picks: [SceneLook.Group: String], variations chosen: Set<String>, count: Int, explore: Bool,
                     orientation: String) -> [Planned] {
        let real = photographers.filter { $0.id != "random" && $0.id != "none" }
        guard !real.isEmpty else { return [] }
        func title(_ id: String) -> String { real.first { $0.id == id }?.title ?? id }

        var gear = equipment
        if gear == "random" { gear = equipmentList.map(\.id).filter { equipmentLook[$0] != nil }.randomElement() ?? "auto" }
        let gearText = equipmentLook[gear]

        // 每张用哪种拍法：选中的拍法轮着来，同一种拍法第二轮换一个说法；原构图只拍一次。
        var selected = variations.filter { chosen.contains($0.id) }
        if selected.isEmpty || explore { selected = Array(variations.prefix(1)) }
        var pool: [(Variation, String)] = []
        for round in 0..<(selected.map(\.takes.count).max() ?? 1) {
            for variation in selected where round < variation.takes.count {
                pool.append((variation, variation.takes[round]))
            }
        }
        let total = explore ? min(6, real.count) : max(1, count)
        // 每张用哪位摄影师：试拍每张换一位；否则整组同一位（选了"随机"就抽一位）。
        let fixed = photographer == "none" ? "" : (photographer == "random" || !real.contains { $0.id == photographer } ? real.randomElement()!.id : photographer)
        let people = explore ? real.shuffled().prefix(total).map(\.id) : Array(repeating: fixed, count: total)

        return (0..<total).map { index in
            let (variation, take) = pool[index % pool.count]
            let who = people[index]
            var parts = [
                "这是一张场景照片，画面里的这个场景本身就是拍摄对象。请摄影师到同一个地方把它重新拍一张，输出一张真实的摄影作品。",
                SceneLook.keepScene,
                SceneLook.noPerson,
            ]
            if !who.isEmpty {
                let style = photographerStyle[who] ?? "\(title(who))：按这位摄影师的典型风格处理光线、影调和色彩。"
                parts.append("【摄影风格】\(style)只借用这位摄影师处理光线、影调、色彩、质感和构图的方式；其作品里常见的人物、模特和道具一概不要带进来。")
            } else {
                parts.append("【摄影风格】保持原照片的色调和风格，只是拍得更专业、更好看。")
            }
            if let gearText { parts.append("【器材】\(gearText)") }
            parts.append(SceneLook.atmosphere(picks))
            var shot = "【这一张】第 \(index + 1) / \(total) 张 · \(variation.title)：\(take)"
            if variation.id != "keep" {
                shot += "镜头移动后新露出来的部分，按原场景的风格、材质和陈设合理延伸，不要变成另一个地方。"
            }
            parts.append(shot)
            if explore {
                parts.append("【整组】这是试拍：每张换一位摄影师，场景和构图都不变，只有摄影风格不同。")
            } else if total > 1 {
                parts.append("【整组】这一组照片拍的是同一个地方、同一个时刻，出自同一位摄影师，只有机位和景别不同。")
            }
            parts.append("【输出】\(orientation)画面。真实的照片质感，不要插画或 3D 渲染的感觉；画面里不要出现任何文字、水印和边框。")
            return Planned(prompt: parts.joined(separator: "\n"), variation: variation.title, photographer: who.isEmpty ? "" : title(who))
        }
    }
}
