package com.nuttavern.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.nuttavern.ui.components.NutTavernSectionLabel
import com.nuttavern.ui.components.NutTavernSelectableRow
import com.nuttavern.ui.components.NutTavernSelectedCheckIcon
import com.nuttavern.ui.viewmodel.SettingsViewModel

/**
 * 颜色主题二级页。列出 [com.nuttavern.ui.theme.ThemePresets] 全部预设,点击切换并立即生效。
 *
 * 当前阶段只展示内置预设;后续接入"自定义种子色"后,在这一页下方再加一组卡片承载
 * 自定义入口,不要把自定义流程塞进同一组列表。
 *
 * 这里不用 [com.nuttavern.ui.components.NutTavernGroupSection] / `NutTavernGroupCard`
 * 包装:`NutTavernSelectableRow` 自带 `surfaceContainerLow` 背景与圆角,本身就是
 * "列表项即卡片"的形态,再嵌一层会出现卡中卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val currentTheme by viewModel.currentTheme.collectAsState()
    val themes = viewModel.availableThemes

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("显示设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "section-label") {
                NutTavernSectionLabel(text = "颜色主题")
            }
            items(themes, key = { it.id }) { theme ->
                val selected = theme.id == currentTheme.id
                NutTavernSelectableRow(
                    title = theme.displayName,
                    selected = selected,
                    trailingContent = if (selected) {
                        { NutTavernSelectedCheckIcon(contentDescription = "当前主题") }
                    } else {
                        null
                    },
                    onClick = { viewModel.selectTheme(theme.id) },
                )
            }
        }
    }
}
