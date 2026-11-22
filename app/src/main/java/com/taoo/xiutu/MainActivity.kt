package com.taoo.xiutu

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.editor.EditorModel
import com.taoo.xiutu.editor.EditorScreen
import com.taoo.xiutu.editor.Tool
import com.taoo.xiutu.godcover.GodCoverScreen
import com.taoo.xiutu.history.HistoryScreen
import com.taoo.xiutu.history.HistoryStore
import com.taoo.xiutu.home.HomeScreen
import com.taoo.xiutu.manga.MangaScreen
import com.taoo.xiutu.picker.PhotoPickerScreen
import com.taoo.xiutu.raw.RawScreen
import com.taoo.xiutu.studio.StudioScreen
import com.taoo.xiutu.tools.beauty.FavoriteFaceScreen
import com.taoo.xiutu.tools.beauty.FavoriteFaceStore
import com.taoo.xiutu.ui.XiuTuTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AISettings.init(applicationContext)
        HistoryStore.init(applicationContext)
        FavoriteFaceStore.init(applicationContext)
        setContent { XiuTuTheme { AppNav() } }
    }
}

private fun enc(s: String) = Uri.encode(s)

/** 页面路由。 */
@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                start = { t -> nav.navigate("picker/editor/${t?.name ?: "none"}") },
                startCover = { nav.navigate("picker/cover/none") },
                startStudio = { nav.navigate("picker/studio/none") },
                startManga = { nav.navigate("manga") },
                startHistory = { nav.navigate("history") },
                startRaw = { nav.navigate("raw") },
                startFavorite = { nav.navigate("favoriteFace") },
            )
        }
        composable("picker/{target}/{tool}") { e ->
            val target = e.arguments?.getString("target") ?: "editor"
            val tool = e.arguments?.getString("tool") ?: "none"
            PhotoPickerScreen(
                onPick = { uri ->
                    when (target) {
                        "cover" -> nav.navigate("cover/${enc(uri.toString())}")
                        "studio" -> nav.navigate("studio/${enc(uri.toString())}")
                        else -> nav.navigate("editor/${enc(uri.toString())}/$tool")
                    }
                },
                onClose = { nav.popHome() },
            )
        }
        composable("editor/{uri}/{tool}") { e ->
            val uri = Uri.parse(e.arguments?.getString("uri"))
            val tool = e.arguments?.getString("tool")?.let { n -> Tool.entries.firstOrNull { it.name == n } }
            val context = LocalContext.current
            val model = remember { EditorModel(context).also { it.load(uri) } }
            EditorScreen(model, tool) { nav.popBackStack() }
        }
        composable("editorFile/{path}") { e ->
            val file = File(e.arguments?.getString("path") ?: "")
            val context = LocalContext.current
            val model = remember { EditorModel(context).also { it.load(file) } }
            EditorScreen(model, null) { nav.popBackStack() }
        }
        composable("cover/{uri}") { e ->
            GodCoverScreen(Uri.parse(e.arguments?.getString("uri"))) { nav.popBackStack() }
        }
        composable("studio/{uri}") { e ->
            StudioScreen(Uri.parse(e.arguments?.getString("uri"))) { nav.popBackStack() }
        }
        composable("raw") {
            RawScreen(onEdit = { f -> nav.navigate("editorFile/${enc(f.absolutePath)}") }, onClose = { nav.popHome() })
        }
        composable("favoriteFace") { FavoriteFaceScreen { nav.popHome() } }
        composable("manga") { MangaScreen { nav.popHome() } }
        composable("history") {
            HistoryScreen(onEdit = { f -> nav.navigate("editorFile/${enc(f.absolutePath)}") }, onClose = { nav.popHome() })
        }
    }
}

private fun NavHostController.popHome() {
    popBackStack("home", inclusive = false)
}
