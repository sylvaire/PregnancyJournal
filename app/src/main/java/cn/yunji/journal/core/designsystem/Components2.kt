package cn.yunji.journal.core.designsystem

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 全站统一的次要按钮规格。
 *
 * 原来 10 处 `OutlinedButton` 各写一套：4 处显式 `heightIn(min = 56.dp)`、6 处不设高度（M3 默认 40dp），
 * 而主按钮 `JournalButton` 是 52dp —— 出现了「次按钮比主按钮更高」的倒挂。
 * 这里固定 52dp / 26dp 圆角 / outline 描边，与主按钮同高同圆角，只差填充。
 *
 * 用于「选择药品」「选择日期」「展开范围」这类**与主按钮并列**的次要动作。
 * 行内小操作（如「删除」「重试」）请用 `JournalTextAction`。
 */
@Composable fun JournalSecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    onClick: () -> Unit) {
    OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = 52.dp), enabled,
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 文本动作（行内链接 / 对话框按钮 / 次要操作）。
 *
 * 全站 167 处 `TextButton` 承担了 4 种语义却没统一规格。这里收敛出两种：
 * - `danger = true`：错误色，用于「删除」「彻底删除」「清除连接」等不可逆动作；
 * - `danger = false`：主色，用于「重试」「刷新」「查看全部」等。
 *
 * 统一 `heightIn(min = 48.dp)` 保证触摸目标达标（此前 `JournalApp` 的「查看备份状态」只有 18dp 高）。
 */
@Composable fun JournalTextAction(text: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    danger: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge,
            color = if (danger) MaterialTheme.colorScheme.error
            else if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
    }
}

/**
 * 小节标题（`titleMedium` 角色）。
 *
 * 业务代码里 34 处直接写 `Text(..., style = titleMedium)`，散落在 14 个文件。
 * 统一成组件后，字号档位只在这里定义一次，避免再出现「同一页面 20sp/16sp 混排」。
 */
@Composable fun JournalSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground)
}

/**
 * 统一的表单字段（对应 Ardot `孕迹/Field`，56 dp 高）。
 *
 * 设计稿 `7:2485 孕迹/Field` 早已定义 56dp 高，但代码侧 48 处 `OutlinedTextField` 全是裸调用、
 * 高度各写各的。这里按设计稿固定 56dp / 16dp 圆角，错误态走主题 error 色。
 */
@Composable fun JournalField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, isError: Boolean = false,
    singleLine: Boolean = true, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    placeholder: String? = null, supportingText: String? = null, readOnly: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions(),
    visualTransformation: VisualTransformation = VisualTransformation.None) {
    OutlinedTextField(value, onValueChange, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        trailingIcon = trailingIcon,
        isError = isError, singleLine = singleLine, minLines = minLines, maxLines = maxLines, readOnly = readOnly,
        shape = RoundedCornerShape(16.dp), keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation)
}

/**
 * 统一的勾选框。
 *
 * 3 处裸 `Checkbox` 用的是 Material3 默认样式（2dp 近直角），与全站 12/16/20/26dp 圆角体系肉眼可见断裂。
 * M3 的 `Checkbox` 没有 `shape` 参数，只能靠 `Modifier.clip` 把方角裁成 6dp 圆角。
 * 整行可点（含文字），保证 48dp 触摸目标。
 */
@Composable fun JournalCheckboxRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        JournalCheckbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
    }
}

/** 仅勾选框本体（需自行布局标签时用）。 */
@Composable fun JournalCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    Checkbox(checked, onCheckedChange, modifier.clip(RoundedCornerShape(6.dp)), enabled = enabled,
        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary,
            uncheckedColor = MaterialTheme.colorScheme.outline, checkmarkColor = MaterialTheme.colorScheme.onPrimary))
}

/**
 * 统一的加载态。
 *
 * 22 处裸 `CircularProgressIndicator` 分布在 8 个文件，有的居中、有的左对齐、有的带文案有的不带。
 * 这里给出「居中 + 可选文案」的标准形态。
 */
@Composable fun JournalLoading(modifier: Modifier = Modifier, text: String? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        if (text != null) Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 统一的错误提示。
 *
 * 29 处裸 `Text(..., color = colorScheme.error)` 有的在卡片里、有的贴边、有的带重试、有的不带。
 * 这里统一为「errorContainer 圆角块 + error 文字 + 可选重试动作」。
 */
@Composable fun JournalError(message: String, modifier: Modifier = Modifier,
    retry: (() -> Unit)? = null, retryLabel: String = "重试") {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer)
            if (retry != null) JournalTextAction(retryLabel, danger = true, onClick = retry)
        }
    }
}

/**
 * 统一的分隔线。
 *
 * 2 处裸 `HorizontalDivider` 用 M3 默认 1dp + 默认色，与全站 outline 色不一致。
 */
@Composable fun JournalDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
}

/**
 * 全屏二级页顶栏（用于 `Dialog(usePlatformDefaultWidth = false)` 的全屏容器）。
 *
 * `DocumentPreview` 与 `ReminderSettingsScreen` 各自手写了一套顶栏，且两者互不相同
 * （前者 `titleLarge` + 「返回」文字按钮，后者 `headlineMedium` 但无返回键）。
 * 这里抽出与 `JournalPageHeader` 完全一致的视觉：28sp 标题 + 12sp 副标题 + 左侧旋转 chevron。
 */
@Composable fun JournalFullScreenHeader(title: String, subtitle: String? = null,
    onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
        .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        // 全屏 Dialog 会叠在 JournalPage 之上，两者顶栏若都用 navigate-up 会让语义树出现两个
        // 同名返回键；这里用 fullscreen-navigate-up 区分，页面顶栏仍是 navigate-up。
        if (onBack != null) IconButton(onBack, Modifier.testTag("fullscreen-navigate-up")) {
            Box(Modifier.rotate(if (LocalLayoutDirection.current == LayoutDirection.Ltr) 180f else 0f)) {
                DesignIcon("cfd63.svg", description = "返回上一级")
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/**
 * 可点卡片的统一按压反馈。
 *
 * 全站可点行（RecordRow、TimelineRecordRow、HomeQuickAction、杏色备注卡）此前只有 Material 默认水波纹，
 * 没有任何缩放/提亮反馈，在深色背景或软绿卡片上水波纹几乎看不见，点下去像没反应。
 * 这里把「按下缩到 0.97 + 抬起回弹」封装成修饰符，配合 Modifier.clickable 一起使用。
 *
 * 用 spring 而非 tween：弹簧在快速连点时不会排队，手感更接近系统。
 */
fun Modifier.journalPressable(interactionSource: MutableInteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale")
    this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * 详情页的键值行（`标签  值` 两端对齐）。
 *
 * 「原始记录详情」「恢复预览」「云端清理统计」等页面此前各自手写
 * `Row(SpaceBetween){ Text(label); Text(value) }`，标签与数值的字重、颜色都不统一，
 * 长数值还会把标签挤成两行。这里统一：
 * - 标签用 `bodyMedium` + onSurfaceVariant，权重 1，最多 1 行省略；
 * - 值用 `bodyMedium` + onSurface，右对齐，不换行；
 * - 值过长时右对齐会挤压标签，故给标签 `weight(1f)` 并允许省略。
 */
@Composable fun JournalKeyValue(label: String, value: String, modifier: Modifier = Modifier,
    emphasize: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End)
    }
}
