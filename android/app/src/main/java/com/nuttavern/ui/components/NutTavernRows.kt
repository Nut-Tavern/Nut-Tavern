package com.nuttavern.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun NutTavernSelectableRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val primaryTextColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val minHeight = if (subtitle.isNullOrBlank()) {
        NutTavernUiTokens.SelectableRowSingleLineMinHeight
    } else {
        NutTavernUiTokens.SelectableRowTwoLineMinHeight
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight, max = NutTavernUiTokens.SelectableRowMaxHeight),
        shape = RoundedCornerShape(NutTavernUiTokens.SelectableConfigRowCorner),
        color = containerColor,
        contentColor = primaryTextColor,
        enabled = enabled,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = NutTavernUiTokens.SelectableConfigRowHorizontalPadding,
                    vertical = NutTavernUiTokens.SelectableConfigRowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NutTavernUiTokens.SelectableConfigContentSpacing),
        ) {
            if (leadingContent != null) {
                Box(
                    modifier = Modifier.size(NutTavernUiTokens.SelectableConfigAvatarSize),
                    contentAlignment = Alignment.Center,
                ) {
                    leadingContent()
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NutTavernUiTokens.SelectableConfigTextSpacing),
            ) {
                Text(
                    text = title,
                    style = if (subtitle.isNullOrBlank()) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                    color = primaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailingContent != null) {
                Box(
                    modifier = Modifier.size(NutTavernUiTokens.SelectableConfigTrailingButtonSize),
                    contentAlignment = Alignment.Center,
                ) {
                    trailingContent()
                }
            }
        }
    }
}

@Composable
fun NutTavernInputActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(NutTavernUiTokens.InputActionButtonSize),
        enabled = enabled,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(NutTavernUiTokens.InputActionIconSize),
            )
        }
    }
}

@Composable
fun NutTavernInputPrimaryButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor = when {
        destructive -> MaterialTheme.colorScheme.error
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        destructive -> MaterialTheme.colorScheme.onError
        enabled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NutTavernAlphaTokens.Disabled)
    }

    Box(
        modifier = modifier
            .size(NutTavernUiTokens.InputPrimaryButtonTouchSize)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled || destructive,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .size(NutTavernUiTokens.InputPrimaryButtonVisualSize)
                .clip(CircleShape)
                .indication(interactionSource, LocalIndication.current),
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(NutTavernUiTokens.InputPrimaryButtonIconSize),
                )
            }
        }
    }
}

@Composable
fun NutTavernInputToolbarButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val visualColor = if (selected) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        Color.Transparent
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NutTavernAlphaTokens.Disabled)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .size(NutTavernUiTokens.InputToolbarButtonTouchSize)
            .clip(RoundedCornerShape(NutTavernUiTokens.InputToolbarButtonCorner))
            .semantics {
                this.contentDescription = contentDescription
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .size(NutTavernUiTokens.InputToolbarButtonVisualSize)
                .clip(RoundedCornerShape(NutTavernUiTokens.InputToolbarButtonCorner))
                .indication(interactionSource, LocalIndication.current),
            shape = RoundedCornerShape(NutTavernUiTokens.InputToolbarButtonCorner),
            color = visualColor,
            contentColor = contentColor,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (content != null) {
                    content()
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        modifier = Modifier.size(NutTavernUiTokens.InputToolbarIconSize),
                    )
                }
            }
        }
    }
}

@Composable
fun NutTavernMessageActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(NutTavernUiTokens.MessageActionButtonSize),
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NutTavernAlphaTokens.Disabled),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(NutTavernUiTokens.MessageActionIconSize),
            )
        }
    }
}
