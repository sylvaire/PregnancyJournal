package cn.yunji.journal.feature

import cn.yunji.journal.core.export.ExportScope
import cn.yunji.journal.data.ExportAccess
import cn.yunji.journal.data.ExportRequest
import cn.yunji.journal.data.ExportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Export wizard state. Plaintext confirmation is explicit: nothing is written before it is given. */
data class ExportUiState(
    val from: String = "",
    val to: String = "",
    val includeOriginals: Boolean = true,
    val includeTrash: Boolean = false,
    val sections: Set<String> = ExportScope.ALL_SECTIONS,
    val plaintextAcknowledged: Boolean = false,
    val preview: Map<String, Int> = emptyMap(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val detail: String? = null,
    val lastResult: String? = null,
) {
    val rangeValid: Boolean
        get() = runCatching {
            ExportScope(from.ifBlank { null }, to.ifBlank { null }, includeOriginals, includeTrash, sections)
        }.isSuccess
    val canStart: Boolean get() = plaintextAcknowledged && !busy && !loading && rangeValid
}

/**
 * Drives the export wizard. The controller never touches the database: it prepares a request, hands the
 * destination choice to the Activity through [launch], and applies the result when the URI comes back.
 */
class ExportController(
    private val scope: CoroutineScope,
    private val access: ExportAccess,
    private val launch: ((kind: String, payload: String, fileName: String) -> Unit)?,
) {
    private val mutable = MutableStateFlow(ExportUiState())
    val state = mutable.asStateFlow()
    private var task: Job? = null

    fun updateRange(from: String, to: String) {
        mutable.value = mutable.value.copy(from = from.take(10), to = to.take(10), message = null)
    }
    fun toggleSection(section: String) {
        val current = mutable.value.sections
        val next = if (section in current) current - section else current + section
        if (next.isEmpty()) {
            mutable.value = mutable.value.copy(message = "请至少选择一个导出范围")
            return
        }
        mutable.value = mutable.value.copy(sections = next, message = null)
    }
    fun setOriginals(enabled: Boolean) { mutable.value = mutable.value.copy(includeOriginals = enabled) }
    fun setTrash(enabled: Boolean) { mutable.value = mutable.value.copy(includeTrash = enabled) }
    fun acknowledgePlaintext(value: Boolean) { mutable.value = mutable.value.copy(plaintextAcknowledged = value) }

    fun request(): ExportRequest {
        val current = mutable.value
        return ExportRequest(from = current.from.ifBlank { null }, to = current.to.ifBlank { null },
            includeOriginals = current.includeOriginals, includeTrash = current.includeTrash,
            sections = current.sections)
    }

    /** Counts what the current scope would contain, so the user can judge before choosing a destination. */
    fun preview() {
        val current = mutable.value
        if (current.loading || current.busy) return
        if (!current.rangeValid) { mutable.value = current.copy(message = "日期范围无效，请检查起止日期"); return }
        mutable.value = current.copy(loading = true, message = null)
        task = scope.launch {
            try {
                val counts = access.preview(request())
                mutable.value = mutable.value.copy(preview = counts, loading = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.value = mutable.value.copy(loading = false, message = e.message ?: "无法统计导出内容")
            }
        }
    }

    fun startDataExport() = start("EXPORT_DATA")
    fun startSummaryPdf() = start("SUMMARY_PDF")

    private fun start(kind: String) {
        val current = mutable.value
        if (!current.canStart) {
            mutable.value = current.copy(message = if (!current.rangeValid) "日期范围无效，请检查起止日期"
                else "请先确认导出内容为明文并自行保管")
            return
        }
        val launcher = launch ?: run {
            mutable.value = current.copy(message = "当前环境无法选择保存位置")
            return
        }
        val name = if (kind == "EXPORT_DATA") "孕迹导出-${java.time.LocalDate.now()}.zip"
        else "孕迹就诊摘要-${java.time.LocalDate.now()}.pdf"
        val payload = cn.yunji.journal.data.ExportRequestCodec.encode(request())
        // 启动器可能因 Activity 重建/锁屏而暂时不可用；不能静默失败，否则按钮看起来“没反应”。
        try { launcher(kind, payload, name) }
        catch (e: Exception) {
            mutable.value = current.copy(message = e.message ?: "无法打开保存位置选择器，请稍后重试")
        }
    }

    /** Called with the chosen destination after the system picker returns. */
    fun apply(destination: android.net.Uri, kind: String, payload: String?) {
        if (mutable.value.busy) return
        val request = runCatching {
            require(kind in setOf("EXPORT_DATA", "SUMMARY_PDF"))
            cn.yunji.journal.data.ExportRequestCodec.decode(payload)
        }.getOrElse {
            mutable.value = mutable.value.copy(message = "导出设置已失效，请重新选择范围和保存位置",
                detail = null, lastResult = null)
            return
        }
        mutable.value = mutable.value.copy(busy = true, message = null, detail = null)
        task = scope.launch {
            try {
                val result: ExportResult = if (kind == "SUMMARY_PDF") access.exportSummary(destination, request)
                else access.exportData(destination, request)
                mutable.value = mutable.value.copy(busy = false, message = result.message, detail = result.detail,
                    lastResult = if (result.success) kind else null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.value = mutable.value.copy(busy = false, message = e.message ?: "导出未完成")
            }
        }
    }

    fun cancel() { task?.cancel(); task = null; mutable.value = mutable.value.copy(busy = false, loading = false, message = "已取消导出") }

    /**
     * 应用进入后台（例如系统保存器弹出）会走这里。清除进行中的任务与结果消息，
     * 但保留用户已选择的导出范围与“明文”确认：这些都是界面选择而非敏感数据，
     * 清掉会让返回后的第二次导出看起来像按钮失效（用户必须重新勾选才发现）。
     */
    fun leave() {
        task?.cancel(); task = null
        val current = mutable.value
        mutable.value = current.copy(
            busy = false, loading = false, message = null, detail = null,
            lastResult = null, preview = emptyMap(),
        )
    }

    /** 资料库切换/登出等真正的会话边界：连范围与确认一起清空。 */
    fun reset() { task?.cancel(); task = null; mutable.value = ExportUiState() }
}
