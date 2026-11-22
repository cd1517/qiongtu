import Foundation

/// AI 修图里的"创意玩法"：把照片画成各种漫画 / 卡通 / 艺术风格，或者加个分身、做点好玩的、换个氛围。
/// 整张图交给 AI 重画。
enum CreativeKind: String, CaseIterable, Identifiable {
    case comic, cartoon, art, twin, fun, mood

    var id: String { rawValue }

    var title: String {
        switch self {
        case .comic: return "漫画"
        case .cartoon: return "卡通"
        case .art: return "艺术"
        case .twin: return "分身"
        case .fun: return "趣味"
        case .mood: return "氛围"
        }
    }

    var symbol: String {
        switch self {
        case .comic: return "book.pages"
        case .cartoon: return "face.smiling"
        case .art: return "paintbrush"
        case .twin: return "person.2"
        case .fun: return "party.popper"
        case .mood: return "leaf"
        }
    }

    /// 画成别的画风时都要守住的：还是这个人、这个画面。
    private static let keep = "人物要一眼就认得出是同一个人：脸型、五官特征、发型发色、服装和姿势都保留；构图、画面比例和背景的布局不变。"
    /// 在真实照片上加东西时都要守住的。
    private static let real = "原照片里的人物长相、表情、服装、姿势和背景都保持不变，仍然是一张真实的照片，新加的东西光线和透视要和原图一致、自然融入。"

    private static func style(_ title: String, _ symbol: String, _ body: String) -> AIPreset {
        AIPreset(title: title, symbol: symbol, prompt: "把整张照片重画成\(body)。" + keep)
    }

    private static func add(_ title: String, _ symbol: String, _ body: String) -> AIPreset {
        AIPreset(title: title, symbol: symbol, prompt: body + real)
    }

    var presets: [AIPreset] {
        switch self {
        case .comic:
            return [
                Self.style("日漫", "sparkles", "日本动画风格的插画：干净利落的线稿，赛璐璐平涂上色，明亮通透的颜色，眼睛有高光，背景也画成动画美术的样子"),
                Self.style("韩漫", "sparkles", "韩国条漫风格的插画：细腻的线条，柔和的渐变上色，皮肤白皙通透，眼睛水润有光泽，整体精致唯美"),
                Self.style("少女漫画", "heart", "日本抒情风格的黑白漫画：细腻的钢笔线条和网点，大眼睛闪着星光，背景飘着花瓣和闪光"),
                Self.style("黑白漫画", "book.pages", "黑白日式漫画分镜里的一格：钢笔线稿，网点纸表现明暗，速度线和强烈的黑白对比"),
                Self.style("美漫", "bolt", "美式漫画书风格：粗重的黑色墨线，强烈的明暗，饱和的颜色，网点印刷质感"),
                Self.style("温暖动画", "sun.max", "手绘温暖风格的动画电影画面：柔和的水彩感背景，清新的蓝天白云和绿色，人物线条简洁温柔，阳光温暖"),
                Self.style("城市流行", "building.2", "80 年代日本城市流行音乐唱片封面式的插画：平涂色块，霓虹和夜色，复古时髦，色彩是粉、紫、青"),
                Self.style("厚涂插画", "paintpalette", "精致的厚涂数字插画：有笔触感的绘画质感，光影丰富，色彩浓郁"),
                AIPreset(title: "三格漫画", symbol: "rectangle.split.3x1", prompt: "把这张照片做成一张竖排三格的漫画：第一格是这个人的铅笔草稿，第二格是上了线稿的样子，第三格是蜡笔上完色、加了小花小星星装饰的完成版；三格都是照片里的同一个人、同一个姿势，白色背景，格子之间有黑色边框。"),
            ]
        case .cartoon:
            return [
                Self.style("Q 版", "face.smiling", "可爱的 Q 版卡通：大头小身子，圆圆的大眼睛，脸颊红红的，线条圆润，颜色明快"),
                AIPreset(title: "像素小人", symbol: "square.grid.3x3", prompt: "把照片里的人画成 16 位游戏机风格的像素画小人：全身，大头小身子，像素清晰可见、有限的调色板，衣服和发型发色跟原照片一样，背景也画成像素风的游戏场景。"),
                Self.style("3D 卡通", "cube", "3D 动画电影里的卡通角色：圆润的造型，柔和的材质和光照，大眼睛，表情生动，背景也变成 3D 动画场景"),
                Self.style("黏土", "hand.raised", "定格动画里的黏土小人和黏土场景：手捏的黏土质感，能看到指纹和捏痕，圆润可爱"),
                Self.style("毛毡", "circle.hexagongrid", "羊毛毡戳出来的小玩偶和场景：毛茸茸的羊毛纤维质感，温暖可爱"),
                Self.style("蜡笔涂鸦", "pencil.tip", "小朋友用蜡笔在白纸上画的画：歪歪扭扭的线条，蜡笔的笔触和纸纹，颜色涂得不太均匀，天真可爱"),
                Self.style("潦草涂鸦", "scribble", "用马克笔在白纸上随手画的潦草涂鸦：简单的线条，几笔颜色，很随意很好玩"),
                Self.style("治愈绘本", "book", "治愈系儿童绘本插画：水粉质感，柔和温暖的颜色，圆润的造型，安静温柔的气氛"),
                Self.style("简笔画", "pencil", "极简的黑色简笔画：白底，只用几根干净的线条勾出人物和场景的轮廓"),
                Self.style("贴纸风", "seal", "可爱的卡通贴纸：粗白边描边，平涂颜色，像一张印出来的贴纸"),
            ]
        case .art:
            return [
                Self.style("水彩", "drop", "水彩画：颜色在纸上晕开，留白透气，能看到水彩纸的纹理"),
                Self.style("油画", "paintbrush", "古典油画：厚重的笔触和颜料肌理，伦勃朗式的光影，画布纹理"),
                Self.style("印象派", "sun.haze", "印象派油画：短促的彩色笔触，光和空气的颤动感，明亮的色彩"),
                Self.style("素描", "pencil.and.scribble", "铅笔素描：细腻的排线表现明暗，白纸上的石墨质感"),
                Self.style("国风水墨", "mountain.2", "中国水墨画：墨色浓淡变化，宣纸质感，留白，写意的笔法"),
                Self.style("浮世绘", "water.waves", "日本浮世绘木刻版画：平面的色块，清晰的轮廓线，传统配色和纸张质感"),
                Self.style("波普", "circle.grid.2x2", "波普艺术：高饱和的撞色，粗黑轮廓，网点印刷，像丝网印刷海报"),
                Self.style("版画", "square.on.square", "黑白木刻版画：刀刻的线条，强烈的黑白对比"),
                Self.style("彩铅", "pencil.tip.crop.circle", "彩色铅笔画：细细的彩铅笔触层层叠加，纸的颗粒感，颜色柔和"),
            ]
        case .twin:
            return [
                Self.add("插画分身", "person.2", "在人物旁边加一个这个人的可爱卡通插画版本，像一张剪下来贴上去的贴纸（带白色描边），服装、发型和发色跟本人一样，做着相似的动作，大小约到本人的肩膀。"),
                Self.add("漫画拼贴", "person.2", "在人物旁边加一个复古漫画风格的这个人（粗线条、平涂颜色），像剪贴画一样贴在照片上，服装发型和本人一样。"),
                Self.add("像素分身", "square.grid.3x3", "在人物旁边加一个这个人的像素画小人版本，像游戏角色一样站着，服装发型发色和本人一样。"),
                Self.add("盲盒分身", "shippingbox", "在人物旁边放一个这个人的潮玩盲盒公仔：搪胶材质，大头小身子，表面光滑有光泽，服装发型和本人一样，站在旁边的台面或地面上。"),
                Self.add("3D 分身", "cube", "在人物旁边加一个这个人的 3D 卡通形象，像动画电影角色一样，服装发型和本人一样，和本人站在一起。"),
                AIPreset(title: "手办", symbol: "figure.stand", prompt: "把照片里的人做成一个 1/7 比例的写实手办，放在电脑桌上：手办站在透明的圆形底座上，姿势、服装、发型和本人一样，脸要认得出是本人；旁边立着印有这个手办图案的包装盒，后面的电脑屏幕上显示着这个手办的建模过程。像一张真实拍摄的产品照。"),
                AIPreset(title: "吸塑包装", symbol: "rectangle.portrait", prompt: "把照片里的人做成一个装在吸塑包装里的可动人偶玩具：透明塑料罩，彩色纸卡背板，旁边的小格子里放着跟这个人相关的小配件；人偶的脸、发型和服装要认得出是本人。像一张真实拍摄的玩具照片。"),
                AIPreset(title: "表情贴纸", symbol: "face.smiling", prompt: "以照片里的人为原型，画一张 2×2 四格的 Q 版表情贴纸：开心、生气、害羞、惊讶四种表情，大头小身子，每个都有白色描边，白色背景；发型、发色和衣服和本人一样。"),
            ]
        case .fun:
            return [
                Self.add("圣诞帽", "gift", "给人物戴上一顶红白的圣诞帽，帽子大小和角度贴合头部，头发自然压在帽子下面。"),
                Self.add("生日派对", "birthdaycake", "给人物戴上一顶彩色尖尖的派对帽，前景加一个插着蜡烛的生日蛋糕，空中飘着彩色的纸屑和气球。"),
                AIPreset(title: "大头照", symbol: "person.crop.circle", prompt: "把照片做成好玩的大头效果：人物的头放大到原来的两倍左右，身体保持原来大小，脸和表情不变，像一张夸张的趣味照片；背景不变。"),
                Self.add("动物合影", "pawprint", "让一只可爱的小羊驼从画面一侧探进来，凑近镜头和人物一起自拍，毛茸茸的，表情呆萌。"),
                Self.add("迷你萌宠", "cat", "在人物身边和手上加几只很小很小的迷你小猫，只有手掌大，萌萌地趴着、看着镜头。"),
                Self.add("天使翅膀", "bird", "在人物背后加一对洁白蓬松的天使羽毛翅膀，从肩胛骨的位置自然地长出来，羽毛细节真实。"),
                AIPreset(title: "证件照", symbol: "person.text.rectangle", prompt: "把照片里的人做成一张正式的证件照：正面半身，纯蓝色背景，均匀柔和的棚拍光，衣服换成干净的深色西装和白衬衫；脸、五官和发型不变，必须是同一个人。"),
                AIPreset(title: "杂志内页", symbol: "newspaper", prompt: "把这张照片排成一页时尚杂志内页：照片占大半版，旁边配一些英文小标题和正文排版，纸张的印刷质感；照片里的人物和画面不变。"),
                AIPreset(title: "复古海报", symbol: "film", prompt: "把这张照片做成一张 70 年代的复古电影海报：照片保留，颜色变成老海报的印刷色，加上手写风格的英文片名和演职员字样，纸张泛黄有折痕；人物长相和姿势不变。"),
            ]
        case .mood:
            return [
                Self.add("秋日枫叶", "leaf", "在画面里加上金黄和橙红的枫叶，前景有几片虚化的落叶飘过，整体光线变成温暖的秋日午后。"),
                Self.add("月下樱花", "moon.stars", "把场景变成夜晚盛开的樱花树下，月光柔和，粉白的花瓣在空中飘落，前景有虚化的花枝。"),
                Self.add("热带夕阳", "sun.horizon", "把场景变成热带海边的日落时分：橙红色的天空，棕榈树剪影，温暖的逆光照在人物身上。"),
                Self.add("雪夜闪光", "snowflake", "把场景变成下雪的夜晚，用相机闪光灯直闪拍的效果：雪花在闪光下发亮，背景暗下去，人物被闪光照亮。"),
                Self.add("水晶球", "globe", "把人物和场景放进一个圣诞雪景水晶球里：玻璃球的反光和弧形折射，球里飘着雪花，底座是木头的；人物在球里还是原来的样子。"),
                Self.add("夏日公园", "tree", "把场景变成阳光明媚的夏日公园：树荫下斑驳的阳光，绿草地，清新通透的颜色。"),
                Self.add("星空夜", "sparkles", "把天空和背景变成满天繁星的银河夜空，人物被柔和的环境光照亮，整体是深蓝色的夜晚。"),
                Self.add("海边日常", "water.waves", "把场景变成海边：蓝色的海和天，沙滩，海风吹动头发，明亮的阳光。"),
                Self.add("烟花", "sparkle", "在人物背后的夜空中加上绚烂盛开的烟花，烟花的彩色光映在人物身上。"),
                Self.add("梦幻失焦", "circle.dotted", "背景变成大片彩色的焦外光斑，人物清晰，整体气氛柔和明亮。"),
            ]
        }
    }

    static var all: [AIPreset] { allCases.flatMap(\.presets) }
}
