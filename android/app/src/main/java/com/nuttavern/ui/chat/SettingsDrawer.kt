package com.nuttavern.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.BookUser
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PanelRightClose
import com.composables.icons.lucide.Regex
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Table
import com.composables.icons.lucide.TableOfContents
import com.composables.icons.lucide.TableProperties
import com.composables.icons.lucide.UserRound
import com.nuttavern.data.character.Character
import com.nuttavern.data.persona.UserPersona
import com.nuttavern.data.preset.Preset
import com.nuttavern.data.regex.RegexScript
import com.nuttavern.ui.character.CharacterAvatarPlaceholder
import com.nuttavern.ui.character.NEW_CHARACTER_PLACEHOLDER_ID
import com.nuttavern.ui.character.characterSubtitleDisplay
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.persona.personaPrimaryDisplay
import com.nuttavern.ui.persona.personaSubtitleDisplay
import com.nuttavern.ui.preset.PresetAvatarPlaceholder
import com.nuttavern.ui.preset.presetSubtitleDisplay

/**
 * 聊天页右侧"工作台"侧栏。
 *
 * "角色卡片"、"用户身份"、"预设"、"正则"四行都是**动态行** — title / subtitle 实时反映当前会话锁定的
 * 角色 / 身份 / 预设 / 正则启用数。
 * - 角色卡片:已绑定时显示 [Character.name] + 描述摘要,leading 走 [CharacterAvatarPlaceholder];
 *   未绑定时显示"未选择角色 / 点击新建角色卡",leading 退化为通用图标。点击走
 *   [onNavigateToCharacterDetail]:已绑定跳对应编辑页,未绑定跳新建占位 id。
 * - 用户身份:[currentPersona] = null 时显示"无"伪卡占位文案;非 null 时显示具体身份信息。
 *   leading 暂用 [Lucide.UserRound] 普通图标,后端阶段头像选择接入后再切到带头像的
 *   PersonaAvatarPlaceholder。
 * - 预设:仓库永远有兜底默认预设,因此 [currentPreset] 通常不为 null;为 null 时按"默认预设"
 *   占位提示。点击 → 调 [onOpenPresetPicker] 弹 PresetPickerSheet 切换预设。
 * - 正则:显示当前启用的全局正则数(总数 / 启用数);点击 → 调 [onOpenRegexPicker] 弹
 *   RegexPickerSheet 管理。
 *
 * 当前角色 / 身份 / 预设 / 正则 / 切换回调由调用方(ChatScreen)从 ChatViewModel 透传进来,
 * 抽屉本身不持有任何 ViewModel,避免重复订阅。
 */
@Composable
internal fun SettingsDrawer(
    currentCharacter: Character?,
    currentPersona: UserPersona?,
    currentPreset: Preset?,
    globalRegexScripts: List<RegexScript>,
    regexCounts: Pair<Int, Int>,
    lorebookCounts: Pair<Int, Int>,
    onOpenUnavailableFeature: (String) -> Unit,
    onOpenPersonaPicker: () -> Unit,
    onOpenPresetPicker: () -> Unit,
    onOpenRegexPicker: () -> Unit,
    onOpenLorebookPicker: () -> Unit,
    onNavigateToCharacterDetail: (characterId: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChatDrawerSurface(modifier = modifier) {
        ChatDrawerHeader(
            title = "工作台",
            closeIcon = Lucide.PanelRightClose,
            closeContentDescription = "关闭设置侧栏",
            onDismiss = onDismiss,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
        ) {
            item(key = "section-character-and-persona") {
                NutTavernGroupSection {
                    CharacterEntryRow(
                        character = currentCharacter,
                        onClick = {
                            val targetId = currentCharacter?.id ?: NEW_CHARACTER_PLACEHOLDER_ID
                            onNavigateToCharacterDetail(targetId)
                        },
                    )
                    NutTavernGroupDivider()
                    PersonaEntryRow(
                        persona = currentPersona,
                        onClick = onOpenPersonaPicker,
                    )
                }
            }

            item(key = "section-prompts") {
                NutTavernGroupSection {
                    PresetEntryRow(
                        preset = currentPreset,
                        onClick = onOpenPresetPicker,
                    )
                    NutTavernGroupDivider()
                    RegexEntryRow(
                        counts = regexCounts,
                        onClick = onOpenRegexPicker,
                    )
                    NutTavernGroupDivider()
                    LorebookEntryRow(
                        counts = lorebookCounts,
                        onClick = onOpenLorebookPicker,
                    )
                }
            }

            item(key = "section-tables") {
                NutTavernGroupSection {
                    DrawerStaticItems(
                        items = listOf(
                            DrawerItem("变量表格", "会话变量与宏值", Lucide.Table) {
                                onOpenUnavailableFeature("变量表格")
                            },
                            DrawerItem("记忆表格", "长期记忆与结构化记录", Lucide.TableOfContents) {
                                onOpenUnavailableFeature("记忆表格")
                            },
                            DrawerItem("表格设置", "变量、记忆与表格行为", Lucide.TableProperties) {
                                onOpenUnavailableFeature("表格设置")
                            },
                        ),
                    )
                }
            }
        }
    }
}

/**
 * "角色卡片"动态行。绑定 / 未绑定两态共用一个 [NutTavernIconRow] leading-slot 重载,
 * 保证与同分组里的"用户身份"行在视觉上严格等高。
 */
@Composable
private fun CharacterEntryRow(
    character: Character?,
    onClick: () -> Unit,
) {
    if (character == null) {
        NutTavernIconRow(
            icon = Lucide.BookUser,
            title = "未选择角色",
            subtitle = "点击新建角色卡",
            showTrailingChevron = true,
            onClick = onClick,
        )
        return
    }

    NutTavernIconRow(
        leading = {
            CharacterAvatarPlaceholder(
                modifier = Modifier.size(NutTavernGroupTokens.IconRowLeadingIconSize),
            )
        },
        title = character.name.ifBlank { "未命名角色" },
        subtitle = characterSubtitleDisplay(character),
        showTrailingChevron = true,
        onClick = onClick,
    )
}

/**
 * "用户身份"动态行。null = 当前会话"无身份",显示伪卡占位文案;非 null 时
 * 走 [personaPrimaryDisplay] / [personaSubtitleDisplay] 给出标题 + 描述摘要。
 */
@Composable
private fun PersonaEntryRow(
    persona: UserPersona?,
    onClick: () -> Unit,
) {
    val primary: String
    val subtitle: String
    if (persona == null) {
        primary = personaPrimaryDisplay(UserPersona.None)
        subtitle = personaSubtitleDisplay(UserPersona.None)
    } else {
        primary = personaPrimaryDisplay(persona)
        subtitle = personaSubtitleDisplay(persona)
    }
    NutTavernIconRow(
        icon = Lucide.UserRound,
        title = primary,
        subtitle = subtitle,
        showTrailingChevron = true,
        onClick = onClick,
    )
}

/**
 * "预设"动态行。仓库永远兜底默认预设,但首次启动 / 默认未加载完时仍可能为 null,
 * 此时给"默认预设 · 加载中"占位,避免空洞。
 */
@Composable
private fun PresetEntryRow(
    preset: Preset?,
    onClick: () -> Unit,
) {
    if (preset == null) {
        NutTavernIconRow(
            icon = Lucide.SlidersHorizontal,
            title = "默认预设",
            subtitle = "加载中",
            showTrailingChevron = true,
            onClick = onClick,
        )
        return
    }

    NutTavernIconRow(
        leading = {
            PresetAvatarPlaceholder(
                modifier = Modifier.size(NutTavernGroupTokens.IconRowLeadingIconSize),
            )
        },
        title = preset.name.ifBlank { "未命名预设" },
        subtitle = presetSubtitleDisplay(preset),
        showTrailingChevron = true,
        onClick = onClick,
    )
}

/**
 * "我的正则"动态行。显示总数 / 启用数。
 */
@Composable
private fun RegexEntryRow(
    counts: Pair<Int, Int>,
    onClick: () -> Unit,
) {
    val (total, enabled) = counts
    val subtitle = when {
        total == 0 -> "暂无规则"
        else -> "共 $total 条,已启用 $enabled 条"
    }
    NutTavernIconRow(
        icon = Lucide.Regex,
        title = "我的正则",
        subtitle = subtitle,
        showTrailingChevron = true,
        onClick = onClick,
    )
}

/**
 * "世界书"动态行。显示总数 / 选中数。
 */
@Composable
private fun LorebookEntryRow(
    counts: Pair<Int, Int>,
    onClick: () -> Unit,
) {
    val (total, selected) = counts
    val subtitle = when {
        total == 0 -> "暂无世界书"
        selected == 0 -> "共 $total 本,未选中"
        else -> "共 $total 本,已选中 $selected 本"
    }
    NutTavernIconRow(
        icon = Lucide.BookOpenText,
        title = "世界书",
        subtitle = subtitle,
        showTrailingChevron = true,
        onClick = onClick,
    )
}

@Composable
private fun DrawerStaticItems(items: List<DrawerItem>) {
    items.forEachIndexed { index, item ->
        NutTavernIconRow(
            icon = item.icon,
            title = item.title,
            subtitle = item.subtitle,
            showTrailingChevron = true,
            onClick = item.onClick,
        )
        if (index < items.lastIndex) NutTavernGroupDivider()
    }
}

private data class DrawerItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)
