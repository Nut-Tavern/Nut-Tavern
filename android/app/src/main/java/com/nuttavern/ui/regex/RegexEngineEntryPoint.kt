package com.nuttavern.ui.regex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.nuttavern.data.regex.RegexScript
import com.nuttavern.regex.RegexEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 让 Composable 直接拿到 [RegexEngine] 用于 Test Mode 等场景,而不引入额外 ViewModel。
 *
 * 与 [com.nuttavern.ui.theme.ThemeRepositoryEntryPoint] 同模式。表单类组件
 * ([RegexScriptFormBody])复用,GLOBAL / SCOPED 路径都拿同一个 engine 实例,无需各自注入。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RegexEngineEntryPoint {
    fun regexEngine(): RegexEngine
}

/**
 * 拿一个用于 Test Mode 的同步 runner。Compose Preview / 取不到 Hilt 容器时返回原文 runner,
 * 不抛异常,与 [com.nuttavern.ui.theme.NutTavernTheme] 同退化路径。
 */
@Composable
internal fun rememberRegexTestRunner(): (RegexScript, String) -> String {
    val context = LocalContext.current
    val engine = remember(context) {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                RegexEngineEntryPoint::class.java,
            ).regexEngine()
        }.getOrNull()
    }
    return remember(engine) {
        { script, input ->
            engine?.runRegexScript(input, script) ?: input
        }
    }
}
