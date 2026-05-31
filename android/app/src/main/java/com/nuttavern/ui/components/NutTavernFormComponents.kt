package com.nuttavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2

/**
 * 卡内"标签 + 输入"行。用于编辑页表单字段(用户身份 / 角色卡 / 预设等)。
 *
 * 多行字段(`singleLine = false`)的高度由 [NutTavernFormTokens.MultilineFieldMaxHeight]
 * 限制,超出后**栏内垂直滚动**且不传滚动事件到外层 LazyColumn(`nestedScroll` 拦截 onPostScroll)。
 * 内部滚动时左侧浮一根指示条;静止 / 没有可滚动内容时指示条隐藏。
 *
 * - 长内容编辑入口由 [onOpenFullScreen] 控制,非 null 时在输入框尾部显示全屏按钮。
 *   推荐**所有可能溢出栏高度的字段都挂全屏按钮**,栏内滚动只是兜底。
 * - 也可通过 [trailingAction] 自定义尾部内容(优先级高于 [onOpenFullScreen])。
 */
@Composable
fun NutTavernLabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    isError: Boolean = false,
    readOnly: Boolean = false,
    supportingText: String? = null,
    onOpenFullScreen: (() -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    val trailing: (@Composable () -> Unit)? = trailingAction
        ?: onOpenFullScreen?.let { action ->
            {
                IconButton(onClick = action) {
                    Icon(Lucide.Maximize2, "全屏编辑$label")
                }
            }
        }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (singleLine) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                placeholder = placeholder?.let { { Text(it) } },
                singleLine = true,
                isError = isError,
                readOnly = readOnly,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = trailing,
            )
        } else {
            ScrollableMultilineField(
                label = label,
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                minLines = minLines,
                isError = isError,
                trailingIcon = trailing,
            )
        }
        if (!supportingText.isNullOrBlank()) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 多行字段实现。OutlinedTextField 不暴露内部 scrollState,这里**关闭它的内部滚动**
 * (不设 maxLines = 让字段按内容高度自然增高),把滚动交给我们包裹的 [verticalScroll]。
 *
 * 这样既能拿到 [androidx.compose.foundation.ScrollState] 给左侧指示条用,也能挂
 * [NestedScrollConnection] 在撞顶 / 撞底时阻止外层 LazyColumn 跟着动。
 *
 * BringIntoView 链路保留:OutlinedTextField 的 cursor 移动会触发 bringIntoView,
 * 由我们外层的 verticalScroll 处理,IME 跟随光标行为不会丢。
 */
@Composable
private fun ScrollableMultilineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
    minLines: Int,
    isError: Boolean,
    trailingIcon: (@Composable () -> Unit)?,
) {
    val scrollState = rememberScrollState()
    // onPostScroll 返回 available:把 verticalScroll 没消化的全部"吃掉",
    // 阻止滚动事件冒泡到外层 LazyColumn。撞顶撞底时 available > 0 会被吞;
    // 中段滚动时 verticalScroll 自己消化完,available ≈ 0,本 connection 等于无操作。
    val blockParentScroll = remember { BlockParentScrollConnection() }

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = NutTavernFormTokens.MultilineFieldMaxHeight)
                .nestedScroll(blockParentScroll)
                .verticalScroll(scrollState),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                placeholder = placeholder?.let { { Text(it) } },
                minLines = minLines,
                isError = isError,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = trailingIcon,
            )
        }
        VerticalScrollIndicator(
            scrollPosition = scrollState.value.toFloat(),
            scrollMax = scrollState.maxValue.toFloat(),
            modifier = Modifier
                .matchParentSize()
                .padding(start = 4.dp, top = 12.dp, bottom = 12.dp),
        )
    }
}

/**
 * 文本框左侧的滚动指示条。仅在 [scrollMax] > 0(确实有可滚动内容)时渲染。
 *
 * thumb 高度按 `viewport / contentTotal` 比例计算,且不少于
 * [NutTavernFormTokens.ScrollIndicatorMinThumbHeight] 以保证可见。
 */
@Composable
private fun VerticalScrollIndicator(
    scrollPosition: Float,
    scrollMax: Float,
    modifier: Modifier = Modifier,
) {
    if (scrollMax <= 0f) return
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier.width(NutTavernFormTokens.ScrollIndicatorWidth),
    ) {
        val trackPx = with(density) { maxHeight.toPx() }
        if (trackPx <= 0f) return@BoxWithConstraints

        val totalContentPx = trackPx + scrollMax
        val minThumbPx = with(density) { NutTavernFormTokens.ScrollIndicatorMinThumbHeight.toPx() }
        val thumbHeightPx = ((trackPx * trackPx) / totalContentPx).coerceAtLeast(minThumbPx)
        val maxOffsetPx = (trackPx - thumbHeightPx).coerceAtLeast(0f)
        val offsetPx = (scrollPosition / scrollMax) * maxOffsetPx

        Box(
            modifier = Modifier
                .offset { IntOffset(0, offsetPx.toInt()) }
                .width(NutTavernFormTokens.ScrollIndicatorWidth)
                .height(with(density) { thumbHeightPx.toDp() })
                .background(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(percent = 50),
                ),
        )
    }
}

/**
 * 把 verticalScroll 没消化的滚动量全部吞掉,阻止外层 LazyColumn 跟着动。
 *
 * onPostScroll 在子项(verticalScroll)处理完后调用:
 * - 中段滚动:verticalScroll 自己消化,available ≈ 0,return 0(无操作);
 * - 撞顶 / 撞底:verticalScroll 没法再滚,available > 0,return available
 *   (告诉框架"已被本 connection 消费",外层 LazyColumn 看到 0 不滚)。
 */
private class BlockParentScrollConnection : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = available
}

/**
 * 表单组件的尺寸 token。集中在这里,避免业务文件 hard-code。
 */
object NutTavernFormTokens {
    /** 多行 LabeledTextField 的最大可见高度,超出后栏内滚动。 */
    val MultilineFieldMaxHeight = 220.dp

    /** 滚动指示条厚度。 */
    val ScrollIndicatorWidth = 3.dp

    /** 滚动指示条 thumb 最小高度,避免在内容极长时缩成不可见的小点。 */
    val ScrollIndicatorMinThumbHeight = 24.dp
}

/**
 * 可折叠区域的标题行。点击切换展开/收起,右侧 chevron 指示当前状态。
 *
 * 用于编辑页"高级字段"折叠区(用户身份 / 角色卡 / 预设等)。
 */
@Composable
fun NutTavernExpandableHeader(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = if (expanded) "收起$title" else "展开$title",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 数值输入字段。修复"清空后被旧值覆盖"和"输入中间态(`1.` / 负号)被吞"的反模式。
 *
 * # 为什么独立成组件
 *
 * 业务里大量出现 `onValueChange = { raw -> raw.toDoubleOrNull()?.let(onValueChange) }`:
 * 用户按 backspace 删到空 → `raw=""` → `toDoubleOrNull()=null` → `?.let` 吞掉 → UI 被旧值
 * 立刻覆盖回去 → 用户**永远删不掉中间字符也无法清空**。
 *
 * 修法:**本地 rawText 缓存**用户键入,**只在 rawText 合法且在范围内**时才回写 [value]。
 * rawText 非法 / 超范围 / 空时也允许停留,通过 `supportingText` 标红提示,但不写回。
 *
 * # API
 *
 * - [value]:当前已提交的数值;null 表示尚未填写(配合 [nullable]=true 才生效)。
 * - [onValueChange]:rawText 合法且在范围内时回调。**只在数值真实改变时调用**。
 * - [parse] / [format]:Int / Long / Double 各自的 parse / 显示策略。三个工厂方法
 *   [intField] / [longField] / [doubleField] 直接构造,业务侧不用关心。
 * - [min] / [max]:范围闭区间;null 表示该方向无约束。
 * - [nullable]:是否允许"空值"作为合法状态(对应清空操作)。
 *   - true:rawText 空 → 回调 `onValueChange(null)`。
 *   - false:rawText 空 → 视为非法,标红,不写回(value 保留上次值)。
 * - [helperText]:常规提示,与错误提示互斥(有错误时显示错误,否则显示 helperText)。
 *
 * # 与 NutTavernLabeledTextField 的关系
 *
 * 不直接复用:LabeledTextField 是字符串字段,onValueChange 类型是 (String) -> Unit;
 * 这里需要 (T?) -> Unit 数值类型。共享视觉 token(padding / vertical spacing / supportingText 风格)。
 */
@Composable
fun <T : Number> NutTavernNumericField(
    label: String,
    value: T?,
    onValueChange: (T?) -> Unit,
    parser: NumericParser<T>,
    modifier: Modifier = Modifier,
    min: T? = null,
    max: T? = null,
    nullable: Boolean = false,
    enabled: Boolean = true,
    helperText: String? = null,
    placeholder: String? = null,
) {
    // 本地 rawText 缓存。**初始 key 用 value**,这样外部把 value 重置(如取消 / 切预设)
    // 时本地缓存也跟着 reset;但用户键入过程中改变 rawText 不会触发 key 变更
    // (key 等于 value,value 只在合法且改变时才回写)。
    var rawText by rememberSaveable(value) {
        mutableStateOf(value?.let(parser::format) ?: "")
    }

    val validation = remember(rawText, min, max, nullable) {
        validateNumeric(rawText, parser, min, max, nullable)
    }

    // 合法且数值真实改变 → 回写 value。中间态 / 非法 / 超范围 → 不写回,rawText 保留。
    // 禁用态不回写:此时字段只读展示,避免本地 rawText 与外部 value 互相打架。
    LaunchedEffect(validation, enabled) {
        if (!enabled) return@LaunchedEffect
        when (validation) {
            is NumericValidation.Valid -> {
                @Suppress("UNCHECKED_CAST")
                if (validation.value != value) onValueChange(validation.value as T?)
            }
            else -> Unit
        }
    }

    val errorMessage = (validation as? NumericValidation.Invalid)?.message.takeIf { enabled }
    val isError = errorMessage != null
    val supportingMessage = errorMessage ?: helperText

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = rawText,
            onValueChange = { rawText = it },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            enabled = enabled,
            isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = parser.keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!supportingMessage.isNullOrBlank()) {
            Text(
                text = supportingMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 数值字段类型策略:三种(Int / Long / Double)各自的 parse / 显示 / 键盘类型。
 *
 * 用 sealed interface 而非 enum,parse 返回 T 需要每种类型独立签名。
 */
sealed interface NumericParser<T : Number> {
    val keyboardType: KeyboardType
    fun parse(raw: String): T?
    fun format(value: T): String
    fun compareTo(a: T, b: T): Int

    object IntParser : NumericParser<Int> {
        override val keyboardType = KeyboardType.Number
        override fun parse(raw: String): Int? = raw.toIntOrNull()
        override fun format(value: Int): String = value.toString()
        override fun compareTo(a: Int, b: Int): Int = a.compareTo(b)
    }

    object LongParser : NumericParser<Long> {
        override val keyboardType = KeyboardType.Number
        override fun parse(raw: String): Long? = raw.toLongOrNull()
        override fun format(value: Long): String = value.toString()
        override fun compareTo(a: Long, b: Long): Int = a.compareTo(b)
    }

    object DoubleParser : NumericParser<Double> {
        override val keyboardType = KeyboardType.Decimal
        override fun parse(raw: String): Double? = raw.toDoubleOrNull()
        override fun format(value: Double): String {
            // 整数值显示为整数,小数值保留有效位数。"1.0" → "1","1.50" → "1.5"。
            val asLong = value.toLong()
            return if (value == asLong.toDouble()) asLong.toString()
            else value.toString()
        }
        override fun compareTo(a: Double, b: Double): Int = a.compareTo(b)
    }
}

private sealed interface NumericValidation<out T> {
    object Empty : NumericValidation<Nothing>
    data class Valid<T>(val value: T?) : NumericValidation<T>
    data class Invalid(val message: String) : NumericValidation<Nothing>
}

private fun <T : Number> validateNumeric(
    rawText: String,
    parser: NumericParser<T>,
    min: T?,
    max: T?,
    nullable: Boolean,
): NumericValidation<T> {
    val trimmed = rawText.trim()
    if (trimmed.isEmpty()) {
        return if (nullable) NumericValidation.Valid(null)
        else NumericValidation.Invalid("不能为空")
    }
    val parsed = parser.parse(trimmed)
        ?: return NumericValidation.Invalid("请输入数字")
    if (min != null && parser.compareTo(parsed, min) < 0) {
        return NumericValidation.Invalid("不小于 ${parser.format(min)}")
    }
    if (max != null && parser.compareTo(parsed, max) > 0) {
        return NumericValidation.Invalid("不大于 ${parser.format(max)}")
    }
    return NumericValidation.Valid(parsed)
}

// ── 开关行 ──

/**
 * 表单内开关行。整行可点击切换,用于编辑页布尔字段。
 *
 * 统一替代各页面 private SwitchRow 副本。
 */
@Composable
fun NutTavernSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = { onCheckedChange(!checked) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

// ── 枚举选择行 ──

/**
 * 枚举选择行。点击弹出 ModalBottomSheet 单选。
 *
 * 泛型版本:options 为 `List<Pair<T, String>>`,T 是枚举值,String 是显示文本。
 * 统一替代各页面 private EnumRow 副本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> NutTavernEnumRow(
    label: String,
    value: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionDescriptions: Map<T, String> = emptyMap(),
) {
    var showSheet by remember { mutableStateOf(false) }
    val displayValue = options.firstOrNull { it.first == value }?.second ?: value.toString()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = { showSheet = true },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NutTavernSheetTitle(title = label)
                options.forEach { (optionValue, optionLabel) ->
                    NutTavernSelectableRow(
                        title = optionLabel,
                        subtitle = optionDescriptions[optionValue],
                        selected = optionValue == value,
                        onClick = { onSelect(optionValue); showSheet = false },
                    )
                }
                Spacer(Modifier.padding(bottom = 16.dp))
            }
        }
    }
}
