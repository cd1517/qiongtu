package com.taoo.xiutu.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.godcover.SceneLook
import com.taoo.xiutu.ui.Chip
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.press
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap

/** 摄影棚各个选项的小图标、短名字和配色。 */
object StudioIcons {
    // iOS 系统色
    val PURPLE = Color(0.69f, 0.32f, 0.87f)
    val ORANGE = Color(1f, 0.58f, 0f)
    val YELLOW = Color(1f, 0.8f, 0f)
    val BLUE = Color(0f, 0.48f, 1f)
    val INDIGO = Color(0.35f, 0.34f, 0.84f)
    val CYAN = Color(0.2f, 0.68f, 0.9f)
    val GRAY = Color(0.56f, 0.56f, 0.58f)
    val TEAL = Color(0.19f, 0.69f, 0.78f)
    val GREEN = Color(0.2f, 0.78f, 0.35f)
    val PINK = Color(1f, 0.18f, 0.33f)
    val WHITE = Color.White

    private val NIGHT = INDIGO
    private val WATER = TEAL
    private val WARM = ORANGE
    private val GOLD = YELLOW
    private val ART = PURPLE
    private val CITY = BLUE
    private val CALM = GRAY

    /** 去掉标题开头的 emoji 和括号里的补充，按钮上只留名字。 */
    fun plain(title: String): String {
        var i = 0
        while (i < title.length) {
            val cp = title.codePointAt(i)
            if (!isDecoration(cp) && cp != ' '.code) break
            i += Character.charCount(cp)
        }
        var s = title.substring(i)
        val cut = s.indexOfFirst { it == '（' || it == '(' }
        if (cut > 0) s = s.substring(0, cut)
        return s.trim()
    }

    private fun isDecoration(cp: Int): Boolean {
        if (cp == 0xFE0F || cp == 0x200D) return true
        if (cp in 0x1F1E6..0x1F1FF) return true
        if (cp <= 0x2000) return false
        val type = Character.getType(cp)
        return type == Character.OTHER_SYMBOL.toInt() || type == Character.NON_SPACING_MARK.toInt() || cp >= 0x1F000
    }

    // 摄影师、器材

    val photographerNames = mapOf(
        "none" to "不指定", "random" to "随机大师",
        "newton" to "牛顿", "avedon" to "阿维顿", "lindbergh" to "林德伯格", "leibovitz" to "莱博维茨",
        "klein" to "克莱因", "lachapelle" to "拉切贝尔", "walker" to "蒂姆·沃克", "knight" to "尼克·奈特",
        "mertmarcus" to "默特马库斯", "testino" to "特斯蒂诺", "demarchelier" to "德马舍利耶", "inezvinoodh" to "伊内兹",
        "weber" to "韦伯", "teller" to "泰勒", "ninagawa" to "蜷川实花", "okuyama" to "奥山由之",
        "hamada" to "滨田英明", "kawauchi" to "川内伦子", "moriyama" to "森山大道", "uedashoji" to "植田正治",
        "araki" to "荒木经惟", "uedayoshihiko" to "上田义彦", "wongdoyle" to "王家卫", "shya" to "夏永康",
        "hou" to "侯孝贤", "yang" to "杨德昌", "tsai" to "蔡明亮", "anglee" to "李安", "kwan" to "关锦鹏",
    )

    fun photographerName(option: StudioModel.Option) = photographerNames[option.id] ?: plain(option.title)

    val equipmentNames = mapOf(
        "auto" to "自动", "random" to "随机器材",
        "portra400" to "Portra 400", "portra800" to "Portra 800", "gold200" to "Gold 200", "ektar100" to "Ektar 100",
        "fuji400h" to "富士 400H", "velvia50" to "Velvia 50", "cinestill800t" to "800T", "cinestill50d" to "50D",
        "ilfordhp5" to "HP5", "ilforddelta3200" to "Delta 3200", "trix400" to "Tri-X 400",
        "canon5d" to "佳能 5D", "sonya7" to "索尼 A7", "fujifilmx" to "富士 X", "leicam" to "徕卡 M",
        "hasselblad" to "哈苏", "phaseone" to "飞思", "polaroidsx70" to "SX-70", "polaroid600" to "宝丽来 600",
        "instaxmini" to "Instax Mini", "instaxwide" to "Instax Wide", "disposable" to "一次性相机",
        "lomo" to "LOMO", "iphone" to "iPhone", "vhs" to "VHS",
    )

    fun equipmentName(option: StudioModel.Option) = equipmentNames[option.id] ?: plain(option.title)

    // 时段、氛围

    /** 时段（拍人物）和氛围（拍场景）共用：id 对应图标和底色。 */
    fun lightSymbol(id: String): Pair<String, Color> = when (id) {
        "auto" -> "wand.and.stars" to PURPLE
        "random" -> "dice.fill" to PURPLE
        "dawn" -> "sunrise.fill" to ORANGE
        "morning" -> "sun.min.fill" to YELLOW
        "noon" -> "sun.max.fill" to YELLOW
        "afternoon" -> "sun.haze.fill" to ORANGE
        "goldenhour", "golden" -> "sun.horizon.fill" to ORANGE
        "bluehour", "blue" -> "moon.haze.fill" to BLUE
        "night" -> "moon.stars.fill" to INDIGO
        "latenight" -> "moon.zzz.fill" to INDIGO
        "sunny" -> "sun.max.fill" to YELLOW
        "cloudy" -> "cloud.sun.fill" to CYAN
        "overcast" -> "cloud.fill" to GRAY
        "rain", "rainy" -> "cloud.rain.fill" to BLUE
        "afterrain" -> "cloud.sun.rain.fill" to TEAL
        "snow", "snowy" -> "cloud.snow.fill" to CYAN
        "fog", "foggy" -> "cloud.fog.fill" to GRAY
        "spring" -> "camera.macro" to PINK
        "summer" -> "sun.max.fill" to GREEN
        "autumn" -> "leaf.fill" to ORANGE
        "winter" -> "snowflake" to CYAN
        "soft" -> "sun.haze.fill" to GRAY
        "hard" -> "sun.max.fill" to YELLOW
        "side" -> "circle.lefthalf.filled" to ORANGE
        "back" -> "sun.and.horizon.fill" to ORANGE
        "window" -> "window.casement" to TEAL
        "beam" -> "rays" to YELLOW
        "neon" -> "lightbulb.led.fill" to PINK
        "lowkey" -> "moon.fill" to INDIGO
        "highkey" -> "sun.min.fill" to WHITE
        else -> "circle.dashed" to GRAY
    }

    fun moodGroupSymbol(group: SceneLook.Group): String = when (group) {
        SceneLook.Group.TIME -> "clock.fill"
        SceneLook.Group.WEATHER -> "cloud.sun.fill"
        SceneLook.Group.SEASON -> "leaf.fill"
        SceneLook.Group.LIGHT -> "lightbulb.fill"
    }

    // 人物

    fun personGroupSymbol(raw: String): String = when (raw) {
        "gender" -> "person.2.fill"
        "persona" -> "theatermasks.fill"
        "age" -> "hourglass"
        "beauty" -> "sparkles"
        "retouch" -> "wand.and.stars"
        "hair" -> "paintbrush.fill"
        else -> "circle"
    }

    val genderSymbols = mapOf(
        "auto" to "wand.and.stars", "female" to "figure.stand.dress", "male" to "figure.stand",
        "androgynous" to "circle.lefthalf.filled",
    )

    val ageSymbols = mapOf("original" to "person.crop.circle")

    val beautySymbols = mapOf(
        "none" to "circle.dashed", "subtle" to "sparkle", "medium" to "sparkles", "strong" to "wand.and.stars",
    )

    val retouchSymbols = mapOf(
        "skin" to "drop.fill", "eyes" to "eye.fill", "nose" to "nose.fill", "lips" to "mouth.fill",
        "face" to "face.smiling", "hair" to "comb.fill", "neck" to "person.bust", "body" to "figure.stand",
    )

    val personaSymbols = mapOf(
        "random" to "dice.fill", "seiso" to "drop.fill", "kawaii" to "heart.fill", "gyaru" to "sparkles",
        "jirai" to "bolt.heart.fill", "onee" to "eyeglasses", "nightclub" to "wineglass.fill", "queen" to "crown.fill",
        "cabaret" to "sparkles", "jk" to "backpack.fill", "business" to "briefcase.fill", "scholar" to "book.fill",
        "athlete" to "figure.run", "fitness" to "dumbbell.fill", "rocker" to "guitars.fill", "gentleman" to "mustache.fill",
        "kpopboy" to "music.mic", "cityboy" to "building.2.fill", "bachelor" to "house.fill", "artist" to "paintpalette.fill",
        "natural" to "leaf.fill", "half" to "globe.europe.africa.fill", "coser" to "theatermasks.fill", "alt" to "bolt.fill",
        "hkretro" to "film.fill", "kclean" to "drop", "genki" to "sun.max.fill", "lolita" to "suit.heart.fill",
        "ryousan" to "heart.circle.fill",
    )

    // 摄影分组、品牌

    fun cameraGroupSymbol(raw: String): String = when (raw) {
        "photographer" -> "camera.fill"
        "equipment" -> "film.fill"
        "time" -> "sun.max.fill"
        "brands" -> "tag.fill"
        else -> "circle"
    }

    fun brandCategorySymbol(title: String): String {
        val rules = listOf(
            "包" to "handbag.fill", "表" to "watch.analog", "珠宝" to "diamond.fill", "首饰" to "diamond.fill",
            "车" to "car.side.fill", "服" to "hanger", "生活" to "wineglass.fill",
        )
        return rules.firstOrNull { title.contains(it.first) }?.second ?: "tag.fill"
    }

    /** 单品自己的图标；没有合适的就用所在大类的图标。 */
    fun brandItemSymbol(option: StudioModel.Option, category: String): String {
        val rules = listOf(
            "香槟" to "wineglass.fill", "威士忌" to "wineglass.fill", "XO" to "wineglass.fill", "路易十三" to "wineglass.fill",
            "耳机" to "headphones", "AirPods" to "headphones", "iPhone" to "iphone", "相机" to "camera.fill",
            "行李箱" to "suitcase.rolling.fill", "鞋" to "shoe.fill", "高跟" to "shoe.fill", "丝巾" to "hanger",
        )
        return rules.firstOrNull { option.title.contains(it.first) }?.second ?: brandCategorySymbol(category)
    }

    /** 拍场景的拍法。 */
    val variationSymbols = mapOf(
        "keep" to "photo", "wide" to "arrow.up.left.and.arrow.down.right", "detail" to "plus.magnifyingglass",
        "angle" to "arrow.triangle.2.circlepath.camera", "low" to "arrow.up.to.line", "high" to "arrow.down.to.line",
        "symmetric" to "square.split.2x1", "frame" to "rectangle.inset.filled",
    )

    // 场景

    /** 按关键词给场景大类挑图标。 */
    fun categorySymbol(title: String): String {
        val rules = listOf(
            "东京" to "building.2.fill", "关西" to "storefront.fill", "京都" to "building.columns.fill",
            "氛围" to "moon.stars.fill", "传统" to "fireworks", "韩国" to "bag.fill", "中国" to "mountain.2.fill",
            "美国" to "star.fill", "英国" to "crown.fill", "酒店" to "bed.double.fill", "校园" to "graduationcap.fill",
            "海岛" to "beach.umbrella.fill", "海边" to "beach.umbrella.fill", "咖啡" to "cup.and.saucer.fill",
            "夜" to "moon.stars.fill", "街" to "building.2.fill", "都市" to "globe.europe.africa.fill",
        )
        return rules.firstOrNull { title.contains(it.first) }?.second ?: "mappin.and.ellipse"
    }

    private class R(val key: String, val symbol: String, val tint: Color)

    /** 按关键词给场景挑图标和底色，前面的规则优先；都对不上就用大类的图标。 */
    private val sceneRules = listOf(
        // 地名里带"海""江""湾"之类字的，先单独认出来。
        R("外滩", "building.2.fill", CITY), R("田子坊", "house.fill", WARM), R("新天地", "building.2.fill", CITY),
        R("江南", "building.2.fill", CITY), R("古城", "house.fill", WARM), R("铜锣湾", "bag.fill", PINK),
        R("中环", "building.2.fill", CITY), R("海神庙", "building.columns.fill", GOLD),
        R("贩卖机", "refrigerator.fill", NIGHT), R("居酒屋", "fork.knife", WARM), R("弹珠", "circle.grid.3x3.fill", ART),
        R("便利店", "storefront.fill", NIGHT), R("练歌房", "music.mic", ART), R("KTV", "music.mic", ART),
        R("电车", "tram.fill", CITY), R("地铁", "tram.fill", CITY), R("车站", "train.side.front.car", CITY),
        R("樱", "camera.macro", PINK), R("红叶", "leaf.fill", WARM), R("雪", "snowflake", WATER),
        R("烟花", "fireworks", NIGHT), R("祭", "fireworks", WARM), R("盆踊", "figure.socialdance", WARM),
        R("和服", "figure.stand.dress", PINK), R("浴衣", "figure.stand.dress", PINK),
        R("舞妓", "figure.stand.dress", PINK), R("温泉", "bathtub.fill", WATER),
        R("榻榻米", "house.lodge.fill", CALM), R("茶道", "cup.and.saucer.fill", GREEN),
        R("夜", "moon.stars.fill", NIGHT), R("歌舞伎町", "moon.stars.fill", NIGHT),
        R("咖啡", "cup.and.saucer.fill", WARM), R("早餐", "cup.and.saucer.fill", WARM),
        R("酒吧", "wineglass.fill", ART), R("泳池", "figure.pool.swim", WATER), R("浴室", "bathtub.fill", WATER),
        R("电梯", "arrow.up.arrow.down.square.fill", CALM), R("大堂", "sofa.fill", GOLD), R("阳台", "sun.horizon.fill", WARM),
        R("套房", "bed.double.fill", GOLD), R("宿舍", "bed.double.fill", CALM),
        R("教室", "studentdesk", CALM), R("走廊", "door.left.hand.open", CALM), R("保健室", "cross.case.fill", CALM),
        R("操场", "figure.run", GREEN), R("天台", "building.fill", CITY), R("楼梯", "stairs", CALM),
        R("办公室", "briefcase.fill", CALM), R("图书馆", "books.vertical.fill", WARM), R("食堂", "fork.knife", WARM),
        R("校门", "door.left.hand.open", CALM), R("小树林", "tree.fill", GREEN), R("大学", "graduationcap.fill", CITY),
        R("剑桥", "graduationcap.fill", CITY), R("牛津", "graduationcap.fill", CITY),
        R("珊瑚", "fish.fill", WATER), R("环礁", "fish.fill", WATER), R("水上屋", "water.waves", WATER),
        R("梯田", "mountain.2.fill", GREEN), R("七色土", "mountain.2.fill", WARM),
        R("海", "beach.umbrella.fill", WATER), R("岛", "beach.umbrella.fill", WATER), R("湾", "beach.umbrella.fill", WATER),
        R("鹿", "pawprint.fill", GREEN), R("熊猫", "pawprint.fill", GREEN), R("竹林", "tree.fill", GREEN),
        R("公园", "tree.fill", GREEN), R("园林", "tree.fill", GREEN), R("乡村", "house.lodge.fill", GREEN),
        R("牧场", "figure.equestrian.sports", WARM), R("巨石阵", "mountain.2.fill", CALM),
        R("天文台", "binoculars.fill", NIGHT), R("拉斯维加斯", "dice.fill", ART), R("星光大道", "star.fill", GOLD),
        R("河", "water.waves", WATER), R("江", "water.waves", WATER), R("西湖", "water.waves", WATER), R("港", "ferry.fill", WATER),
        R("大桥", "road.lanes", CITY), R("塔桥", "road.lanes", CITY), R("金门", "road.lanes", CITY),
        R("寺", "building.columns.fill", GOLD), R("神社", "building.columns.fill", GOLD), R("鸟居", "building.columns.fill", GOLD),
        R("宫", "building.columns.fill", GOLD), R("城", "building.columns.fill", GOLD), R("钟楼", "building.columns.fill", GOLD),
        R("清真寺", "building.columns.fill", GOLD), R("高迪", "building.columns.fill", GOLD), R("红场", "building.columns.fill", GOLD),
        R("雷门", "building.columns.fill", GOLD), R("大本钟", "building.columns.fill", GOLD),
        R("壁画", "paintpalette.fill", ART), R("艺术", "paintpalette.fill", ART), R("文化村", "paintpalette.fill", ART),
        R("文艺", "paintpalette.fill", ART), R("彩色屋", "house.fill", PINK), R("蓝顶", "house.fill", CITY),
        R("韩屋", "house.fill", WARM), R("胡同", "house.fill", WARM), R("古", "house.fill", WARM), R("老城", "house.fill", WARM),
        R("石板路", "house.fill", WARM), R("旧街", "house.fill", WARM), R("巷", "house.fill", WARM), R("田子坊", "house.fill", WARM),
        R("九曲", "road.lanes", PINK), R("市场", "basket.fill", WARM), R("商业", "bag.fill", PINK), R("精品", "bag.fill", PINK),
        R("时尚", "bag.fill", PINK), R("明洞", "bag.fill", PINK), R("中环", "building.2.fill", CITY),
        R("塔", "building.2.fill", CITY), R("哈利法", "building.2.fill", CITY), R("歌剧院", "building.columns.fill", GOLD),
        R("云门", "circle.fill", CITY), R("广场", "building.2.fill", CITY), R("十字路口", "figure.walk", CITY),
        R("街", "building.2.fill", CITY), R("都市", "building.2.fill", CITY), R("外滩", "building.2.fill", CITY),
        R("新天地", "building.2.fill", CITY), R("洪崖洞", "building.2.fill", NIGHT),
    )

    /** 太长的场景名在格子上放不下，换个短的叫法。 */
    private val shortSceneNames = mapOf(
        "伊斯坦布尔蓝色清真寺" to "蓝色清真寺", "巴塞罗那高迪建筑" to "高迪建筑", "EDITION酒店套房" to "EDITION 套房",
        "北京798艺术区" to "798 艺术区", "纽约SOHO街拍" to "SOHO 街拍",
    )

    fun sceneName(title: String) = shortSceneNames[title] ?: title

    fun sceneSymbol(title: String, category: String): Pair<String, Color> {
        sceneRules.firstOrNull { title.contains(it.key) }?.let { return it.symbol to it.tint }
        return categorySymbol(category) to CALM
    }

    /** 发色色块的颜色；彩虹挑染是多种颜色。 */
    fun swatch(value: String?): List<Color> {
        if (value == null) return listOf(GRAY)
        val colors = value.split("#").drop(1).mapNotNull { hex(it.take(6)) }
        return colors.ifEmpty { listOf(GRAY) }
    }

    private fun hex(h: String): Color? {
        if (h.length != 6) return null
        val v = h.toIntOrNull(16) ?: return null
        return Color(((v shr 16) and 0xff) / 255f, ((v shr 8) and 0xff) / 255f, (v and 0xff) / 255f)
    }
}

/** 摄影棚的选项格子：有图就放图，没图就是带底色的图标；选中时描一圈强调色。 */
@Composable
fun PickTile(
    title: String,
    selected: Boolean,
    image: Bitmap? = null,
    symbol: String? = null,
    tint: Color = StudioIcons.GRAY,
    width: Int = StudioLooks.TILE_WIDTH,
    height: Int = StudioLooks.TILE_HEIGHT,
    onClick: () -> Unit,
) {
    Column(Modifier.press(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        val shape = RoundedCornerShape(10.dp)
        Box(
            Modifier.size(width.dp, height.dp).clip(shape).background(Color(0.16f, 0.16f, 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                val ib = remember(image) { image.asImageBitmap() }
                Image(ib, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                // 深灰底，图标后面透一点它自己的颜色
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(tint.copy(alpha = 0.32f), tint.copy(alpha = 0f)), radius = with(LocalDensity.current) { 46.dp.toPx() })))
                if (symbol != null) Icon(sym(symbol), null, tint = Color.White, modifier = Modifier.size(23.dp))
            }
            Box(Modifier.fillMaxSize().border(2.5.dp, if (selected) Theme.accent else Color.Transparent, shape))
        }
        Text(
            title, fontSize = 11.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Theme.accent else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.width((width + 4).dp),
        )
    }
}

/** 一排横向滚动的格子。 */
@Composable
fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top, content = content,
    )
}

/** 一排横向滚动的小标签，每个前面带图标（没有合适图标的就只有文字）。 */
@Composable
fun <T> SymbolChipRow(items: List<T>, title: (T) -> String, symbol: (T) -> String?, isSelected: (T) -> Boolean, onTap: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in items) Chip(title(item), isSelected(item), symbol = symbol(item)) { onTap(item) }
    }
}

/** 发色：小标签前面是一个色块。 */
@Composable
fun SwatchChip(title: String, swatch: List<Color>, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (selected) Color.White else Theme.chip).tap(onClick = onClick)
            .padding(start = 9.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val brush = if (swatch.size > 1) Brush.sweepGradient(swatch + swatch[0]) else Brush.linearGradient(listOf(swatch[0], swatch[0]))
        Box(Modifier.size(16.dp).clip(CircleShape).background(brush).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape))
        Text(
            title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.Black else Color.White, maxLines = 1,
        )
    }
}
