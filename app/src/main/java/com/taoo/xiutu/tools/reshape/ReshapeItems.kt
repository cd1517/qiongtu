package com.taoo.xiutu.tools.reshape

import android.graphics.PointF
import com.taoo.xiutu.ai.AIPreset
import com.taoo.xiutu.ml.FaceInfo
import kotlin.math.abs

// MARK: - 项目清单

/** 面部重塑的分类（底部确认栏里的那一排标签）。 */
enum class ReshapeCategory(val title: String) {
    EXPRESSION("表情管理"),
    POSE("3D塑颜"),
    PROPORTION("比例"),
    SHAPE("脸型"),
    EYES("眼睛"),
    LIPS("嘴唇"),
    NOSE("鼻子"),
    BROWS("眉毛");

    /** 这一类里在本机完成的项目（不含 AI 项目）。 */
    val keys: List<ReshapeKey> get() = ReshapeKey.entries.filter { it.category == this }
}

/** 一只眼 / 一条眉可以单独调。左右按画面上的左右算。 */
enum class ReshapeSide(val title: String) {
    BOTH("整体"),
    LEFT("左"),
    RIGHT("右"),
}

/** 在本机完成的重塑项目。除了去眼袋、祛红血丝、白牙是调颜色，其余都是按五官关键点做局部变形。 */
enum class ReshapeKey(
    val category: ReshapeCategory,
    val title: String,
    val symbol: String,
    /** 滑块两头各代表什么（左, 右）。 */
    val ends: Pair<String, String>,
    /** 可以分左右单独调的项目（眼睛和眉毛）。 */
    val paired: Boolean = false,
    /** 调颜色的三项只有 0...100，其余都是 -100...100。 */
    val bipolar: Boolean = true,
) {
    // 3D 塑颜
    HEAD_TILT(ReshapeCategory.POSE, "倾斜", "rotate.right", "左歪" to "右歪"),
    // 比例
    SMALL_HEAD(ReshapeCategory.PROPORTION, "小头", "arrow.down.right.and.arrow.up.left", "大" to "小"),
    FACE_IN_HEAD(ReshapeCategory.PROPORTION, "头包脸", "face.dashed", "脸大" to "脸小"),
    CROWN(ReshapeCategory.PROPORTION, "颅顶", "arrow.up.to.line", "低" to "高"),
    FOREHEAD(ReshapeCategory.PROPORTION, "额头", "rectangle.tophalf.inset.filled", "短" to "长"),
    MID_FACE(ReshapeCategory.PROPORTION, "中庭", "rectangle.center.inset.filled", "长" to "短"),
    PHILTRUM(ReshapeCategory.PROPORTION, "人中", "arrow.up.and.down", "长" to "短"),
    LOWER_FACE(ReshapeCategory.PROPORTION, "下庭", "rectangle.bottomhalf.inset.filled", "长" to "短"),
    SHORT_FACE(ReshapeCategory.PROPORTION, "短脸", "arrow.down.and.line.horizontal.and.arrow.up", "长" to "短"),
    // 脸型
    SMART_SHAPE(ReshapeCategory.SHAPE, "智能脸型", "face.smiling", "宽" to "瘦"),
    FACE_WIDTH(ReshapeCategory.SHAPE, "脸宽", "arrow.left.and.right", "窄" to "宽"),
    SMALL_FACE(ReshapeCategory.SHAPE, "小脸", "arrow.down.right.and.arrow.up.left", "大" to "小"),
    SMOOTH_FACE(ReshapeCategory.SHAPE, "面部流畅", "circle.dashed", "" to "流畅"),
    CHEEKBONE(ReshapeCategory.SHAPE, "颧骨", "arrow.right.and.line.vertical.and.arrow.left", "宽" to "收"),
    CHIN_LENGTH(ReshapeCategory.SHAPE, "下巴长短", "arrow.up.and.down", "短" to "长"),
    POINTED_CHIN(ReshapeCategory.SHAPE, "尖下巴", "arrowtriangle.down", "圆" to "尖"),
    V_FACE(ReshapeCategory.SHAPE, "V脸", "chevron.down", "" to "V"),
    JAW_ANGLE(ReshapeCategory.SHAPE, "下颌角", "angle", "宽" to "收"),
    JAW_LINE(ReshapeCategory.SHAPE, "下颌线", "line.diagonal", "松" to "紧"),
    HAIRLINE(ReshapeCategory.SHAPE, "发际线", "arrow.down.to.line", "高" to "低"),
    // 眼睛
    EYE_SIZE(ReshapeCategory.EYES, "大小", "arrow.up.left.and.arrow.down.right", "小" to "大", paired = true),
    EYE_VERTICAL(ReshapeCategory.EYES, "上下", "arrow.up.and.down", "下" to "上", paired = true),
    EYE_HEIGHT(ReshapeCategory.EYES, "眼高", "eye", "扁" to "圆", paired = true),
    EYE_LENGTH(ReshapeCategory.EYES, "长度", "arrow.left.and.right", "短" to "长", paired = true),
    EYE_DISTANCE(ReshapeCategory.EYES, "眼距", "arrow.left.and.line.vertical.and.arrow.right", "近" to "远", paired = true),
    EYE_LIFT(ReshapeCategory.EYES, "提肌", "eye.fill", "" to "提", paired = true),
    IRIS_SIZE(ReshapeCategory.EYES, "眼瞳大小", "circle.circle", "小" to "大", paired = true),
    LOWER_LID(ReshapeCategory.EYES, "眼睑下至", "arrow.down.to.line", "" to "下至", paired = true),
    EYE_TAIL(ReshapeCategory.EYES, "眼尾上扬", "arrow.up.right", "下垂" to "上扬", paired = true),
    EYE_TILT(ReshapeCategory.EYES, "倾斜", "rotate.right", "下垂" to "上扬", paired = true),
    EYE_BAGS(ReshapeCategory.EYES, "去眼袋", "eye.trianglebadge.exclamationmark", "" to "", paired = true, bipolar = false),
    RED_EYE(ReshapeCategory.EYES, "祛红血丝", "drop", "" to "", paired = true, bipolar = false),
    INNER_CORNER(ReshapeCategory.EYES, "内眼角", "arrow.right.to.line", "收" to "开", paired = true),
    OUTER_CORNER(ReshapeCategory.EYES, "外眼角", "arrow.left.to.line", "收" to "开", paired = true),
    // 嘴唇
    LIP_SIZE(ReshapeCategory.LIPS, "大小", "arrow.up.left.and.arrow.down.right", "小" to "大"),
    LIP_WIDTH(ReshapeCategory.LIPS, "宽度", "arrow.left.and.right", "窄" to "宽"),
    LIP_VERTICAL(ReshapeCategory.LIPS, "上下", "arrow.up.and.down", "下" to "上"),
    LIP_TILT(ReshapeCategory.LIPS, "倾斜", "rotate.right", "左高" to "右高"),
    LIP_HORIZONTAL(ReshapeCategory.LIPS, "左右", "arrow.left.arrow.right", "左" to "右"),
    M_LIP(ReshapeCategory.LIPS, "M唇", "mouth", "平" to "M"),
    FULL_LIP(ReshapeCategory.LIPS, "丰唇", "mouth.fill", "薄" to "丰"),
    SMILE(ReshapeCategory.LIPS, "微笑", "face.smiling", "下" to "上扬"),
    TEETH(ReshapeCategory.LIPS, "白牙", "sparkles", "" to "", bipolar = false),
    // 鼻子
    NOSE_SIZE(ReshapeCategory.NOSE, "大小", "arrow.up.left.and.arrow.down.right", "小" to "大"),
    NOSE_LIFT(ReshapeCategory.NOSE, "提升", "arrow.up", "长" to "提"),
    NOSE_WING(ReshapeCategory.NOSE, "鼻翼", "arrow.right.and.line.vertical.and.arrow.left", "宽" to "窄"),
    NOSE_ROOT(ReshapeCategory.NOSE, "山根", "arrow.up.to.line", "宽" to "窄"),
    NOSE_BRIDGE(ReshapeCategory.NOSE, "鼻梁", "line.diagonal", "宽" to "窄"),
    NOSE_TIP(ReshapeCategory.NOSE, "鼻尖", "nose", "垂" to "翘"),
    // 眉毛
    BROW_VERTICAL(ReshapeCategory.BROWS, "上下", "arrow.up.and.down", "下" to "上", paired = true),
    BROW_THICKNESS(ReshapeCategory.BROWS, "粗细", "lineweight", "细" to "粗", paired = true),
    BROW_LENGTH(ReshapeCategory.BROWS, "长短", "arrow.left.and.right", "短" to "长", paired = true),
    BROW_DISTANCE(ReshapeCategory.BROWS, "间距", "arrow.left.and.line.vertical.and.arrow.right", "近" to "远", paired = true),
    BROW_HEAD_DISTANCE(ReshapeCategory.BROWS, "眉头间距", "arrow.left.arrow.right", "近" to "远", paired = true),
    BROW_TILT(ReshapeCategory.BROWS, "倾斜", "rotate.right", "下垂" to "上扬", paired = true),
    BROW_PEAK(ReshapeCategory.BROWS, "眉峰", "eyebrow", "平" to "高", paired = true);

    val isColor: Boolean get() = !bipolar
}

// MARK: - 数值

/** 一个项目的数值：-1...1。能分左右的项目两边各存一个，其余两个值相同。 */
data class ReshapeValue(val left: Float = 0f, val right: Float = 0f) {
    val isZero: Boolean get() = left == 0f && right == 0f
}

/** 一张脸的全部调整（不可变，改动时生成新对象）。 */
data class ReshapeFace(val info: FaceInfo, val values: Map<ReshapeKey, ReshapeValue> = emptyMap()) {
    val isIdentity: Boolean get() = values.isEmpty()

    fun value(key: ReshapeKey, side: ReshapeSide = ReshapeSide.BOTH): Float {
        val v = values[key] ?: return 0f
        return when (side) {
            ReshapeSide.BOTH -> if (abs(v.left) >= abs(v.right)) v.left else v.right
            ReshapeSide.LEFT -> v.left
            ReshapeSide.RIGHT -> v.right
        }
    }

    fun set(key: ReshapeKey, side: ReshapeSide, raw: Float): ReshapeFace {
        val value = raw.coerceIn(if (key.bipolar) -1f else 0f, 1f)
        var v = values[key] ?: ReshapeValue()
        v = when (if (key.paired) side else ReshapeSide.BOTH) {
            ReshapeSide.BOTH -> ReshapeValue(value, value)
            ReshapeSide.LEFT -> v.copy(left = value)
            ReshapeSide.RIGHT -> v.copy(right = value)
        }
        val next = values.toMutableMap()
        if (v.isZero) next.remove(key) else next[key] = v
        return copy(values = next)
    }
}

/** "变形还原"的一笔：涂过的地方变形被抹回去，颜色类的调整不受影响。 */
data class ReshapeRestoreStroke(
    /** 归一化图片坐标（原点左上角）。 */
    val points: List<PointF>,
    /** 笔刷半径，以图片长边为 1。 */
    val radius: Float,
)

// MARK: - AI 项目

/**
 * 面部重塑里交给 AI 做的项目：表情、转头、对称这类靠挪像素做不出来的改动。
 * 只把脸部这一块发出去，结果再贴回原图。
 */
class ReshapeAIItem(val title: String, val symbol: String, val category: ReshapeCategory, val prompt: String) {
    val id: String get() = category.name + "." + title

    val preset: AIPreset get() = AIPreset(title = title, symbol = symbol, prompt = prompt, faceCrop = true)

    companion object {
        /** 所有项目共用的要求：只改指定的那一处，必须还是同一个人，位置和构图不能动。 */
        private const val RULES =
            " This is a crop from a portrait photo. Change only what is described above and nothing else. " +
                "It must be unmistakably the same individual: keep the identity, face shape, facial proportions, skin tone, makeup, " +
                "hairstyle, hair colour, accessories, clothing, lighting direction and background exactly as in the input. " +
                "Keep the exact same framing, head size and position of everything so the result can be overlaid on the original. " +
                "Photorealistic, with natural skin texture."

        private fun make(title: String, symbol: String, category: ReshapeCategory, change: String) =
            ReshapeAIItem(title, symbol, category, change + RULES)

        private const val SYMMETRY =
            "Make the face more symmetrical: gently even out the differences between the left and right sides " +
                "(eye size and height, eyebrow height, nostrils, mouth corners, jawline). Subtle and natural, not a mirrored face."

        val all: List<ReshapeAIItem> = listOf(
            // 表情管理
            make("wink", "face.smiling", ReshapeCategory.EXPRESSION,
                "Change only the facial expression: the person winks playfully, closing the eye on the left side of the image while the other eye stays open, with a slight natural smile."),
            make("微笑", "face.smiling", ReshapeCategory.EXPRESSION,
                "Change only the facial expression: a gentle natural closed-mouth smile. Lift the corners of the mouth slightly and let the cheeks and eyes follow subtly."),
            make("嘴巴闭合", "mouth", ReshapeCategory.EXPRESSION,
                "Change only the mouth: close it naturally, lips gently together and relaxed, no teeth visible."),
            make("抿嘴笑", "mouth.fill", ReshapeCategory.EXPRESSION,
                "Change only the facial expression: a shy pressed-lip smile, lips pressed together with the corners of the mouth lifted."),
            make("大笑", "face.smiling.inverse", ReshapeCategory.EXPRESSION,
                "Change only the facial expression: a big happy open-mouth laugh showing natural upper teeth, cheeks raised and eyes smiling."),
            make("睁眼", "eye", ReshapeCategory.EXPRESSION,
                "Change only the eyes: open both eyes naturally and fully (they are closed or half closed in the input), looking toward the camera, with realistic irises, eyelids and eyelashes that match this person."),
            make("冷酷", "sunglasses", ReshapeCategory.EXPRESSION,
                "Change only the facial expression: a cool, calm, serious look. Relaxed neutral mouth, no smile, steady confident gaze."),
            // 3D 塑颜
            make("对称", "square.split.2x1", ReshapeCategory.POSE, SYMMETRY),
            make("抬头", "arrow.up", ReshapeCategory.POSE,
                "Change only the head pose: tilt the head slightly upward (chin up) by about 8 degrees, as if the camera were a little lower. The facial features follow the new angle consistently."),
            make("低头", "arrow.down", ReshapeCategory.POSE,
                "Change only the head pose: tilt the head slightly downward (chin down) by about 8 degrees, as if the camera were a little higher. The facial features follow the new angle consistently."),
            make("左转", "arrow.turn.up.left", ReshapeCategory.POSE,
                "Change only the head pose: turn the head slightly toward the left side of the image by about 10 degrees. The facial features follow the new angle consistently."),
            make("右转", "arrow.turn.up.right", ReshapeCategory.POSE,
                "Change only the head pose: turn the head slightly toward the right side of the image by about 10 degrees. The facial features follow the new angle consistently."),
            // 脸型
            make("面部超清", "wand.and.rays", ReshapeCategory.SHAPE,
                "Restore the face in very high definition: crisp eyes and eyelashes, individual eyebrow hairs, realistic skin texture with pores, clear lip texture. This is a restoration, not a redraw: every facial feature keeps exactly the same shape, size and position, and the expression and gaze do not change. Do not beautify."),
            make("面部对称", "square.split.2x1", ReshapeCategory.SHAPE, SYMMETRY),
            make("去双下巴", "arrow.up.to.line", ReshapeCategory.SHAPE,
                "Remove the double chin: tighten the area under the jaw so the jawline is clean and defined. Do not change anything above the jawline."),
            // 眼睛
            make("眼神矫正", "eye.circle", ReshapeCategory.EYES,
                "Correct the gaze so both eyes look directly into the camera lens. Keep the eye shape, eye size, iris colour, eyelids and eyelashes unchanged."),
            make("对称", "square.split.2x1", ReshapeCategory.EYES,
                "Make the two eyes symmetrical: match their size, shape, height and eyelid fold to each other. Natural, and nothing else about the eyes changes."),
            make("去脂", "eye.slash", ReshapeCategory.EYES,
                "Reduce the puffiness and fat of the upper eyelids so the eyelid fold is cleaner and the eyes look more awake. Keep the eye shape and size unchanged."),
        )

        fun items(category: ReshapeCategory): List<ReshapeAIItem> = all.filter { it.category == category }
    }
}
