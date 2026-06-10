package com.nuttavern.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.Lucide
import com.nuttavern.data.model.Message
import com.nuttavern.ui.components.EmptyState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 聊天消息列表的滚动哲学(参考 rikkahub):
 *
 * 1. **末尾哨兵 spacer**:LazyColumn 最后一项是一个 5dp 高的 [Spacer]([ScrollBottomKey])。
 *    它扮演"贴底锚点"角色 — 自动跟随时永远把它请到 viewport 内,而不是把"最后一条消息"
 *    顶到顶部。这样长 assistant 回复 / markdown 长块也能正确贴底显示。
 *
 * 2. **isAtBottom 走像素而不是 index**:对比 `lastVisibleItem.offset + size` 与
 *    `viewportEndOffset - bottomInsetPx`。基于 index 的判断在末尾 item 是 spacer 时
 *    需要写很多边界,基于像素干净。
 *
 * 3. **流式跟随触发**:`snapshotFlow { visibleItemsInfo }` 在每次 measure 后产出。
 *    满足"当前不在拖动 + 还在 loading + 此刻贴底像素判定通过" 时,
 *    `requestScrollToItem(lastIndex + N)` 故意给一个超界 index,LazyListState 会
 *    截断到末尾,自然停在 spacer 上。
 *
 * 4. **键盘随动用 scrollBy**:把"键盘高度变化的 px"原样应用到 LazyListState 上,
 *    与 IME 动画同帧推进。这条路径不依赖 index、不依赖 isAtBottom,适合任何场景。
 *
 * 5. **不用 imePadding / 不自定义 contentWindowInsets**:Scaffold.bottomBar 持有
 *    Composer,Activity adjustResize 时 bottomBar 自动跟键盘上移,从而让
 *    `innerPadding.bottom` 正确反映"键盘 + bottomBar"占用的高度,作为 LazyColumn 的
 *    contentPadding 底边。
 */
internal const val ScrollBottomKey = "ScrollBottomKey"

/**
 * `requestScrollToItem` 给的目标 index 的 overshoot 量。LazyListState 会 coerce 到 lastIndex,
 * 这里取一个保守值是为了将来在末尾追加多个哨兵(如分隔条 / 推荐区)时,overshoot 仍 >= 哨兵数。
 */
private const val SCROLL_TO_BOTTOM_OVERSHOOT = 5

/**
 * "回到最新"FAB 的显示阈值:距底超过这么多项才显示。
 * 用固定项数而不是 visibleCount,避免阈值随消息长度漂移(长 markdown 占满屏 visibleCount=1
 * 会让阈值崩到 1,刚上滑一项就出 FAB)。
 */
private const val SCROLL_TO_LATEST_FAB_THRESHOLD = 3

/**
 * isAtBottom 的像素容忍值,避免 measure 子像素抖动让自动跟随频繁中断。
 * dp 而不是 px:在 xxxhdpi 上 8px 只有 ~2.7dp,容忍区被流式 measure 误差吃掉。
 */
private val IS_AT_BOTTOM_TOLERANCE_DP = 4.dp

@Composable
internal fun ChatMessageList(
    messages: List<Message>,
    streamingContent: String,
    streamingReasoningContent: String,
    streamingReasoningDurationMillis: Long,
    currentToolActivity: String?,
    shouldShowStreaming: Boolean,
    conversationId: String,
    innerPadding: PaddingValues,
    onCopyMessage: (Message) -> Unit,
    onEditMessage: (Message) -> Unit,
    onRegenerateMessage: (Message) -> Unit,
    onDeleteMessage: (Message) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty() && !shouldShowStreaming) {
        Box(
            modifier = modifier.padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                title = "开始新对话",
                description = "发送一条消息，与助手开始交流",
            )
        }
        return
    }

    // 每个会话独立 LazyListState,避免切换会话后滚动位置错位。
    key(conversationId) {
        ChatMessageListContent(
            messages = messages,
            streamingContent = streamingContent,
            streamingReasoningContent = streamingReasoningContent,
            streamingReasoningDurationMillis = streamingReasoningDurationMillis,
            currentToolActivity = currentToolActivity,
            shouldShowStreaming = shouldShowStreaming,
            innerPadding = innerPadding,
            onCopyMessage = onCopyMessage,
            onEditMessage = onEditMessage,
            onRegenerateMessage = onRegenerateMessage,
            onDeleteMessage = onDeleteMessage,
            modifier = modifier,
        )
    }
}

@Composable
private fun ChatMessageListContent(
    messages: List<Message>,
    streamingContent: String,
    streamingReasoningContent: String,
    streamingReasoningDurationMillis: Long,
    currentToolActivity: String?,
    shouldShowStreaming: Boolean,
    innerPadding: PaddingValues,
    onCopyMessage: (Message) -> Unit,
    onEditMessage: (Message) -> Unit,
    onRegenerateMessage: (Message) -> Unit,
    onDeleteMessage: (Message) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val maxMessageWidth = (screenWidthDp * 0.78).dp

    val totalItemCount = messages.size + if (shouldShowStreaming) 1 else 0

    // 把 viewport 像素底边减去 bottomInset,得到"实际可视底边"。
    fun List<LazyListItemInfo>.isAtBottom(): Boolean {
        val lastItem = lastOrNull() ?: return true
        val bottomInsetPx = with(density) { innerPadding.calculateBottomPadding().toPx() }
        val tolerancePx = with(density) { IS_AT_BOTTOM_TOLERANCE_DP.toPx() }.roundToInt()
        val lastBottom = lastItem.offset + lastItem.size
        val viewBottom = listState.layoutInfo.viewportEndOffset - bottomInsetPx.roundToInt()
        return lastBottom <= viewBottom + tolerancePx
    }

    val showScrollToLatest by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val totalItems = info.totalItemsCount
            if (totalItems == 0) return@derivedStateOf false
            val lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            // 用固定项数阈值,避免阈值随 viewport 内可见项数(取决于消息长度)漂移:
            // 长 markdown 占满屏 visibleCount=1 → 上滚 1 项就出 FAB;
            // 短消息 visibleCount=8 → 要上滚 8 项才出 FAB。
            // 固定 3 项内不显示,与 isAtBottom 的像素口径分开使用,职责清晰。
            lastVisibleIndex < totalItems - 1 - SCROLL_TO_LATEST_FAB_THRESHOLD
        }
    }

    // 自动跟随最新:仅在"流式中 + 用户当前未在拖动 + 此刻贴底像素判定通过"时,
    // 把哨兵 spacer 请到 viewport 末尾。requestScrollToItem 给超界 index 让 Compose
    // 内部截断到列表末尾,等价于"贴底"。
    //
    // LaunchedEffect 的 key 不放 shouldShowStreaming:collect 内已经守护了,把它当 key
    // 会让流式开关切换时丢掉 snapshotFlow buffer 状态,纯属重启浪费。
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }.collect { visibleItemsInfo ->
            if (!shouldShowStreaming) return@collect
            if (listState.isScrollInProgress) return@collect
            if (!visibleItemsInfo.isAtBottom()) return@collect
            // 给 lastIndex + 一个小 overshoot,Compose 会 coerce 到末尾;命名常量,
            // 避免后续在末尾追加多个哨兵时 overshoot 不够,部分哨兵被顶丢。
            listState.requestScrollToItem(
                listState.layoutInfo.totalItemsCount + SCROLL_TO_BOTTOM_OVERSHOOT,
            )
        }
    }

    // 键盘抬起 / 收起时跟随:把键盘高度变化的像素增量原样 scrollBy 到列表上。
    // 这条路径不依赖 isAtBottom,也不依赖 index,与 IME 动画同帧。
    ImeLazyListAutoScroller(listState)

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            contentPadding = PaddingValues(
                top = 4.dp,
                // bottomBar 高度 + IME 高度都通过 innerPadding.bottom 喂进来。
                // 加 4.dp 视觉缓冲,与原实现一致。
                bottom = innerPadding.calculateBottomPadding() + 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(items = messages, key = { _, message -> message.id }) { _, message ->
                ChatMessageRow(
                    message = message,
                    maxMessageWidth = maxMessageWidth,
                    onCopyMessage = onCopyMessage,
                    onEditMessage = onEditMessage,
                    onRegenerateMessage = onRegenerateMessage,
                    onDeleteMessage = onDeleteMessage,
                )
            }
            if (shouldShowStreaming) {
                item(key = "streaming") {
                    StreamingMessageRow(
                        content = streamingContent,
                        reasoningContent = streamingReasoningContent,
                        reasoningDurationMillis = streamingReasoningDurationMillis,
                        currentToolActivity = currentToolActivity,
                    )
                }
            }
            // 末尾哨兵 spacer:贴底动作以它为目标,长消息也不会"对齐到消息开头"。
            item(key = ScrollBottomKey) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = showScrollToLatest,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = innerPadding.calculateBottomPadding() + 16.dp),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    if (totalItemCount == 0) return@SmallFloatingActionButton
                    coroutineScope.launch {
                        listState.animateScrollToItem(listState.layoutInfo.totalItemsCount)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(
                    imageVector = Lucide.ArrowDown,
                    contentDescription = "回到最新消息",
                )
            }
        }
    }
}

/**
 * 监听 IME 高度变化,直接把 delta 像素 scrollBy 到 [LazyListState] 上。
 *
 * 与基于 isAtBottom 的方案相比,这条路径有两个关键好处:
 * - 不依赖列表当前是否贴底:用户在中间区域时,键盘弹起也能让光标附近内容跟着上移;
 * - 不依赖 lastIndex / scrollToItem:scrollBy 是相对位移,不会出现"对齐到消息开头"的尴尬。
 *
 * 取自 rikkahub 的 ImeLazyListAutoScroller,做到与 IME 动画同帧推进。
 */
/**
 * 监听 IME 高度变化,把 delta 像素 scrollBy 到 [LazyListState] 上。
 *
 * 与基于 isAtBottom 的方案相比,这条路径有两个关键好处:
 * - 不依赖列表当前是否贴底:用户在中间区域时,键盘弹起也能让光标附近内容跟着上移;
 * - 不依赖 lastIndex / scrollToItem:scrollBy 是相对位移,不会出现"对齐到消息开头"的尴尬。
 *
 * 取自 rikkahub 的 ImeLazyListAutoScroller。两点关键设计:
 * 1. `imeHeight` 初值取**当前** ime bottom,而不是 0。会话切换时 LazyListState 被
 *    `key(conversationId)` 重建,如果 imeHeight 从 0 起,而键盘此时已经抬起,首次 emit
 *    会被当成"键盘从 0 抬到 N",误把刚切过去的会话首屏下推一屏;
 * 2. `imeHeight` 仅在 `keyboardHeight > 0` 时更新,等价于"键盘收起不动列表" — 收起时
 *    contentPadding.bottom 自动变小,LazyColumn 自己把内容向下展开,无需 scroll;
 *    若也对收起 scroll,会把列表往下抖一截、再被自然展开顶回去,有抖动。
 */
@Composable
private fun ImeLazyListAutoScroller(listState: LazyListState) {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // 初值用当前 ime bottom,不用 0。
    val initialImeHeight = ime.getBottom(density)
    var imeHeight by remember { mutableIntStateOf(initialImeHeight) }
    LaunchedEffect(Unit) {
        snapshotFlow { ime.getBottom(density) }.collect { keyboardHeight ->
            if (keyboardHeight > 0) {
                listState.scrollBy((keyboardHeight - imeHeight).toFloat())
                imeHeight = keyboardHeight
            }
        }
    }
}
