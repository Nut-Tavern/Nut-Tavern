package com.nuttavern.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Wrench
import com.nuttavern.data.model.Modality
import com.nuttavern.data.model.Model
import com.nuttavern.data.model.ModelAbility
import com.nuttavern.ui.components.NutTavernCapabilityChipColors
import com.nuttavern.ui.components.NutTavernCapabilityIconChip

/**
 * 把 [Model] 上的"模态(输入→输出)/ 能力"渲染为单行胶囊。
 *
 * 顺序:输入→输出 → 工具 → 推理
 * 调用方放在 [com.nuttavern.ui.components.NutTavernModelCard] 的 `chips` 槽里。
 *
 * 历史的"模型类型"chip(聊天 / 图像 / 嵌入)已删除:类酒馆产品线只支持 chat,
 * 显示一个永远是"聊天"的 chip 没有信息价值。
 */
@Composable
fun ModelCapabilityChips(model: Model) {
    IoModalityChip(model.inputModalities, model.outputModalities)
    AbilityChips(model.abilities)
}

/**
 * 输入→输出模态整合 chip。视觉:`T (图) → T (图)`,不带文字。
 */
@Composable
private fun IoModalityChip(
    inputModalities: List<Modality>,
    outputModalities: List<Modality>,
) {
    val (container, content) = NutTavernCapabilityChipColors.modality()
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .height(22.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ModalityIcons(inputModalities)
            Icon(
                imageVector = Lucide.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
            )
            ModalityIcons(outputModalities)
        }
    }
}

@Composable
private fun ModalityIcons(modalities: List<Modality>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (modalities.isEmpty() || Modality.TEXT in modalities) {
            Icon(
                imageVector = Lucide.Type,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
        }
        if (Modality.IMAGE in modalities) {
            Icon(
                imageVector = Lucide.Image,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun AbilityChips(abilities: List<ModelAbility>) {
    if (ModelAbility.TOOL in abilities) {
        val (container, content) = NutTavernCapabilityChipColors.tool()
        NutTavernCapabilityIconChip(
            icon = Lucide.Wrench,
            containerColor = container,
            contentColor = content,
            contentDescription = "工具",
        )
    }
    if (ModelAbility.REASONING in abilities) {
        val (container, content) = NutTavernCapabilityChipColors.reasoning()
        NutTavernCapabilityIconChip(
            icon = Lucide.Sparkles,
            containerColor = container,
            contentColor = content,
            contentDescription = "推理",
        )
    }
}
