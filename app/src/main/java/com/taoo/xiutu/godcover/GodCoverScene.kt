package com.taoo.xiutu.godcover

/** 封神的场景模式：封面的主角是照片里的场景本身。AI 改造的选项和指令都在本机拼，不许加人。 */
object GodCoverScene {
    /** AI 改造里的几组选项。 */
    enum class Group(val title: String) {
        LOOK("大片感"), LIGHT("光线"), WEATHER("天气"), SEASON("季节"), TIME("时间"), TIDY("清理");

        /** 光线、天气、季节、时间和摄影棚共用同一套。 */
        val shared: SceneLook.Group?
            get() = when (this) {
                LIGHT -> SceneLook.Group.LIGHT
                WEATHER -> SceneLook.Group.WEATHER
                SEASON -> SceneLook.Group.SEASON
                TIME -> SceneLook.Group.TIME
                LOOK, TIDY -> null
            }

        val options: List<SceneLook.Option>
            get() = when (this) {
                LOOK -> looks
                TIDY -> tidy
                else -> shared?.options ?: emptyList()
            }

        val fallback: String get() = if (this == LOOK) "medium" else SceneLook.KEEP
    }

    val looks = listOf(
        SceneLook.Option("none", "原样", "不做风格化处理：光线、颜色和构图都保留原图，只做正常的画质整理。"),
        SceneLook.Option("subtle", "自然", "轻度：像专业摄影师在现场认真拍的一张，曝光和白平衡准确，反差适中，横平竖直，保留现场真实的光线和颜色。"),
        SceneLook.Option("medium", "明显", "中度：处理成专业杂志摄影师拍的大片，光线有设计感和方向感，画面干净讲究，色彩统一，质感高级。"),
        SceneLook.Option("strong", "强烈", "强烈：处理成跨页级别的视觉大片，戏剧化的光线和影调，大胆统一的色彩风格，强烈的视觉冲击力，但仍然是一张真实的照片。"),
    )

    val tidy = listOf(
        SceneLook.Option(SceneLook.KEEP, "保持原样"),
        SceneLook.Option("clean", "清理杂物", "【清理杂物】去掉画面里分散注意力的零碎杂物（垃圾桶、杂乱的线缆、随手放的零碎、突兀的标牌等），并自然地补全背景。只清理零碎：建筑、家具、主要物品和整体布局不变；原图里的人不算杂物，保持原样。"),
    )

    /** 选中的风格是哪一类印刷品，决定指令里怎么称呼它、排版上要有哪些文字。 */
    private fun kind(category: String, name: String): Pair<String, String> = when {
        category.contains("电影") -> "电影海报" to "片名、一句宣传语、演职员表和上映信息"
        category.contains("专辑") -> "音乐专辑封面" to "专辑名和艺人名"
        category.contains("广告") -> "品牌广告海报" to "品牌标志的位置和一句广告语"
        category.contains("真人秀") -> "真人秀宣传海报" to "节目名、一句宣传语和播出信息"
        category.contains("政治") -> "宣传海报" to "大字标语和口号"
        category.contains("艺术") -> "艺术展览海报" to "展览名、展期和场馆信息"
        else -> "杂志封面" to "刊名「$name」、几行封面文案、期号和日期"
    }

    /** 风格介绍里说读者是谁的那几个词用不上（场景里没有人），带年龄暗示的不写进指令。 */
    private fun artDirection(desc: String): String {
        val skip = listOf("学生", "高中", "小学", "女生", "少女", "少年", "儿童", "制服", "JK")
        return desc.split("·")
            .map { it.trim() }
            .filter { part -> part.isNotEmpty() && skip.none { part.contains(it) } }
            .joinToString("、")
    }

    /** values：每组选了哪一项。texts：勾了"让 AI 用我的文字"时的四条文字，否则为空。 */
    fun prompt(
        styleName: String, styleDesc: String, category: String, values: Map<Group, String>,
        noText: Boolean, texts: List<String>, orientation: String,
    ): String {
        val (noun, elements) = kind(category, styleName)
        val direction = artDirection(styleDesc)
        val short = "「$styleName」$noun"
        val named = short + (if (direction.isEmpty()) "" else "（$direction）")
        fun option(group: Group): SceneLook.Option {
            val id = values[group] ?: group.fallback
            return group.options.firstOrNull { it.id == id } ?: group.options[0]
        }

        val parts = mutableListOf(
            "这是一张场景照片，要用它做${named}的主图：这张${noun}的主角就是这个场景本身，不是人。",
            SceneLook.KEEP_SCENE,
            SceneLook.NO_PERSON + "不要出现封面模特或任何人像。",
        )
        val look = option(Group.LOOK)
        parts += "【大片感】${look.prompt}" + (if (look.id == "none") "" else "整体的色调和气质要配得上${short}一贯的视觉风格。")
        val picks = HashMap<SceneLook.Group, String>()
        for (group in Group.entries) {
            group.shared?.let { picks[it] = option(group).id }
        }
        parts += SceneLook.atmosphere(picks)
        val clean = option(Group.TIDY)
        if (clean.prompt.isNotEmpty()) parts += clean.prompt

        if (noText) {
            parts += "【输出】只要一张干净的照片，留给后面排版用：不要刊名、标题、文字、logo、条形码、价格、水印和任何排版元素。真实的照片质感，${orientation}画面，取景范围和原图一致。"
        } else {
            var design = "【封面设计】把它设计成一张完整的${short}，竖幅：加上${elements}，字体和版式是这类${noun}典型的样子。文字排在画面的留白处（天空、墙面、地面等），不要遮住场景里最重要的部分。原图是横幅时，取场景里最有代表性的部分做竖幅构图，可以把上下的天空、天花板或地面自然延伸，不要拉伸变形。除了排版文字，不要加别的图形和人物。"
            val labels = listOf("主标题", "副标题", "附加文字", "附加文字")
            val custom = labels.zip(texts).filter { it.second.isNotEmpty() }.map { "${it.first}「${it.second}」" }
            if (custom.isNotEmpty()) {
                design += "上面的文字必须用下面这几条，一字不差，代替你自己拟的文案：${custom.joinToString("；")}。"
            }
            parts += design
        }
        return parts.joinToString("\n")
    }
}
