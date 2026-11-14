package cn.yunji.journal.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.decode.SvgDecoder

/** The SVG source is bundled from the Figma export; no runtime network request is made. */
@Composable fun DesignIcon(file: String, description: String? = null, size: Dp = 24.dp, tint: Color = MaterialTheme.colorScheme.primary) {
    val context = LocalContext.current
    val request = remember(context, file) { ImageRequest.Builder(context).data("file:///android_asset/design/$file")
        .decoderFactory(SvgDecoder.Factory()).build() }
    AsyncImage(model = request, contentDescription = description, modifier = Modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

@Composable fun JournalButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    val buttonModifier = modifier.fillMaxWidth().heightIn(min = 52.dp)
    if (secondary) FilledTonalButton(onClick, buttonModifier, enabled, shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) { Text(text, style = MaterialTheme.typography.labelLarge) }
    else Button(onClick, buttonModifier, enabled, shape = RoundedCornerShape(26.dp)) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable fun JournalDangerButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick, modifier.fillMaxWidth().heightIn(min = 52.dp), enabled,
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Switch from the Figma foundations with explicit colours, so both states stay on the warm-green palette.
 *
 * Checked: primary track + white thumb. Unchecked: soft (#E7F0E6) track + muted (#5F7166) thumb + outline
 * (#DDE4DA) border. The unchecked thumb/track contrast is > 3:1, whereas the stock Material3 defaults served
 * by this theme mapped both the unchecked thumb and the track to the same outline colour (invisible thumb).
 */
@Composable fun JournalSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
            checkedIconColor = MaterialTheme.colorScheme.onPrimary,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = MaterialTheme.colorScheme.primaryContainer,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            uncheckedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledCheckedThumbColor = MaterialTheme.colorScheme.surface,
            disabledCheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledCheckedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledCheckedIconColor = MaterialTheme.colorScheme.surface,
            disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            disabledUncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledUncheckedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledUncheckedIconColor = MaterialTheme.colorScheme.surface,
        ))
}

/**
 * Filter/tag pill from the Figma foundations: 48 dp high, min 64 dp wide, 12 dp radius.
 * Selected = primary fill + onPrimary label; unselected = surface + 1 dp outline border + muted label.
 * Uses selectable semantics (role Tab) so tests can assert selection and TalkBack reports the state.
 *
 * 选中态用 160 ms 颜色过渡而非瞬切：标签常用于筛选（时间线、回收站、分类），
 * 瞬切会让「点了没反应 / 一下子变黑」的错觉，160 ms 与页面转场的 160 ms fade 同拍。
 */
@Composable fun JournalTag(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val contentAlpha = if (enabled) 1f else 0.38f
    val fill by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.12f)
        else MaterialTheme.colorScheme.surface,
        animationSpec = tween(durationMillis = 160), label = "tagFill")
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = contentAlpha)
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
        animationSpec = tween(durationMillis = 160), label = "tagContent")
    // 描边宽度也做过渡：选中时描边淡出，避免边框突然消失造成的尺寸抖动感。
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 0.dp else 1.dp,
        animationSpec = tween(durationMillis = 160), label = "tagBorder")
    Surface(
        modifier = modifier.heightIn(min = 48.dp).defaultMinSize(minWidth = 64.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = fill,
        contentColor = content,
        border = if (borderWidth > 0.dp) BorderStroke(borderWidth, MaterialTheme.colorScheme.outline.copy(alpha = contentAlpha)) else null,
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { label() }
        }
    }
}

@Composable fun JournalCard(soft: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = if (soft) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Column(
            // 卡片内容变化（如展开"操作"、显示进度）时平滑过渡，避免高度瞬间跳变。
            Modifier.fillMaxWidth().animateContentSize(tween(durationMillis = 180))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable fun RecordRow(title: String, subtitle: String, icon: String, onClick: (() -> Unit)? = null) =
    RecordRow(title, subtitle, icon, onClick, Modifier)

@Composable fun RecordRow(title: String, subtitle: String, icon: String, onClick: (() -> Unit)?, modifier: Modifier) {
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { DesignIcon(icon) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onClick != null) DesignIcon("cfd63.svg", size = 20.dp)
        }
    }
    if (onClick != null) {
        // Surface(onClick=...) 没有 onClickLabel 参数；改用 clickable 才能给读屏一个动作描述，
        // 否则 TalkBack 只念出标题、不说明点击后会进入何处。
        // 同时加统一按压反馈（缩到 0.97 回弹）：水波纹在白卡/软绿卡上几乎不可见，点下去像没反应。
        val interaction = remember { MutableInteractionSource() }
        Surface(
            modifier = modifier
                .journalPressable(interaction)
                .clickable(interactionSource = interaction, indication = null,
                    onClickLabel = title, role = Role.Button, onClick = onClick),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, content = content,
        )
    } else {
        Surface(modifier = modifier, shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface, content = content)
    }
}

@Composable fun PageHeader(title: String, subtitle: String, onSubtitleClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, modifier = if(onSubtitleClick!=null)Modifier.clickable(onClick=onSubtitleClick) else Modifier,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Header for secondary pages (JournalPage's topBar): the Figma `PageHeader` hierarchy of a
 * 28/42 Bold title plus an optional 12/18 muted subtitle, indented by the same 24 dp page margin
 * as the content. The optional back affordance keeps the `navigate-up` test tag, the mirrored
 * chevron and the "返回上一级" content description that the navigation tests click.
 *
 * The header is placed in the Scaffold topBar slot, so it consumes the same
 * [TopAppBarDefaults.windowInsets] as the previous Material3 TopAppBar and the Scaffold keeps
 * flowing the content below the measured header height.
 */
@Composable fun JournalPageHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth()
        .windowInsetsPadding(TopAppBarDefaults.windowInsets)
        .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack, Modifier.testTag("navigate-up")) {
            Box(Modifier.rotate(if (LocalLayoutDirection.current == LayoutDirection.Ltr) 180f else 0f)) {
                DesignIcon("cfd63.svg", description = "返回上一级")
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Shared empty state from the Figma pattern: soft card + semantic icon + 20 sp title + optional 12 sp muted
 * subtitle and action. Callers keep their existing copy strings and test tags unchanged.
 */
@Composable fun JournalEmptyState(title: String, subtitle: String? = null, icon: String = "86969.svg",
    action: (@Composable () -> Unit)? = null) {
    JournalCard(soft = true) {
        DesignIcon(icon)
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

