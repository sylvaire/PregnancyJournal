package cn.yunji.journal.feature

import android.app.TimePickerDialog
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import cn.yunji.journal.core.designsystem.DesignIcon
import cn.yunji.journal.core.designsystem.JournalField
import cn.yunji.journal.core.designsystem.JournalError
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pickers follow this Chinese-only application's language, without changing the device locale. */
@Composable internal fun ChinesePickerContent(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val chinese = remember(configuration) { Configuration(configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) } }
    val context = chinesePickerContext()
    CompositionLocalProvider(LocalConfiguration provides chinese, LocalContext provides context, content = content)
}

@Composable internal fun chinesePickerContext(): Context {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog).apply {
            applyOverrideConfiguration(Configuration(configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        }
    }
}

/** A clock picker assists entry; manual input retains seconds and existing time-zone validation. */
@Composable internal fun RecordTimeField(value: String, update: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, seconds: Boolean = false, appendToList: Boolean = false) {
    var show by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val context = chinesePickerContext()
    val focus = LocalFocusManager.current
    val latestUpdate by rememberUpdatedState(update)
    val latestValue by rememberUpdatedState(value)
    JournalField(value, { error=null;update(it.take(if(appendToList)100 else 18)) }, label, modifier=modifier, enabled=enabled,
        placeholder=if(appendToList) "例如 08:00,20:00" else if(seconds) "例如 09:30:00" else "例如 09:30",
        trailingIcon={ IconButton(onClick={focus.clearFocus();show=true},enabled=enabled) {
            DesignIcon("ic-clock.svg", description="选择$label")
        } })
    error?.let {JournalError(it)}
    if(show && enabled) DisposableEffect(context) {
        val initial=runCatching {LocalTime.parse(if(appendToList)value.substringAfterLast(',').trim() else value)}.getOrDefault(LocalTime.of(9,0))
        val dialog=TimePickerDialog(context,cn.yunji.journal.R.style.JournalTimePickerDialog,{_,hour,minute ->
            val result=LocalTime.of(hour,minute,initial.second,initial.nano)
            val selected=result.format(if(initial.nano!=0) DateTimeFormatter.ISO_LOCAL_TIME else
                DateTimeFormatter.ofPattern(if(seconds || initial.second!=0) "HH:mm:ss" else "HH:mm",Locale.ROOT))
            val candidate=if(appendToList) (latestValue.split(',').map(String::trim).filter(String::isNotBlank)+selected).distinct().joinToString(",") else selected
            if(candidate.length>100) error="时刻过多，请先删除不需要的时刻。" else {error=null;latestUpdate(candidate)}
        },initial.hour,initial.minute,true)
        dialog.setOnDismissListener {show=false}
        dialog.show()
        onDispose {dialog.setOnDismissListener(null);dialog.dismiss();show=false}
    }
}
