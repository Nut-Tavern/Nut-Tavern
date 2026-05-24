package com.nuttavern

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nuttavern.ui.chat.ChatScreen
import com.nuttavern.ui.character.CharacterEditScreen
import com.nuttavern.ui.character.CharacterListScreen
import com.nuttavern.ui.persona.UserPersonaEditScreen
import com.nuttavern.ui.persona.UserPersonaListScreen
import com.nuttavern.ui.preset.PresetEditScreen
import com.nuttavern.ui.preset.PresetListScreen
import com.nuttavern.ui.regex.RegexEditScreen
import com.nuttavern.ui.regex.RegexGroupScreen
import com.nuttavern.ui.regex.RegexListScreen
import com.nuttavern.ui.settings.ProviderDetailScreen
import com.nuttavern.ui.settings.ProviderListScreen
import com.nuttavern.ui.settings.SettingsScreen
import com.nuttavern.ui.settings.ThemeSettingsScreen
import com.nuttavern.ui.theme.NutTavernTheme
import com.nuttavern.ui.tools.ToolsSettingsScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * 应用根入口。
 *
 * 路由仅作"页与页之间的跳转"用,不承担状态;状态依旧在各 Screen 自己的 ViewModel 里。
 * 路由表保持手写 string,等到出现深链 / 参数化路由再考虑 type-safe Navigation。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NutTavernTheme {
                NutTavernNavGraph()
            }
        }
    }
}

private object Routes {
    const val Chat = "chat"
    const val Settings = "settings"
    const val ThemeSettings = "settings/theme"
    const val Providers = "settings/providers"
    const val ProviderDetail = "settings/providers/{providerId}"
    const val Characters = "settings/characters"
    const val CharacterDetail = "settings/characters/{characterId}"
    const val Personas = "settings/personas"
    const val PersonaDetail = "settings/personas/{personaId}"
    const val Presets = "settings/presets"
    const val PresetDetail = "settings/presets/{presetId}"
    const val Regex = "settings/regex"
    const val RegexGroup = "settings/regex/group/{groupId}"
    const val RegexDetail = "settings/regex/{regexId}"
    const val RegexGroupScriptDetail = "settings/regex/group/{groupId}/script/{scriptId}"
    const val Tools = "settings/tools"

    fun providerDetail(providerId: String): String = "settings/providers/$providerId"
    fun characterDetail(characterId: String): String = "settings/characters/$characterId"
    fun personaDetail(personaId: String): String = "settings/personas/$personaId"
    fun presetDetail(presetId: String): String = "settings/presets/$presetId"
    fun regexGroup(groupId: String): String = "settings/regex/group/$groupId"
    fun regexDetail(regexId: String): String = "settings/regex/$regexId"
    fun regexGroupScriptDetail(groupId: String, scriptId: String): String =
        "settings/regex/group/$groupId/script/$scriptId"
}

/** 设置 / 二级页右滑入 / 右滑出的动画时长。M3 标准 emphasized 段 250-300ms,这里取 260。 */
private const val NAV_SLIDE_DURATION_MILLIS = 260

@androidx.compose.runtime.Composable
private fun NutTavernNavGraph() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.Chat,
        // NavHost 默认的 fade 转场视觉太弱,改成全局 None;只让 settings 类页面右滑,
        // 主页 ChatScreen 在被推 / 被还原时不参与动画,保持原地稳定。
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.Chat) {
            ChatScreen(
                onNavigateToSettings = { navController.navigate(Routes.Settings) },
                onNavigateToPersonaDetail = { personaId ->
                    navController.navigate(Routes.personaDetail(personaId))
                },
                onNavigateToCharacterDetail = { characterId ->
                    navController.navigate(Routes.characterDetail(characterId))
                },
                onNavigateToPresetDetail = { presetId ->
                    navController.navigate(Routes.presetDetail(presetId))
                },
                onNavigateToRegexDetail = { regexId ->
                    navController.navigate(Routes.regexDetail(regexId))
                },
            )
        }
        composable(
            Routes.Settings,
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenThemeSettings = { navController.navigate(Routes.ThemeSettings) },
                onOpenProviders = { navController.navigate(Routes.Providers) },
                onOpenCharacters = { navController.navigate(Routes.Characters) },
                onOpenPersonas = { navController.navigate(Routes.Personas) },
                onOpenPresets = { navController.navigate(Routes.Presets) },
                onOpenRegex = { navController.navigate(Routes.Regex) },
                onOpenTools = { navController.navigate(Routes.Tools) },
            )
        }
        composable(
            Routes.ThemeSettings,
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) {
            ThemeSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.Providers,
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) {
            ProviderListScreen(
                onBack = { navController.popBackStack() },
                onOpenProviderDetail = { providerId ->
                    navController.navigate(Routes.providerDetail(providerId))
                },
            )
        }
        composable(
            Routes.ProviderDetail,
            arguments = listOf(
                androidx.navigation.navArgument("providerId") {
                    type = androidx.navigation.NavType.StringType
                },
            ),
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("providerId").orEmpty()
            ProviderDetailScreen(
                providerId = id,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.Characters,
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) {
            CharacterListScreen(
                onBack = { navController.popBackStack() },
                onOpenCharacterDetail = { characterId ->
                    navController.navigate(Routes.characterDetail(characterId))
                },
            )
        }
        composable(
            Routes.CharacterDetail,
            arguments = listOf(
                androidx.navigation.navArgument("characterId") {
                    type = androidx.navigation.NavType.StringType
                },
            ),
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("characterId").orEmpty()
            CharacterEditScreen(
                characterId = id,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.Personas,
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) {
            UserPersonaListScreen(
                onBack = { navController.popBackStack() },
                onOpenPersonaDetail = { personaId ->
                    navController.navigate(Routes.personaDetail(personaId))
                },
            )
        }
        composable(
            Routes.PersonaDetail,
            arguments = listOf(
                androidx.navigation.navArgument("personaId") {
                    type = androidx.navigation.NavType.StringType
                },
            ),
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("personaId").orEmpty()
            UserPersonaEditScreen(
                personaId = id,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.Presets,
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) {
            PresetListScreen(
                onBack = { navController.popBackStack() },
                onOpenPresetDetail = { presetId ->
                    navController.navigate(Routes.presetDetail(presetId))
                },
            )
        }
        composable(
            Routes.PresetDetail,
            arguments = listOf(
                androidx.navigation.navArgument("presetId") {
                    type = androidx.navigation.NavType.StringType
                },
            ),
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("presetId").orEmpty()
            PresetEditScreen(
                presetId = id,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.Tools,
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) {
            ToolsSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.Regex,
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) {
            RegexListScreen(
                onBack = { navController.popBackStack() },
                onOpenRegexDetail = { regexId ->
                    navController.navigate(Routes.regexDetail(regexId))
                },
                onOpenRegexGroup = { groupId ->
                    navController.navigate(Routes.regexGroup(groupId))
                },
            )
        }
        composable(
            Routes.RegexGroup,
            arguments = listOf(
                androidx.navigation.navArgument("groupId") {
                    type = androidx.navigation.NavType.StringType
                },
            ),
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId").orEmpty()
            RegexGroupScreen(
                groupId = groupId,
                onBack = { navController.popBackStack() },
                onNavigateToGroup = { newGroupId ->
                    // 新建组 → 把 create 占位页用真实组页替换,避免返回栈里留个"新建中"
                    navController.navigate(Routes.regexGroup(newGroupId)) {
                        popUpTo(Routes.RegexGroup) { inclusive = true }
                    }
                },
                onOpenScriptDetail = { gId, scriptId ->
                    navController.navigate(Routes.regexGroupScriptDetail(gId, scriptId))
                },
            )
        }
        composable(
            Routes.RegexDetail,
            arguments = listOf(
                androidx.navigation.navArgument("regexId") {
                    type = androidx.navigation.NavType.StringType
                },
            ),
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("regexId").orEmpty()
            RegexEditScreen(
                regexId = id,
                groupId = null,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.RegexGroupScriptDetail,
            arguments = listOf(
                androidx.navigation.navArgument("groupId") {
                    type = androidx.navigation.NavType.StringType
                },
                androidx.navigation.navArgument("scriptId") {
                    type = androidx.navigation.NavType.StringType
                },
            ),
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    initialOffsetX = { it },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_SLIDE_DURATION_MILLIS),
                    targetOffsetX = { it },
                )
            },
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId").orEmpty()
            val scriptId = backStackEntry.arguments?.getString("scriptId").orEmpty()
            RegexEditScreen(
                regexId = scriptId,
                groupId = groupId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
