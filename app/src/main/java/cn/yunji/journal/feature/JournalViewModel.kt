package cn.yunji.journal.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.yunji.journal.core.database.PregnancyEntity
import cn.yunji.journal.core.database.CustomReminderEntity
import cn.yunji.journal.core.database.VisitEntity
import cn.yunji.journal.core.database.PregnancyDateRevisionEntity
import cn.yunji.journal.data.JournalRepository
import cn.yunji.journal.data.ActivePregnancyPreferences
import cn.yunji.journal.data.MedicationForm
import cn.yunji.journal.data.IntakeForm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

data class JournalState(
    val loading: Boolean = true,
    val pregnancies: List<PregnancyEntity> = emptyList(),
    val visits: List<VisitEntity> = emptyList(),
    val activeId: String? = null,
    val error: String? = null,
    val dateRevisions: List<PregnancyDateRevisionEntity> = emptyList(),
) { val active get() = pregnancies.firstOrNull { it.id == activeId } ?: pregnancies.firstOrNull { it.status == "ONGOING" } ?: pregnancies.firstOrNull() }

@Serializable
data class PregnancyForm(val id: String? = null, val title: String = "我的孕期", val lmp: String = "", val edd: String = "", val hospital: String = "", val note: String = "")

@Serializable
data class VisitForm(val id: String? = null, val pregnancyId: String = "", val date: String = LocalDate.now().toString(), val time: String = "", val hospital: String = "", val items: String = "", val preparation: String = "", val actualDate: String = LocalDate.now().toString(), val advice: String = "", val actualTime: String = "", val actualHospital: String = "", val actualResult: String = "")

sealed interface JournalEvent {
    data object Saved : JournalEvent
    data class Error(val message: String) : JournalEvent
    data class Notice(val message: String) : JournalEvent
    data class DocumentRemoved(val id: String?, val message: String) : JournalEvent
    data class RootRemoved(val batch: String, val kind: String, val rootId: String) : JournalEvent
}

@HiltViewModel
class JournalViewModel @Inject constructor(private val repository: JournalRepository, private val savedState: SavedStateHandle,
    private val preferences: ActivePregnancyPreferences,
    private val documentImporter: dagger.Lazy<cn.yunji.journal.data.DocumentImporter> = dagger.Lazy { error("Import dependency required") },
    private val reminderScheduler: dagger.Lazy<cn.yunji.journal.data.ReminderScheduler> = dagger.Lazy { error("Reminder dependency required") },
    private val cloudRepository: dagger.Lazy<cn.yunji.journal.data.CloudConnectionRepository> = dagger.Lazy { error("Cloud dependency required") },
    private val recoverySettings: dagger.Lazy<cn.yunji.journal.data.RecoverySettings> = dagger.Lazy { error("Recovery dependency required") },
    private val cloudBackupAccess: dagger.Lazy<cn.yunji.journal.data.CloudBackupAccess> = dagger.Lazy { error("Cloud backup dependency required") },
    private val exportAccess: dagger.Lazy<cn.yunji.journal.data.ExportAccess> = dagger.Lazy { error("Export dependency required") },
    private val cloudCleanupAccess: dagger.Lazy<cn.yunji.journal.data.CloudCleanupAccess> = dagger.Lazy { error("Cloud cleanup dependency required") },
    private val cloudCleanupStatus: dagger.Lazy<cn.yunji.journal.data.CloudCleanupStatusStore> = dagger.Lazy { error("Cloud cleanup status dependency required") },
) : ViewModel() {
    private val cloudDelegate=lazy {CloudConnectionController(viewModelScope,cloudRepository.get())}
    val cloud get()=cloudDelegate.value
    private val recoveryDelegate=lazy {RecoveryController(viewModelScope,recoverySettings.get())}
    val recovery get()=recoveryDelegate.value
    private val cloudBackupDelegate=lazy {CloudBackupController(viewModelScope,cloudBackupAccess.get(),
        recoveryConfigured = {runCatching {recoverySettings.get().configured()}.getOrDefault(true)})}
    val cloudBackup get()=cloudBackupDelegate.value
    private val cloudCleanupDelegate=lazy {CloudCleanupController(viewModelScope,cloudCleanupAccess.get(),cloudCleanupStatus.get(),
        backupRunning={runCatching{cloudBackupAccess.get().isRunning()}.getOrDefault(false)})}
    val cloudCleanup get()=cloudCleanupDelegate.value
    /** The Activity supplies the SAF launcher; without it the wizard reports that no location can be chosen. */
    // 每次调用时读取当前 launcher：Activity 可能在 ViewModel 构造后才设置，
    // 且恢复/重建后会更换实例，直接捕获旧值会让后续导出静默失败。
    private val exportDelegate=lazy {ExportController(viewModelScope,exportAccess.get(),exportLaunchBridge)}
    private val exportLaunchBridge: (String, String, String) -> Unit = { kind, payload, fileName ->
        val target = exportLauncher
        if (target == null) throw IllegalStateException("当前环境无法选择保存位置")
        target(kind, payload, fileName)
    }
    val export get()=exportDelegate.value
    var exportLauncher: ((kind: String,payload: String,fileName: String)->Unit)?=null
    /** 后台挂起：清除敏感运行状态，但保留导出向导的界面选择（范围/明文确认）。 */
    fun clearSensitiveCloud() {
        if(cloudDelegate.isInitialized())cloudDelegate.value.leave()
        if(recoveryDelegate.isInitialized())recoveryDelegate.value.leave()
        if(cloudBackupDelegate.isInitialized())cloudBackupDelegate.value.leave()
        if(cloudCleanupDelegate.isInitialized())cloudCleanupDelegate.value.leave()
        if(exportDelegate.isInitialized())exportDelegate.value.leave()
    }
    override fun onCleared() {
        // 会话真正结束：连导出范围与确认一并清空。
        if(exportDelegate.isInitialized())exportDelegate.value.reset()
        clearSensitiveCloud();super.onCleared()
    }
    val documents get() = documentImporter.get()
    val medications get() = repository.medications
    private val _drugForm=MutableStateFlow(cn.yunji.journal.data.DrugForm())
    val drugForm=_drugForm.asStateFlow()
    fun hasDrugEditor(pregnancyId: String,id: String?)=_formReady.value && editing=="drug" && _drugForm.value.pregnancyId==pregnancyId && _drugForm.value.id==id
    fun editDrug(pregnancyId: String,id: String?): Job {
        val previous=flushDraft();_formReady.value=false
        editorGeneration++;draftRevision=0;persistedDraftRevision=0
        loadingForm?.cancel();draftJob?.cancel();editing="drug"
        return viewModelScope.launch {
            previous?.join()
            try {
                val initial=medications.drugForm(pregnancyId,id)
                val restored=repository.draft("drug",initial.draftKey)?.let {Json.decodeFromString<cn.yunji.journal.data.DrugForm>(it)}?.takeIf {it.pregnancyId==pregnancyId && it.id==id}
                _drugForm.value=restored ?: initial;_formReady.value=true
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){channel.send(JournalEvent.Error("无法打开药品，原记录与草稿已保留"))}
        }.also {loadingForm=it}
    }
    fun updateDrug(form: cn.yunji.journal.data.DrugForm){if(_drugForm.value!=form){_drugForm.value=form;draftRevision++;scheduleDraft()}}
    fun saveDrug()=action {require(_formReady.value);draftJob?.cancel();flushJob?.join();medications.saveDrug(_drugForm.value);editing=null;channel.send(JournalEvent.Saved)}
    val intakes get() = repository.intakes
    val measurements get() = repository.measurements
    val journalEntries get() = repository.journalEntries
    private val _journalEntryForm = MutableStateFlow(cn.yunji.journal.data.JournalEntryForm())
    val journalEntryForm = _journalEntryForm.asStateFlow()
    fun hasJournalEntryEditor(pregnancyId: String,id: String?) = _formReady.value && editing=="journalEntry" && _journalEntryForm.value.pregnancyId==pregnancyId && _journalEntryForm.value.id==id
    fun editJournalEntry(pregnancyId: String,id: String?): Job {
        val previous=flushDraft();_formReady.value=false
        editorGeneration++;draftRevision=0;persistedDraftRevision=0
        loadingForm?.cancel();draftJob?.cancel();editing="journalEntry"
        return viewModelScope.launch {
            previous?.join()
            try {
                val initial=journalEntries.form(pregnancyId,id)
                val restored=repository.draft("journalEntry",initial.draftKey)?.let {Json.decodeFromString<cn.yunji.journal.data.JournalEntryForm>(it)}?.takeIf {it.pregnancyId==pregnancyId && it.id==id}
                _journalEntryForm.value=restored ?: initial;_formReady.value=true
            } catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){channel.send(JournalEvent.Error("无法打开日常记录，原记录与草稿已保留"))}
        }.also {loadingForm=it}
    }
    fun updateJournalEntry(form: cn.yunji.journal.data.JournalEntryForm) {if(_journalEntryForm.value!=form){_journalEntryForm.value=form;draftRevision++;scheduleDraft()}}
    fun saveJournalEntry() = action {
        require(_formReady.value);draftJob?.cancel();flushJob?.join()
        journalEntries.save(_journalEntryForm.value);editing=null;channel.send(JournalEvent.Saved)
    }
    private val _measurementForm = MutableStateFlow(cn.yunji.journal.data.MeasurementForm())
    val measurementForm = _measurementForm.asStateFlow()
    fun hasMeasurementEditor(pregnancyId: String, id: String?) = _formReady.value && editing == "measurement" && _measurementForm.value.pregnancyId == pregnancyId && _measurementForm.value.id == id
    fun editMeasurement(pregnancyId: String, id: String?): Job {
        val previous = flushDraft()
        _formReady.value = false
        editorGeneration++; draftRevision = 0; persistedDraftRevision = 0
        loadingForm?.cancel(); draftJob?.cancel(); editing = "measurement"
        return viewModelScope.launch {
            previous?.join()
            try {
                val initial = measurements.form(pregnancyId,id)
                val restored = repository.draft("measurement",initial.draftKey)?.let { Json.decodeFromString<cn.yunji.journal.data.MeasurementForm>(it) }
                    ?.takeIf { it.id == id && it.pregnancyId == pregnancyId }
                _measurementForm.value = restored ?: initial; _formReady.value = true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { channel.send(JournalEvent.Error("无法打开测量记录，原记录与草稿已保留")) }
        }.also { loadingForm = it }
    }
    fun updateMeasurement(form: cn.yunji.journal.data.MeasurementForm) { if (_measurementForm.value != form) { _measurementForm.value = form; draftRevision++; scheduleDraft() } }
    fun saveMeasurement() = action {
        require(_formReady.value); draftJob?.cancel(); flushJob?.join()
        measurements.save(_measurementForm.value); editing = null; channel.send(JournalEvent.Saved)
    }
    private val _intakeForm = MutableStateFlow(IntakeForm())
    val intakeForm = _intakeForm.asStateFlow()
    fun hasIntakeEditor(pregnancyId: String, occurrenceId: String?, recordId: String?) = _formReady.value && editing == "intake" &&
        _intakeForm.value.pregnancyId == pregnancyId && (recordId != null || _intakeForm.value.occurrenceId == occurrenceId) && _intakeForm.value.recordId == recordId
    fun editIntake(pregnancyId: String, occurrenceId: String?, recordId: String?, status: String, date: String?): Job {
        val previous = flushDraft()
        _formReady.value = false
        editorGeneration++; draftRevision = 0; persistedDraftRevision = 0
        loadingForm?.cancel(); draftJob?.cancel(); editing = "intake"
        return viewModelScope.launch {
            previous?.join()
            try {
                val initial = intakes.form(pregnancyId, occurrenceId, recordId, status, date)
                val restored = repository.draft("intake", initial.draftKey)?.let { Json.decodeFromString<IntakeForm>(it) }
                    ?.takeIf { it.pregnancyId == pregnancyId && it.occurrenceId == initial.occurrenceId && it.recordId == initial.recordId }
                    ?.copy(plannedDate = initial.plannedDate, plannedTime = initial.plannedTime, plannedZoneId = initial.plannedZoneId)
                _intakeForm.value = if (restored != null && recordId == null && occurrenceId != null) restored.copy(status = status) else restored ?: initial
                _formReady.value = true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: IllegalArgumentException) { channel.send(JournalEvent.Error(e.message ?: "无法打开服药记录")) }
            catch (_: Exception) { channel.send(JournalEvent.Error("无法读取服药记录，原记录与草稿已保留")) }
        }.also { loadingForm = it }
    }
    fun updateIntake(form: IntakeForm) { if (_intakeForm.value != form) { _intakeForm.value = form; draftRevision++; scheduleDraft() } }
    fun saveIntake() = action {
        require(_formReady.value)
        draftJob?.cancel(); flushJob?.join()
        intakes.save(_intakeForm.value)
        editing = null; channel.send(JournalEvent.Saved)
    }
    private val _medicationForm = MutableStateFlow(MedicationForm())
    val medicationForm = _medicationForm.asStateFlow()
    fun hasMedicationEditor(id: String?, pregnancyId: String) = _formReady.value && editing == "medicationPlan" && _medicationForm.value.id == id && _medicationForm.value.pregnancyId == pregnancyId
    fun editMedication(id: String?, pregnancyId: String): Job {
        val previous = flushDraft()
        _formReady.value = false
        editorGeneration++; draftRevision = 0; persistedDraftRevision = 0
        loadingForm?.cancel(); draftJob?.cancel(); editing = "medicationPlan"
        return viewModelScope.launch {
            previous?.join()
            try {
                val initial = medications.form(pregnancyId, id)
                val draft = repository.draft("medicationPlan", id ?: "new:$pregnancyId")?.let { Json.decodeFromString<MedicationForm>(it) }
                _medicationForm.value = draft?.takeIf { it.id == id && it.pregnancyId == pregnancyId } ?: initial
                _formReady.value = true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { channel.send(JournalEvent.Error("无法读取用药方案，原有记录和草稿已保留")) }
        }.also { loadingForm = it }
    }
    fun updateMedication(form: MedicationForm) { if (_medicationForm.value != form) { _medicationForm.value = form; draftRevision++; scheduleDraft() } }
    fun saveMedication() = action {
        require(_formReady.value)
        draftJob?.cancel(); flushJob?.join()
        medications.save(_medicationForm.value)
        editing = null; channel.send(JournalEvent.Saved)
    }
    fun medicationStatus(id: String, status: String) = action { medications.changeStatus(id, status) }
    val trash get() = documents.trashRepository
    fun deleteRoot(kind: String, id: String) = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        try {
            val batch = trash.delete(kind, id)
            channel.send(JournalEvent.RootRemoved(batch, kind, id))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { channel.send(JournalEvent.Error("删除未完成，请稍后重试")) }
        finally { _busy.value = false; requestReminderRebuild(); requestCloudBackup() }
    }
    fun restoreBatch(id: String) = documentAction { trash.restore(id) }
    fun purgeBatch(id: String) = documentAction { trash.purge(id) }
    fun removeDocument(id: String) = documentAction {
        val recycled = documents.trash(id)
        channel.send(JournalEvent.DocumentRemoved(if (recycled) id else null, if (recycled) "已移入回收站" else "已取消导入并移除"))
    }
    fun restoreDocument(id: String) = documentAction { documents.restore(id) }
    fun purgeDocument(id: String) = documentAction { documents.purge(id) }
    fun editDocument(id: String, name: String, category: String, date: String?=null,hospital: String?=null,note: String?=null) = documentAction { documents.updateMetadata(id, name, category,date,hospital,note) }
    fun setDocumentCover(attachmentId: String, visitId: String) = documentAction {
        documents.setCover(attachmentId, visitId)
        channel.send(JournalEvent.Notice("已设为本次产检封面"))
    }
    fun clearDocumentCover(visitId: String) = documentAction {
        documents.clearCover(visitId); channel.send(JournalEvent.Notice("已取消封面"))
    }
    fun relinkDocument(id: String, targetVisitId: String, expectedRevision: Long?=null) = documentAction {
        documents.relink(id, targetVisitId, expectedRevision); channel.send(JournalEvent.Notice("已重新关联产检"))
    }
    fun exportDocument(id: String, destination: android.net.Uri, expectedRevision: Long?=null) = documentAction {
        documents.exportOriginal(id, destination, expectedRevision); channel.send(JournalEvent.Notice("原件已导出"))
    }
    fun replaceDocument(id: String, source: android.net.Uri, expectedRevision: Long?=null) = documentAction {
        documents.replaceOriginal(id, source, expectedRevision); channel.send(JournalEvent.Notice("原件已替换并重新加密"))
    }
    private fun documentAction(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: IllegalArgumentException) { channel.send(JournalEvent.Error(e.message ?: "操作未完成，资料状态已保留")) }
        catch (_: Exception) { channel.send(JournalEvent.Error("操作未完成，资料状态已保留，请稍后重试")) }
        finally { requestReminderRebuild(); requestCloudBackup() }
    }
    fun importDocuments(visitId: String, uris: List<android.net.Uri>) = viewModelScope.launch {
        try { documents.enqueue(visitId, uris) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { channel.send(JournalEvent.Error("无法加入导入队列，请确认文件可读取且一次不超过20项")) }
    }
    fun importMedicationPhotos(id: String,uris: List<android.net.Uri>)=viewModelScope.launch {
        try {documents.enqueueMedication(id,uris)}catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){channel.send(JournalEvent.Error("无法加入包装照片队列，请确认图片可读取"))}
    }
    fun retryDocument(id: String, uri: android.net.Uri? = null) = viewModelScope.launch {
        try { documents.retry(id, uri) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { channel.send(JournalEvent.Error("无法重新导入，请稍后重试")) }
    }
    private val selected = savedState.getStateFlow<String?>("activePregnancy", null)
    val state = combine(repository.pregnancies, repository.visits, repository.dateRevisions, selected, preferences.activePregnancy) { pregnancies, visits, dates, id, persistedId ->
        JournalState(false, pregnancies, visits, id ?: persistedId, dateRevisions = dates)
    }.catch { emit(JournalState(loading = false, error = "暂时无法读取加密资料。原始文件已保留，请勿清除应用数据。")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalState())
    val customReminders: StateFlow<List<CustomReminderEntity>> = repository.customReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val channel = Channel<JournalEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private val _pregnancyForm = MutableStateFlow(PregnancyForm())
    val pregnancyForm = _pregnancyForm.asStateFlow()
    private val _visitForm = MutableStateFlow(VisitForm())
    val visitForm = _visitForm.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var draftJob: Job? = null
    private var flushJob: Job? = null
    private var loadingForm: Job? = null
    private var editing: String? = null
    private var editorGeneration = 0L
    private var draftRevision = 0L
    private var persistedDraftRevision = 0L
    private val _formReady = MutableStateFlow(false)
    val formReady = _formReady.asStateFlow()

    fun select(id: String) {
        savedState["activePregnancy"] = id
        viewModelScope.launch { runCatching { preferences.selectPregnancy(id) } }
    }

    fun hasPregnancyEditor(id: String?) = _formReady.value && editing == "pregnancy" && _pregnancyForm.value.id == id
    fun hasVisitEditor(id: String?, pregnancyId: String, actual: Boolean = false) = _formReady.value &&
        editing == (if (actual) "actualVisit" else "visit") && _visitForm.value.id == id && _visitForm.value.pregnancyId == pregnancyId

    fun editPregnancy(id: String?): Job {
        val previousDraft = flushDraft()
        _formReady.value = false
        editorGeneration++; draftRevision = 0; persistedDraftRevision = 0
        loadingForm?.cancel(); draftJob?.cancel(); editing = "pregnancy"
        return viewModelScope.launch {
            previousDraft?.join()
            runCatching {
                val draft = repository.draft("pregnancy", id ?: "new")
                _pregnancyForm.value = if (draft != null) Json.decodeFromString<PregnancyForm>(draft) else {
                    val entity = id?.let { repository.pregnancy(it) }
                    if (entity != null) PregnancyForm(entity.id, entity.title, entity.lmpDate.orEmpty(), entity.confirmedEdd.orEmpty(), entity.hospital, entity.note) else PregnancyForm()
                }
                _formReady.value = true
            }.onFailure { channel.send(JournalEvent.Error("无法读取草稿，已保留原始资料")) }
        }.also { loadingForm = it }
    }

    fun updatePregnancy(form: PregnancyForm) {
        if (_pregnancyForm.value == form) return
        _pregnancyForm.value = form
        draftRevision++
        scheduleDraft()
    }

    fun editVisit(id: String?, pregnancyId: String, actual: Boolean = false): Job {
        val previousDraft = flushDraft()
        _formReady.value = false
        editorGeneration++; draftRevision = 0; persistedDraftRevision = 0
        loadingForm?.cancel(); draftJob?.cancel(); editing = if (actual) "actualVisit" else "visit"
        return viewModelScope.launch {
            previousDraft?.join()
            runCatching {
                val feature = if (actual) "actualVisit" else "visit"
                val key = id ?: "new:$pregnancyId"
                // Migrate old unscoped drafts only when their pregnancy matches this editor.
                if (!actual && id == null && repository.draft(feature, key) == null) {
                    repository.draft("visit", "new")?.let { legacy ->
                        if (Json.decodeFromString<VisitForm>(legacy).pregnancyId == pregnancyId)
                            repository.moveDraft("visit", "new", key, legacy)
                    }
                }
                val draft = repository.draft(feature, key)
                val restored = draft?.let { Json.decodeFromString<VisitForm>(it) }
                _visitForm.value = if (restored != null && restored.pregnancyId == pregnancyId) restored else {
                    val entity = id?.let { repository.visit(it) }
                    if (entity != null) VisitForm(entity.id, entity.pregnancyId, entity.scheduledDate, entity.scheduledTime.orEmpty(), entity.hospital, entity.items, entity.preparation, entity.actualDate ?: LocalDate.now().toString(), entity.advice,
                        entity.actualTime.orEmpty(), entity.actualHospital.orEmpty(), entity.actualResult.orEmpty())
                    else VisitForm(pregnancyId = pregnancyId, hospital = state.value.active?.hospital.orEmpty())
                }
                _formReady.value = true
            }.onFailure { channel.send(JournalEvent.Error("无法读取预约草稿")) }
        }.also { loadingForm = it }
    }

    fun updateVisit(form: VisitForm) {
        if (_visitForm.value == form) return
        _visitForm.value = form; draftRevision++; scheduleDraft()
    }

    private fun scheduleDraft() {
        if (!_formReady.value) return
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(250)
            draftJob = null
            flushDraft()?.join()
        }
    }

    fun flushDraft(): Job? {
        if (!_formReady.value || _busy.value) return null
        if (draftRevision == persistedDraftRevision) return flushJob
        val feature = editing ?: return null
        val generation = editorGeneration
        val revision = draftRevision
        val (id, payload) = when (feature) {
            "pregnancy" -> (_pregnancyForm.value.id ?: "new") to Json.encodeToString(_pregnancyForm.value)
            "medicationPlan" -> (_medicationForm.value.id ?: "new:${_medicationForm.value.pregnancyId}") to Json.encodeToString(_medicationForm.value)
            "intake" -> _intakeForm.value.draftKey to Json.encodeToString(_intakeForm.value)
            "measurement" -> _measurementForm.value.draftKey to Json.encodeToString(_measurementForm.value)
            "journalEntry" -> _journalEntryForm.value.draftKey to Json.encodeToString(_journalEntryForm.value)
            "drug" -> _drugForm.value.draftKey to Json.encodeToString(_drugForm.value)
            "visit", "actualVisit" -> (_visitForm.value.id ?: "new:${_visitForm.value.pregnancyId}") to Json.encodeToString(_visitForm.value)
            else -> return null
        }
        draftJob?.cancel()
        val previous = flushJob
        return viewModelScope.launch {
            previous?.join()
            try {
                repository.saveDraft(feature, id, payload)
                if (editorGeneration == generation) persistedDraftRevision = maxOf(persistedDraftRevision, revision)
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { channel.send(JournalEvent.Error("草稿暂未保存，请返回编辑后重试")) }
        }.also { flushJob = it }
    }

    fun savePregnancy() = action {
        require(_formReady.value) { "请等待档案读取完成" }
        draftJob?.cancel()
        flushJob?.join()
        val f = _pregnancyForm.value
        val id = repository.savePregnancy(f.id, f.title, f.lmp.takeIf(String::isNotBlank)?.let(LocalDate::parse),
            f.edd.takeIf(String::isNotBlank)?.let(LocalDate::parse), f.hospital, f.note)
        select(id); editing = null; channel.send(JournalEvent.Saved)
    }

    fun saveVisit() = action {
        require(_formReady.value) { "请等待预约读取完成" }
        draftJob?.cancel()
        flushJob?.join()
        val f = _visitForm.value
        repository.saveVisit(f.id, f.pregnancyId, LocalDate.parse(f.date), f.time.takeIf(String::isNotBlank)?.let(LocalTime::parse), f.hospital, f.items, f.preparation)
        editing = null; channel.send(JournalEvent.Saved)
    }

    fun saveActualVisit() = action {
        require(_formReady.value) { "请等待记录读取完成" }
        draftJob?.cancel()
        flushJob?.join()
        val f = _visitForm.value
        repository.completeVisit(requireNotNull(f.id), LocalDate.parse(f.actualDate), f.advice,
            f.actualTime.takeIf(String::isNotBlank)?.let(LocalTime::parse), f.actualHospital, f.actualResult)
        editing = null
        channel.send(JournalEvent.Saved)
    }

    fun cancelVisit(id: String, cancelled: Boolean) = action { repository.setVisitCancelled(id, cancelled) }

    fun setVisitReminder(id: String, date: String, time: String) = action {
        repository.setVisitReminder(id, date, time)
        channel.send(JournalEvent.Notice("提醒已设置"))
    }

    fun clearVisitReminder(id: String) = action {
        repository.clearVisitReminder(id)
        channel.send(JournalEvent.Notice("提醒已关闭"))
    }

    fun saveCustomReminder(id: String?, pregnancyId: String, title: String, note: String,
        startDate: String, localTime: String, repeat: String, endDate: String?) = action {
        repository.saveCustomReminder(id,pregnancyId,title,note,LocalDate.parse(startDate),LocalTime.parse(localTime),
            repeat,endDate?.takeIf { it.isNotBlank() }?.let(LocalDate::parse))
        channel.send(JournalEvent.Notice("提醒已保存"))
    }

    fun deleteCustomReminder(id: String) = action {
        repository.deleteCustomReminder(id)
        channel.send(JournalEvent.Notice("提醒已删除"))
    }

    fun rebuildReminders() = viewModelScope.launch {
        try {
            val result = reminderScheduler.get().rebuild()
            channel.send(JournalEvent.Notice("已重建未来7天提醒：${result.scheduled}项"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { channel.send(JournalEvent.Error("提醒重建失败，稍后会自动重试")) }
    }

    fun sendTestReminder() = viewModelScope.launch {
        val shown = runCatching { reminderScheduler.get().postTest() }.getOrDefault(false)
        channel.send(if (shown) JournalEvent.Notice("测试通知已发送") else JournalEvent.Error("通知权限未开启，请在系统设置中允许通知"))
    }

    fun setReminderTypeEnabled(visits: Boolean?, enabled: Boolean) {
        runCatching { if (visits == null) reminderScheduler.get().setMedicationsEnabled(enabled) else reminderScheduler.get().setVisitsEnabled(enabled) }
    }
    fun notificationsAllowed(): Boolean = runCatching { reminderScheduler.get().notificationPermissionGranted() }.getOrDefault(false)
    fun exactRemindersAllowed(): Boolean = runCatching { reminderScheduler.get().exactAllowed() }.getOrDefault(false)
    fun visitsReminderEnabled(): Boolean = runCatching { reminderScheduler.get().visitsEnabled() }.getOrDefault(true)
    fun medicationsReminderEnabled(): Boolean = runCatching { reminderScheduler.get().medicationsEnabled() }.getOrDefault(true)
    private fun requestReminderRebuild() { runCatching { reminderScheduler.get().requestRebuild() } }

    /** Local edits mark the vault dirty; the unique worker coalesces bursts into one upload. */
    private fun requestCloudBackup() {
        if (!cloudBackupDelegate.isInitialized()) return
        runCatching {
            val access = cloudBackupAccess.get()
            if (access.scheduler().autoActive) access.scheduleAuto()
        }
    }

    fun changeStatus(id: String, status: String) = action { repository.setPregnancyStatus(id, status) }

    private fun action(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { block() }
            catch (_: java.time.format.DateTimeParseException) { channel.send(JournalEvent.Error("日期或时间格式不正确")) }
            catch (e: IllegalArgumentException) { channel.send(JournalEvent.Error(e.message ?: "请检查填写内容")) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { channel.send(JournalEvent.Error("保存失败，已保留现有资料，请稍后重试")) }
            finally { _busy.value = false; requestReminderRebuild(); requestCloudBackup() }
        }
    }
}
