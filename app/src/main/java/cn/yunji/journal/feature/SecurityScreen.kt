package cn.yunji.journal.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.yunji.journal.core.designsystem.JournalButton
import cn.yunji.journal.core.designsystem.JournalTag
import cn.yunji.journal.core.designsystem.JournalCard
import cn.yunji.journal.core.designsystem.RecordRow
import cn.yunji.journal.core.designsystem.DesignIcon

data class SecurityUi(val enabled: Boolean,val strict: Boolean,val timeout: Int)

@Composable
fun SecuritySettingsScreen(state: SecurityUi,action: (String)->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("security-settings"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        JournalCard(soft = true) {
            Text("应用锁", style = MaterialTheme.typography.titleLarge)
            Text(if(state.enabled || state.strict) "已开启 · 离开 ${if (state.strict) 0 else state.timeout} 分钟后锁定" else "未开启",
                color = MaterialTheme.colorScheme.primary)
            Text("保护本机私密资料", style = MaterialTheme.typography.bodySmall)
        }
        JournalButton(if(state.enabled)"关闭应用锁" else "开启应用锁",enabled=!state.strict){action("TOGGLE_LOCK")}
        Text("离开应用后锁定",style=MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(0 to "立即",1 to "1分钟",5 to "5分钟").forEach {(minutes,label)->JournalTag(state.timeout==minutes,{action("TIMEOUT_$minutes")},{Text(label)},enabled=!state.strict)}
        }
        RecordRow("验证方式", "使用系统生物识别或设备凭据解锁", "86969.svg")
        RecordRow("严格模式", if(state.strict) "已开启 · 每次锁定后重新认证" else "当前关闭 · 可提高后台保护强度", "86969.svg")
        JournalButton(if(state.strict)"关闭严格模式" else "开启严格模式",secondary=true){action("TOGGLE_STRICT")}
        JournalButton("立即锁定",enabled=state.enabled || state.strict){action("LOCK_NOW")}
        RecordRow("通知默认隐藏敏感内容", "锁定会关闭原件预览并清理私有预览缓存", "86969.svg")
        Text(if(state.strict)"严格模式每次锁定后清除密钥会话，后台导入、提醒重建和备份可能延期。" else "普通模式允许设备首次解锁后的后台工作；严格模式改用每次需要系统认证的密钥封装。",style=MaterialTheme.typography.bodySmall)
        Text("严格模式始终在离开应用后锁定。设备密钥失效时保留加密原件；恢复信息尚未交付前，请勿清除应用数据。",style=MaterialTheme.typography.bodySmall)
        Text("通知动作需认证并确认后才写入。锁定方式依赖手机系统凭据。", style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun UnlockScreen(busy: Boolean,error: String?,unlock: ()->Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("vault-locked"),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("孕迹",style=MaterialTheme.typography.headlineMedium)
        Text("应用已锁定", style = MaterialTheme.typography.bodySmall)
        JournalCard(soft = true) {
            DesignIcon("86969.svg", size = 40.dp)
            Text("资料已锁定", style = MaterialTheme.typography.headlineMedium)
            Text("请先验证身份", style = MaterialTheme.typography.headlineMedium)
            Text("用手机屏幕锁或生物识别解锁，继续查看本机孕期记录。")
        }
        RecordRow("保护本机资料", "离开应用后按设置自动锁定", "86969.svg")
        RecordRow("验证失败可重试", "不会删除或修改记录", "86969.svg")
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if(busy)CircularProgressIndicator() else JournalButton("解锁资料",onClick=unlock)
        Text("请勿向他人透露手机解锁信息。", style=MaterialTheme.typography.bodySmall)
    }
}
