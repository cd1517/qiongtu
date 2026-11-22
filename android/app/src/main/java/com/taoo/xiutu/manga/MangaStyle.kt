package com.taoo.xiutu.manga

// 漫画风格定义和指令，原样取自 iOS 版 MangaStyle.swift（LifeManga）。

/** 漫画风格：中文名、副标题、给 AI 的英文指令。 */
enum class MangaStyle(val raw: String, val displayName: String, val subtitle: String) {
    SHONEN_JUMP("shonenJump", "经典少年Jump风", "间谍过家家 / 咒术回战 那种现代少年漫干净线条"),
    SLICE_OF_LIFE("sliceOfLife", "日常治愈风", "辉夜大小姐 / 邻家女孩 那种温柔日常感"),
    DARK_SEINEN("darkSeinen", "暗黑剧情风", "我推的孩子 / 链锯人 那种有质感的剧情漫画"),
    RETRO_GEKIGA("retroGekiga", "复古剧画风", "70-80 年代写实剧画，浓厚老派气息"),
    CHIBI_4KOMA("chibi4Koma", "萌系四格风", "Q 版四格漫画，可爱搞笑"),
    SPORTS_HOT_BLOODED("sportsHotBlooded", "运动热血风", "灌篮高手 / 排球少年 那种动感燃系"),
    SCIFI_MECHA("scifiMecha", "科幻机甲风", "攻壳机动队 / AKIRA 那种赛博机械感"),
    HORROR_JUNJI_ITO("horrorJunjiIto", "悬疑氛围风", "Monster / 死亡笔记 那种紧张心理悬疑感（非暴力）"),
    AMERICAN_SUPERHERO("americanSuperhero", "美漫英雄风", "现代主流超级英雄月刊感，强动态强透视"),
    SHOGAKUKAN_KIDS("shogakukanKids", "日本小学馆漫画风", "经典日本少儿漫画，清爽圆润、轻科幻喜剧"),
    SHOJO_ROMANCE("shojoRomance", "少女漫风", "清透纤细的现代少女恋爱漫画感"),
    HOT_BLOOD_ADVENTURE("hotBloodAdventure", "热血漫画风", "王道少年冒险战斗，燃、冲、连载感强"),
    NOSTALGIC_CHILDHOOD("nostalgicChildhood", "细节童年风", "怀旧童年日常，温暖闹腾、老单行本质感"),
    YOUTH_4KOMA("youth4Koma", "四格青春风", "校园社团四格，轻萌青春、吐槽节奏快"),
    NAVIGATION_GUIDE("navigationGuide", "导航案内", "街景地图 + 俯视路线 + 角色带路，像路线指引漫画"),
    ;

    /** 基础风格指令，不含色彩指令。 */
    val prompt: String
        get() = when (this) {
            SHONEN_JUMP -> "Modern mainstream Weekly Shonen Jump manga style, in the vein of Spy x Family, Jujutsu Kaisen, Chainsaw Man, My Hero Academia, or Oshi no Ko. Clean confident inking with strong line-weight variation. Big expressive eyes with sharp highlights, dynamic but readable poses, sharp angular hair drawn as simple solid black silhouettes. Use screen-tone halftones SPARINGLY (only on hair, eyes, and one or two key shadow areas). Plenty of white space. Speed lines used purposefully — not scattered all over the page. The page must read crisp and clean."
            SLICE_OF_LIFE -> "Gentle modern Japanese slice-of-life manga style, like Kaguya-sama: Love is War, Komi Can't Communicate, or Yotsuba&!. Soft, very clean ink lines. Mostly white faces with subtle delicate shading. Calm warm composition, lots of negative space. Speech bubbles are clean rounded rectangles or soft ovals. Backgrounds are simplified and uncluttered. NO heavy crosshatching, NO sketchy lines."
            DARK_SEINEN -> "Modern seinen manga style, in the vein of Oshi no Ko, Chainsaw Man, or 20th Century Boys. Sharp confident inking with strong contrast: large solid-black shadow shapes versus clean white skin. Mature realistic proportions but still stylized. Atmospheric lighting through bold black silhouettes, NOT through scribbly hatching. Use screen tones for clothing and dramatic gradients. Keep faces and skin clean — let the silhouettes carry the mood."
            RETRO_GEKIGA -> "Vintage 1970s-80s Japanese dramatic manga illustration style. Realistic but still clearly stylized — confident hand-inked lines, deliberate classic crosshatching on clothing, architecture, and major shadow shapes, vintage screen-tone patterns, and restrained editorial composition. Faces should stay readable and expressive rather than overly gritty. The result should feel like a finished old-school printed manga page with mature atmosphere, careful staging, and period print charm."
            CHIBI_4KOMA -> "Cute chibi 4-koma comedy manga style, like Lucky Star, K-On!, or Azumanga Daioh. Super-deformed rounded character proportions, large simple eyes, clean thin confident ink lines. Almost no shading — just a tiny bit of light screen tone. Tons of white space, light and airy composition. Add small cute symbols (sparkles, sweat drops, music notes, hearts) tastefully. Speech bubbles are perfect clean ovals."
            SPORTS_HOT_BLOODED -> "Modern sports manga style, like Haikyuu!!, Kuroko no Basket, or Blue Lock. Dynamic action with confident clean inking, dramatic foreshortened poses, intense determined expressions. Speed lines used PURPOSEFULLY — radiating from a single focal point, not scattered. Sweat drops and motion effects with crisp clean lines. Backgrounds simplified to focus attention on the characters. Keep faces and skin mostly white — let the body language carry the energy."
            SCIFI_MECHA -> "Detailed sci-fi mecha manga style, like Ghost in the Shell, AKIRA, or modern Mobile Suit Gundam manga. Mechanical and architectural elements drawn with precise ruler-clean lines and accurate perspective. Characters with clean sharp inking, mature stylized proportions. Use screen tones thoughtfully on metal surfaces and lighting gradients. Keep human skin/faces relatively clean. Cyberpunk or hard-sci-fi atmosphere through composition, not noisy textures."
            HORROR_JUNJI_ITO -> "Psychological suspense manga style with strong noir atmosphere. Dense but precise and controlled crosshatching for dramatic shadow, every stroke deliberate and polished. Strong cinematic chiaroscuro with deep solid blacks against crisp clean whites. Build tension through composition and lighting: long shadows, off-kilter camera angles, half-lit faces, fog, empty corridors, distant silhouettes, and a quiet investigative mood. Keep everything grounded in an ordinary everyday world, with intact fully clothed characters, clean readable anatomy, and a refined detective-drama tone rather than sensational shock imagery."
            AMERICAN_SUPERHERO -> "Modern American superhero comic-book page style. High-energy action storytelling with bold black inks, strong line-weight variation, dramatic foreshortening, muscular stylized anatomy, cinematic panel composition, explosive motion, clean digital-style coloring logic even in monochrome value design, and impactful sound effect energy. Emphasize urban action, heroic poses, strong contrast shadows, and a polished mainstream monthly comic aesthetic."
            SHOGAKUKAN_KIDS -> "Classic Japanese children's comedy manga page style. Clean monochrome ink drawing with simple rounded character designs, highly readable panel layout, expressive cartoon faces, playful reaction poses, gentle everyday neighborhood atmosphere, light whimsical gadget-comedy feeling, tidy speech bubbles, minimal shading, sparse screen tones, and a wholesome retro family-friendly manga page aesthetic."
            SHOJO_ROMANCE -> "Modern Japanese shojo romance manga page style. Delicate thin ink lines, soft expressive eyes, flowing detailed hair, elegant slender character proportions, emotional close-up compositions, airy screen tones, gentle grayscale shading, subtle sparkles and soft background effects, clean white space, and a tender romantic atmosphere. Focus on feelings, eye contact, blush, hesitation, and quiet emotional tension rather than action."
            HOT_BLOOD_ADVENTURE -> "Classic Japanese shonen action manga page style. Bold energetic ink lines, exaggerated expressive faces, dynamic action composition, strong black shadow shapes, dramatic speed lines, adventurous battle atmosphere, powerful character silhouettes, and clear high-impact panel storytelling. Emphasize heroic energy, determination, friendship, forward momentum, and emotional intensity."
            NOSTALGIC_CHILDHOOD -> "Nostalgic Japanese childhood slice-of-life manga page style. Retro black-and-white family comedy manga with expressive child characters, warm neighborhood atmosphere, detailed everyday props, classic halftone shading, soft but confident ink lines, playful reaction faces, and an old-school printed manga texture. Emphasize childhood memory, small daily accidents, innocent mischief, cozy urban life, and gentle humorous storytelling."
            YOUTH_4KOMA -> "Japanese 4-koma youth comedy manga page style. Cute youthful character design, clean thin black-and-white linework, tidy four-panel layout, simple expressive faces, soft halftone shading, school and club-life atmosphere, light awkward humor, charming slice-of-life pacing, and a gentle retro moe manga aesthetic. Focus on small daily interactions, shy reactions, subtle jokes, and relaxed youthful energy rather than dramatic action."
            NAVIGATION_GUIDE -> "Japanese navigation-guide comic style. Create a clean, highly readable manga-like wayfinding page that combines simplified overhead map panels, street-view landmark panels, route arrows, destination callouts, and a friendly guide character who points, gestures, or walks the viewer through the journey. Emphasize visual clarity, directional flow, legible path progression, landmark recognition, and editorial travel-guide energy rather than drama. Re-draw real map screenshots into polished illustrated map blocks with simplified roads, blocks, station markers, crossings, and route highlights. Use clear arrows, numbered steps, inset zoom panels, and guide captions so the page feels like a charming hand-drawn route manual or local area navigation comic."
        }

    /** 风格指令 + 全局清爽规则 + 色彩指令。 */
    fun effectivePrompt(isColor: Boolean, stylizationStrength: Double = 0.78, preserveRealWorldReadability: Boolean = false): String {
        // 剧画和悬疑两种风格本身允许密集线条，不加全局清爽规则
        val cleanRule = if (this == HORROR_JUNJI_ITO || this == RETRO_GEKIGA) "" else "\n\n" + GLOBAL_CLEAN_INK_RULE
        val strength = stylizationStrength.coerceIn(0.0, 1.0)
        val stylizationDirective = when {
            strength < 0.34 -> "STYLIZATION STRENGTH — LOW:\nKeep the source scene highly recognizable. Preserve real-world street layout, landmark placement, building silhouettes, shopfront structure, and overall scene identity. Apply only light comic cleanup: mild outline enhancement, gentle color unification, and subtle illustration polish. Avoid aggressive redesign, excessive simplification, or heavy anime deformation."
            strength < 0.67 -> "STYLIZATION STRENGTH — MEDIUM:\nBalance recognizability and comic redraw. Preserve major landmarks, road geometry, building massing, and scene readability, while adding clearer contour lines, shape grouping, cleaner textures, and more intentional illustrated color design."
            else -> "STYLIZATION STRENGTH — HIGH:\nPush the image strongly toward manga/anime illustration. Redraw the scene with stronger line art, cleaner graphic shape design, clearer cel-shading or semi-cel-shading, more saturated palette separation, and much less camera realism. Make it feel obviously hand-drawn and artistically interpreted rather than photo-derived."
        }
        val preservationDirective = if (preserveRealWorldReadability) "REAL-WORLD READABILITY PRIORITY:\nPreserve wayfinding usefulness. Keep roads, crossings, station exits, storefront positions, landmark silhouettes, spatial relationships, and scene orientation recognizable. If signage or environmental text matters for identifying a place, keep its placement and visual role believable instead of replacing the area with fantasy details. Prioritize navigational clarity over dramatic stylization." else ""
        return if (isColor) {
            "COLOR MODE OVERRIDE — RENDER IN FULL COLOR:\nRender this page as a FULL-COLOR manga illustration with richer saturation, clearer hue separation, stronger line art, and obvious hand-drawn anime/comic stylization. Use clean cel-shading or semi-cel-shading, simplified illustrated textures, bold contour lines, and polished manga/anime color design rather than realistic photo rendering. Keep the world looking redrawn, graphic, and intentionally illustrated. Use vivid, harmonious colors, slightly boosted saturation, natural but stylized skin tones, and atmospheric lighting. Prioritize ink outlines and shape design over camera realism. Polished anime/manga color palette. IGNORE any \"black and white\", \"no color\", \"monochrome\", \"screen tone only\", or \"pure ink\" instructions in the style guide below — those are overridden by this full-color directive. Keep ink linework strong, clean, and confident.\n\n${stylizationDirective}\n${preservationDirective}\n\nSTYLE GUIDE:\n${prompt}${cleanRule}"
        } else {
            "COLOR MODE OVERRIDE — PURE BLACK AND WHITE:\nPure black-and-white manga ink art. No colors. Solid blacks for shadow shapes, screen-tone halftone dots used sparingly and deliberately. Follow the style guide below.\n\n${stylizationDirective}\n${preservationDirective}\n\nSTYLE GUIDE:\n${prompt}${cleanRule}"
        }
    }

    companion object {
        private const val GLOBAL_CLEAN_INK_RULE = "CRITICAL DRAWING RULES (override anything else if conflict):\n- CLEAN, CONFIDENT INK LINES with strong weight variation, like a printed manga page that just came off a professional inker's desk.\n- This must look HAND-DRAWN AND ILLUSTRATED, not like a filtered photo, painted-over photo, rotoscoped image, or lightly edited live-action still.\n- Aggressively TRANSLATE real-world photo details into manga/anime drawing language: simplified shapes, selective detail, clear silhouette design, and inked contour lines around important forms.\n- GENEROUS WHITE SPACE. Faces and clothing should be mostly white with just a few decisive shadow shapes.\n- DO NOT use sketchy, scribbly, pencil-rough, charcoal, or photo-realistic textures.\n- DO NOT preserve camera-realistic lens detail, photographic micro-texture, or documentary realism. Reduce real-world clutter and redraw it as stylized comic scenery.\n- Use SOLID FLAT BLACKS for major shadows, screen-tone dot patterns ONLY where appropriate (hair, clothing folds), NOT scattered everywhere.\n- Avoid heavy crosshatching/stippling on faces and skin — keep skin almost entirely white.\n- Backgrounds should be SIMPLIFIED: clean perspective lines, geometric shapes, lots of white. No noisy textures.\n- When the source image is a real street, room, building, or landscape, RE-DRAW it as a stylized manga background with clearer outlines, cleaner planes, and more graphic value grouping instead of preserving raw photographic detail.\n- Speech bubbles must be perfectly clean ovals with crisp single-line borders.\n- The page should look CRISP and READABLE at thumbnail size."
    }
}

/** 气泡、续接等指令。 */
object BubbleDirective {
    /** 续接上一页的风格延续指令。 */
    const val CONTINUITY_DIRECTIVE = "STYLE & STORY CONTINUITY:\nThe FIRST attached reference image is the PREVIOUS manga page from the same comic. Treat it as the gold standard for art style, line weight, character designs, costume details, and overall aesthetic. The new page must look like it could come right after that page in the same printed book. If recurring characters appear, KEEP their faces, hair, and outfits identical. Continue the visual narrative naturally — do not reset the style."

    /** 普通模式下的气泡指令。 */
    fun simpleMode(mode: String): String = when (mode) {
        "none" -> "BUBBLE / TEXT INSTRUCTION (override anything else):\nDo NOT draw any speech bubbles, caption boxes, or dialogue text on this page. Pure visual illustration only. Sound-effect kanji/katakana lettering integrated into the artwork is still allowed if appropriate to the style."
        "empty" -> "BUBBLE / TEXT INSTRUCTION:\nIf you draw speech bubbles or caption boxes, leave them COMPLETELY EMPTY — do NOT write any text inside them. Sound-effect lettering is still allowed."
        "japanese" -> "BUBBLE / TEXT INSTRUCTION:\nIf you add speech bubbles, render the dialogue inside them in JAPANESE (natural manga-style Japanese, allowing kanji plus hiragana/katakana). Do NOT use English. Do NOT use Chinese."
        "english" -> "BUBBLE / TEXT INSTRUCTION:\nIf you add speech bubbles, render the dialogue inside them in ENGLISH (short natural English manga-style lines)."
        else -> "BUBBLE / TEXT INSTRUCTION:\nIf you add speech bubbles, render the dialogue inside them in SIMPLIFIED CHINESE (短的中文台词，自然的漫画对话，每个气泡 ≤ 12 字). Do NOT use English. Do NOT use Japanese kana."
    }
}
