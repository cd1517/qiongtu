package com.taoo.xiutu.manga

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taoo.xiutu.ai.AIError
import com.taoo.xiutu.ai.AIOneShot
import com.taoo.xiutu.core.Img
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

// 漫画人生的角色库。画风、年龄段、性别的定义和给 AI 的指令原样取自 iOS 版 MangaCharacter.swift（LifeManga）。

@Serializable
enum class CharacterArtStyle(val displayName: String, val subtitle: String) {
    @SerialName("jpAnime") JP_ANIME("日漫风", "京阿尼 / 新海诚 现代动画风"),
    @SerialName("usComics") US_COMICS("美漫风", "Marvel / DC 美式漫画"),
    @SerialName("krManhwa") KR_MANHWA("韩漫风", "顶级 webtoon · 精致细腻"),
    @SerialName("kawaii") KAWAII("可爱风", "粉嫩、大眼、软萌"),
    @SerialName("chibi") CHIBI("Q 版", "2~3 头身 SD 缩小可爱"),
    @SerialName("render3D") RENDER_3D("3D 渲染", "皮克斯 / 平行宇宙 3D 渲染"),
    @SerialName("semiReal") SEMI_REAL("皮克斯风", "欧美动画电影角色设定感"),
    @SerialName("watercolor") WATERCOLOR("水彩风", "水彩晕染 + 纸纹"),
    @SerialName("pixelArt") PIXEL_ART("像素风", "16-bit JRPG / 复古游戏立绘"),
    ;

    /** 给 AI 的英文画风指令。 */
    val prompt: String
        get() = when (this) {
            JP_ANIME -> "Modern Japanese anime/manga character illustration style. Clean cel-shaded coloring with crisp confident lineart. Large expressive anime eyes with highlights, stylized hair with shine, vibrant clean color palette. Polished modern TV-anime production aesthetic."
            US_COMICS -> "Modern American graphic-novel character illustration style. Bold confident inking with strong line-weight variation. Realistic body proportions with slightly stylized features. Strong cel-shading with dramatic but tasteful shadows. Polished, painterly, professional comic illustration aesthetic. (No specific franchise; original character design.)"
            KR_MANHWA -> "Modern Korean webtoon character illustration style — polished glossy digital illustration with clean painterly cel-shading. Realistic body proportions with softly anime-influenced features, beautifully detailed expressive eyes, sharp confident lineart, vibrant modern color palette, smooth gradient shading on skin and hair. High-production-value webtoon aesthetic with cinematic lighting and dramatic poses. Original character design."
            KAWAII -> "Soft kawaii illustration style with a pastel palette (pinks, peaches, lavenders, mint). Large sparkly round eyes, simplified soft features, gentle rounded shapes, dreamy peaceful atmosphere. Cute mascot-friendly aesthetic. (Adult character; cute style only — keep adult facial structure and proportions.)"
            CHIBI -> "Stylized chibi / super-deformed cartoon ART STYLE applied to an ADULT character — playfully exaggerated proportions (about 3-4 heads tall), simplified rounded body, large expressive eyes, simplified hands. IMPORTANT: this is an adult character rendered in chibi STYLE (like an adult mascot or comedic short-form animation spinoff). NOT a depiction of a child, baby, or minor — keep the adult outfit, adult bearing, and any adult props (work bag, glasses, etc) visible. Cute, comedic cartoon aesthetic."
            RENDER_3D -> "Modern 3D-animated film character illustration style — soft realistic lighting with subtle sub-surface skin scattering, painterly fabric textures, stylized but rendered look, gentle depth of field. Polished feature-film CG aesthetic. Original character design (no specific franchise)."
            SEMI_REAL -> "Stylized western animated feature-film character design style. Friendly family-animation appeal with large expressive eyes, rounded appealing facial features, confident eyebrow shapes, clean stylized anatomy, and polished soft 3D-like shading. Feels like a premium animation-studio character sheet with expressive turnarounds, expression heads, and pose-friendly silhouette design. Warm, charming, original animated-movie aesthetic."
            WATERCOLOR -> "Traditional watercolor illustration style. Soft loose washes of color, organic blooming edges, visible paper texture, painterly imperfection. Controlled detail in the face with looser strokes elsewhere. Romantic, gentle, hand-painted feel. Studio-Ghibli-inspired concept-art aesthetic."
            PIXEL_ART -> "16-bit JRPG pixel-art character illustration style. Clean visible square pixels, limited vivid retro color palette, color blocking with deliberate dithering for shading. Distinct chunky pixel shapes for hair, eyes, and clothing. SNES / Game Boy Advance era role-playing game character portrait aesthetic. Render every element (character, callouts, expressions, items) in pixel-art form with a clearly visible pixel grid. Do NOT smooth the pixels into a normal illustration."
        }
}

@Serializable
enum class CharacterAgeGroup(val displayName: String, val promptPhrase: String) {
    @SerialName("baby") BABY("小宝宝", "a baby"),
    @SerialName("child") CHILD("小孩子", "a young child"),
    @SerialName("teen") TEEN("少年", "a teenager"),
    @SerialName("youngAdult") YOUNG_ADULT("青年", "a young adult"),
    @SerialName("middleAged") MIDDLE_AGED("中年", "a middle-aged adult"),
    @SerialName("elderly") ELDERLY("老年", "an elderly adult"),
    ;

    val isMinor: Boolean get() = this == BABY || this == CHILD
}

@Serializable
enum class CharacterGender(val displayName: String, val promptPhrase: String) {
    @SerialName("male") MALE("男", "male"),
    @SerialName("female") FEMALE("女", "female"),
}

/** 角色库里的一个角色：一张由照片生成的标准立绘，加上名字和设定。 */
@Serializable
data class MangaCharacter(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val bio: String,
    val ageGroup: CharacterAgeGroup,
    val gender: CharacterGender,
    val artStyle: CharacterArtStyle,
    val imageFile: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 三视图（正面、侧面、背面画在一张横图上）。没生成过就是 null。 */
    val sheetFile: String? = null,
)

object CharacterPrompts {
    fun buildCharacterPortraitPrompt(
        name: String,
        bio: String?,
        ageGroup: CharacterAgeGroup,
        gender: CharacterGender,
        artStyle: CharacterArtStyle,
    ): String {
        val lines = mutableListOf<String>()
        lines += "Create a polished full-body CHARACTER PORTRAIT based on the reference photo."
        lines += "This is NOT a collage or design board. It must be a single clean standing character illustration."
        lines += ""
        lines += "Composition:"
        lines += "• One ${ageGroup.promptPhrase} ${gender.promptPhrase} character only, full body visible from head to shoes."
        lines += "• Plain pure white or very light neutral studio background."
        lines += "• Clean silhouette, natural relaxed standing pose, fashion-illustration presentation."
        lines += "• No extra props, no text, no labels, no callout boxes, no duplicate heads."
        lines += ""
        lines += "Character: $name.${if (bio.isNullOrEmpty()) "" else " $bio."}"
        lines += ""
        lines += "Art style: ${artStyle.prompt}"
        lines += ""
        lines += "Rules: The character should clearly read as ${ageGroup.promptPhrase} and ${gender.promptPhrase}, not another life stage. Match face, hair, body build and vibe from the source photo as closely as the policy allows. Make the outfit feel signature and coherent with the bio. Render in full color. Premium clean character reference illustration quality."
        return lines.joinToString("\n")
    }

    /** 三视图：以标准立绘为准，把同一个角色的正面、侧面、背面画在一张横图上。 */
    fun turnaroundPrompt(character: MangaCharacter): String {
        val lines = mutableListOf<String>()
        lines += "Create a CHARACTER TURNAROUND SHEET of the character shown in the reference image."
        lines += ""
        lines += "CHARACTER:"
        lines += "- Name: ${character.name}"
        if (character.bio.isNotEmpty()) lines += "- Background: ${character.bio}"
        lines += "- The reference image is this character's canonical design. Keep the face, hairstyle, hair color, body build, outfit, colors and accessories IDENTICAL in every view. Do not redesign anything."
        lines += "- The character is ${character.ageGroup.promptPhrase} and ${character.gender.promptPhrase}."
        lines += ""
        lines += "THREE VIEWS, side by side in one wide image, same scale, feet on the same ground line, evenly spaced:"
        lines += "1. LEFT — FRONT: Standing facing camera, full body, neutral expression, neutral A-pose."
        lines += "2. CENTER — SIDE: Standing in profile, side view, full body silhouette readable."
        lines += "3. RIGHT — BACK: Standing facing AWAY from camera, full body view of the back of the character."
        lines += ""
        lines += "LAYOUT:"
        lines += "- Plain neutral background (off-white or very light gray), no scenery, no extra props."
        lines += "- Each figure fully visible from head to feet, not overlapping the others."
        lines += "- No text, no labels, no caption, no arrows, no extra heads or detail callouts. Just the three figures on plain background."
        lines += "- Composition reads cleanly as a turnaround/character-sheet illustration."
        lines += ""
        lines += "Art style: ${character.artStyle.prompt}"
        return lines.joinToString("\n")
    }

    /** 画漫画时载入了角色：告诉 AI 最后几张参考图是角色设定。 */
    fun directive(characters: List<MangaCharacter>): String? {
        if (characters.isEmpty()) return null
        val names = characters.joinToString("、") { it.name }
        return "CHARACTER REFERENCE:\nI have attached ${characters.size} character reference image(s) at the end of the input set. These represent the recurring characters that should appear in this page (${names}). Match their faces, hair, outfits and overall design EXACTLY as shown. Do not redesign them.\n\nIMPORTANT REFERENCE-USE RULE:\nUse ONLY the main standing character figure from each reference as the canonical design. Ignore any side expression heads, accessory callouts, prop layouts, object boards, arrows, labels, multi-panel sheet layout, background decorations, or extra design-sheet elements that may appear around the character in a character sheet. Those surrounding helper elements are reference aids only and must NOT be copied into the manga unless the user explicitly asked for that object or prop in the scene."
    }
}

/** 角色库：存在 App 私有目录（files/characters），不上传。 */
object CharacterStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var folder: File? = null
    private val cache = HashMap<String, Bitmap?>()

    var characters by mutableStateOf<List<MangaCharacter>>(emptyList())
        private set

    fun init(context: Context) {
        if (folder != null) return
        val dir = File(context.applicationContext.filesDir, "characters").apply { mkdirs() }
        folder = dir
        val index = File(dir, "index.json")
        if (index.exists()) {
            runCatching { json.decodeFromString<List<MangaCharacter>>(index.readText()) }.getOrNull()?.let { characters = it }
        }
    }

    private fun dir(): File = folder ?: error("CharacterStore 未初始化")

    private fun persist() {
        val index = File(dir(), "index.json")
        val tmp = File(dir(), "index.json.tmp")
        runCatching {
            tmp.writeText(json.encodeToString(characters))
            tmp.renameTo(index)
        }
    }

    private fun load(file: String): Bitmap? = synchronized(cache) {
        if (cache.containsKey(file)) return cache[file]
        val image = Img.decode(File(dir(), file))
        cache[file] = image
        image
    }

    fun cached(file: String): Bitmap? = synchronized(cache) { cache[file] }

    /** 立绘（同步读，带缓存）。 */
    fun image(character: MangaCharacter): Bitmap? = load(character.imageFile)

    fun sheet(character: MangaCharacter): Bitmap? = character.sheetFile?.let { load(it) }

    suspend fun imageAsync(file: String): Bitmap? = withContext(Dispatchers.IO) { load(file) }

    private fun writeJpeg(image: Bitmap, file: String): Boolean = runCatching {
        File(dir(), file).writeBytes(Img.jpeg(image, 93))
        true
    }.getOrDefault(false)

    suspend fun add(
        name: String, bio: String, ageGroup: CharacterAgeGroup, gender: CharacterGender,
        artStyle: CharacterArtStyle, image: Bitmap,
    ): MangaCharacter? {
        val file = UUID.randomUUID().toString().uppercase() + ".jpg"
        if (!withContext(Dispatchers.IO) { writeJpeg(image, file) }) return null
        val character = MangaCharacter(name = name, bio = bio, ageGroup = ageGroup, gender = gender, artStyle = artStyle, imageFile = file)
        synchronized(cache) { cache[file] = image }
        characters = listOf(character) + characters
        persist()
        return character
    }

    /** 给角色存上（或换掉）三视图。 */
    suspend fun setSheet(image: Bitmap, id: String) {
        val index = characters.indexOfFirst { it.id == id }
        if (index < 0) return
        val file = UUID.randomUUID().toString().uppercase() + "-sheet.jpg"
        if (!withContext(Dispatchers.IO) { writeJpeg(image, file) }) return
        val old = characters[index].sheetFile
        if (old != null) {
            File(dir(), old).delete()
            synchronized(cache) { cache.remove(old) }
        }
        synchronized(cache) { cache[file] = image }
        characters = characters.map { if (it.id == id) it.copy(sheetFile = file) else it }
        persist()
    }

    /** 生成三视图：把标准立绘发给 AI，拿回一张横图。返回这次的费用（算不出来就是 null）。 */
    suspend fun generateSheet(id: String): Double? {
        val character = characters.firstOrNull { it.id == id }
        val portrait = character?.let { withContext(Dispatchers.IO) { image(it) } }
            ?: throw AIError("找不到这个角色的立绘。")
        val (image, usage) = AIOneShot.run(CharacterPrompts.turnaroundPrompt(character), listOf(portrait), size = "1536x1024")
        setSheet(image, id)
        return usage?.cost
    }

    fun remove(character: MangaCharacter) {
        characters = characters.filter { it.id != character.id }
        File(dir(), character.imageFile).delete()
        synchronized(cache) { cache.remove(character.imageFile) }
        character.sheetFile?.let {
            File(dir(), it).delete()
            synchronized(cache) { cache.remove(it) }
        }
        persist()
    }
}
