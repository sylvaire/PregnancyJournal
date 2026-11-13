package cn.yunji.journal

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.rememberNavBackStack
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.core.security.*
import cn.yunji.journal.data.*
import cn.yunji.journal.domain.ReminderEvent
import cn.yunji.journal.feature.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity: FragmentActivity() {
    @Inject lateinit var keys: DeviceKeyStore
    @Inject lateinit var settings: SecurityStore
    @Inject lateinit var runtime: VaultRuntime
    @Inject lateinit var access: AccessState
    @Inject lateinit var notifier: ReminderNotifier
    @Inject lateinit var files: FileHandoff
    @Inject lateinit var restoreAccess: RestoreAccess
    private lateinit var restoreController: RestoreController
    private var restoreOpen by mutableStateOf(false)
    private var activityResumed by mutableStateOf(false)
    private var navigationEpoch by mutableIntStateOf(0)
    private var restoreRequestId: String?=null
    private var restoreCommit: Job?=null
    private lateinit var fileLauncher: ActivityResultLauncher<Intent>
    private var receivingFile: Job?=null
    private var applyingFile: Job?=null
    private var fileResult by mutableStateOf<FileResult?>(null)
    private val scope=MainScope()
    private var model by mutableStateOf<JournalViewModel?>(null)
    private var covered by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var authenticationRunning by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<String?>(null)
    private val backupProgress=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private var showSecurity by mutableStateOf(false)
    private var securityTick by mutableIntStateOf(0)
    private var generation=0
    private var prompt: BiometricPrompt?=null
    private var request: DeviceKeyStore.Authentication?=null
    private var opening: Job?=null
    private var background: Job?=null
    private var committing: Job?=null
    private var pausedSession=false
    private data class Pending(val key: String,val generation: Long,val action: String)
    private var pending: Pending?=null
    private var pendingAuthorized=false
    private var pendingEvent by mutableStateOf<ReminderEvent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderWorker.recover(applicationContext)
        restoreController=RestoreController(scope,restoreAccess)
        enableEdgeToEdge()
        // 仅显式 verification 构建（-PvisualVerification=true）关闭 FLAG_SECURE 以便真机截图；
        // 普通 debug / release / performance 构建始终启用安全标志。
        if (secureFlagRequired()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        fileLauncher=activityResultRegistry.register("vault-file-result-v1",this,ActivityResultContracts.StartActivityForResult()) {result ->
            receivingFile=scope.launch {
                try {withContext(Dispatchers.IO){files.receive(result.resultCode,result.data,access.needsAuthentication())}}
                catch(_:Exception){notice="无法接收文件选择结果，原资料已保留。"}
                if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !access.needsAuthentication())openModel()
            }
        }
        acceptIntent(intent)
        setContent {
            PregnancyTheme {
                val backupPhase by backupProgress.collectAsStateWithLifecycle()
                val restoreState by restoreController.state.collectAsStateWithLifecycle()
                val stack=key(navigationEpoch){rememberNavBackStack(TodayRoute)}
                val current=model
                Box(Modifier.fillMaxSize()) {
                // Preserve registered system-picker callbacks until an actual session revocation.
                if(current!=null) Box(if(covered)Modifier.alpha(0f).clearAndSetSemantics {} else Modifier) {
                    CompositionLocalProvider(LocalFileLaunch provides ::launchFile,LocalRestoreLaunch provides ::openRestore,LocalSafetyActionsAllowed provides !covered) {JournalApp(current,stack){showSecurity=true}}
                    if(showSecurity && !covered) Dialog(onDismissRequest={showSecurity=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,securePolicy=SecureFlagPolicy.SecureOn)) {
                        JournalPage(title="安全设置",back={showSecurity=false}) {
                            securityTick
                            SecuritySettingsScreen(SecurityUi(settings.lockEnabled(),keys.strict(),settings.timeoutMinutes()),::securityAction)
                        }
                    }
                    pendingEvent?.takeIf {!covered}?.let {event -> AlertDialog(onDismissRequest={clearPending()},title={Text(if(pending?.action==ReminderContract.ACTION_TAKEN)"确认记录为已服用？" else "稍后10分钟提醒？")},
                        text={Text("${event.title}\n${event.body}\n请按实际情况确认。")},
                        confirmButton={TextButton(::confirmPending,enabled=!busy){Text("确认")}},dismissButton={TextButton({clearPending()},enabled=!busy){Text("取消")}}) }
                    backupPhase?.takeIf {!covered}?.let {phase->AlertDialog(onDismissRequest={},title={Text("正在导出备份")},text={Text(phase)},confirmButton={TextButton({applyingFile?.cancel()}){Text("取消备份")}})}
                    fileResult?.takeIf {!covered && backupPhase==null}?.let {result -> AlertDialog(onDismissRequest={discardFile()},title={Text(if(result.backupStarted)"上次备份需要重新选择位置" else if(!result.returned)"上次文件选择尚未完成" else when(result.request.kind){"REPLACE"->"替换这份资料的原件？";"EXPORT"->"继续导出原件？";"BACKUP"->"开始制作加密备份？";"SAFETY_EXPORT"->"导出这份安全副本？";else->"继续处理所选文件？"})},
                        text={Text(if(result.backupStarted)"上次导出已中断，所选位置可能留下未验证文件。请到保存位置删除本次创建的文件（如仍存在），再取消这次选择并重新导出。原资料保持不变。" else if(!result.returned)"可以取消这次选择后重新操作。原资料保持不变。" else if(result.request.kind=="REPLACE")"新文件会重新加密并校验，原件将被替换。记录已变化时不会覆盖。" else if(result.request.kind=="BACKUP")"将校验完整数据后保存到所选位置。请确认恢复密钥已单独保管。" else if(result.request.kind=="SAFETY_EXPORT")"将原样保存加密副本并核对文件，需要使用该副本对应的恢复密钥。" else "已恢复${result.uris.size}个文件的选择结果，确认后继续。")},
                        confirmButton={if(result.canApply)TextButton({applyFile(result)},enabled=!busy){Text("继续")}},dismissButton={TextButton({discardFile()},enabled=!busy){Text("取消")}}) }
                }
                if(covered && current!=null) Dialog(onDismissRequest={},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,securePolicy=SecureFlagPolicy.SecureOn)) {
                    Surface(Modifier.fillMaxSize()) {UnlockScreen(busy || authenticationRunning,error){authenticate(if(pending!=null)"NOTIFICATION" else "UNLOCK")}}
                }
                if(current==null) Surface(Modifier.fillMaxSize()) {UnlockScreen(busy || authenticationRunning,error){authenticate(if(pending!=null)"NOTIFICATION" else "UNLOCK")}}
                }
                if(restoreOpen && activityResumed && (!covered || restoreState.stage in setOf("RESTORING","DONE","ERROR"))) {
                    Dialog(onDismissRequest={if(restoreState.stage!="RESTORING")cancelRestore()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,securePolicy=SecureFlagPolicy.SecureOn)) {
                        Surface(Modifier.fillMaxSize()) {JournalPage(title="恢复资料",back={if(restoreState.stage!="RESTORING")cancelRestore()}) {
                            RestoreContent(restoreState,::chooseRestoreFile,restoreController::key,restoreController::prepare,
                                restoreController::acknowledge,::confirmRestore,::cancelRestore,::reopenRestoredLibrary,hostBusy=busy,
                                startCloud=restoreController::startCloud,findCloud=restoreController::findCloud,
                                selectSnapshot=restoreController::selectSnapshot)
                        }}
                    }
                }
                notice?.let {message -> AlertDialog(onDismissRequest={notice=null},title={Text("孕迹")},text={Text(message)},confirmButton={TextButton({notice=null}){Text("知道了")}}) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);acceptIntent(intent);if(pending!=null)authenticate("NOTIFICATION")}
    private fun acceptIntent(intent: Intent?) {
        val action=intent?.action ?: return
        if(action !in setOf(ReminderContract.ACTION_TAKEN,ReminderContract.ACTION_SNOOZE))return
        val key=intent.getStringExtra(ReminderContract.EXTRA_KEY) ?: return
        val gen=intent.getLongExtra(ReminderContract.EXTRA_GENERATION,0)
        if(key.length !in 1..220 || gen<=0)return
        pending=Pending(key,gen,action);pendingAuthorized=false;pendingEvent=null
    }
    private fun clearPending() {pending=null;pendingEvent=null;pendingAuthorized=false;intent?.action=Intent.ACTION_MAIN;intent?.removeExtra(ReminderContract.EXTRA_KEY);intent?.removeExtra(ReminderContract.EXTRA_GENERATION)}
    override fun onResume() {
        super.onResume()
        activityResumed=true
        pausedSession=false
        try {
            access.resumed()
            if(authenticationRunning || busy || restoreController.state.value.stage=="RESTORING")return
            if(pending!=null && !pendingAuthorized){authenticate("NOTIFICATION");return}
            if(!access.needsAuthentication()) {access.unlock();openModel()} else covered=true
        } catch(_:Exception){covered=true;error="暂时无法解锁资料。原文件已保留，请勿清除应用数据。"}
    }
    override fun onPause() {coverSession();super.onPause()}
    override fun onStop() {
        super.onStop()
        if(!pausedSession)coverSession()
    }
    private fun coverSession() {
        covered=true
        activityResumed=false
        if(::restoreController.isInitialized && restoreController.state.value.stage!="RESTORING") {
            restoreCommit?.cancel()
            restoreController.leave();restoreOpen=false
        }
        if(pausedSession)return
        pausedSession=true
        model?.clearSensitiveCloud()
        access.stopped();notifier.redactActive();committing?.cancel();applyingFile?.cancel();fileResult=null
        if(authenticationRunning)return
        background=scope.launch(NonCancellable,start=CoroutineStart.UNDISPATCHED) {
            applyingFile?.join()
            model?.flushDraft()?.join()
            runtime.clearPreviews()
            if(keys.strict() || settings.lockEnabled() && settings.timeoutMinutes()==0) {
                access.lock();model=null;viewModelStore.clear()
                if(keys.strict())runtime.revoke()
            }
            pendingAuthorized=false;pendingEvent=null
            ReminderWorker.enqueue(applicationContext)
        }
    }
    override fun onDestroy() {
        if(::restoreController.isInitialized)restoreController.leave()
        prompt?.cancelAuthentication();request?.let {keys.cancel(it)};request=null
        scope.cancel();super.onDestroy()
    }
    private fun openModel(after: (()->Unit)?=null) {
        opening?.cancel()
        opening=scope.launch {
            try {
                background?.join()
                receivingFile?.join()
                withContext(Dispatchers.IO){runtime.resources();restoreAccess.finishReopen()}
                if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || access.needsAuthentication())return@launch
                if(model==null)model=ViewModelProvider(this@MainActivity)["journal-session-${++generation}",JournalViewModel::class.java]
                // The export wizard needs the Activity's SAF launcher; it cannot start a picker itself.
                model?.exportLauncher={kind,payload,fileName->launchFile(
                    FileRequest(kind,ownerId=runtime.resources().material.id,payload=payload),fileName)}
                covered=false;error=null;after?.invoke();restoreFile()
            }catch(e:CancellationException){throw e}
            catch(_:Exception){covered=true;error="无法打开加密资料，原文件已保留。请勿清除应用数据。"}
            finally {busy=false}
        }
    }
    private fun openRestore() {
        if(covered || busy || access.needsAuthentication())return
        restoreController.leave();restoreOpen=true
    }
    private fun chooseRestoreFile() {
        if(covered || busy)return
        busy=true
        scope.launch {
            try {
                restoreController.leave()
                val intent=withContext(Dispatchers.IO) {
                    restoreRequestId?.let {files.clearIf(it)}
                    restoreRequestId=null
                    files.begin(FileRequest("RESTORE",ownerId=runtime.resources().material.id))
                }
                check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                fileLauncher.launch(intent)
            }catch(_:Exception){notice="暂时无法选择备份，请重新打开资料库后重试。"}
            finally {busy=false}
        }
    }    private fun cancelRestore() {
        if(busy || restoreController.state.value.stage=="RESTORING")return
        restoreController.leave();restoreOpen=false
        val id=restoreRequestId;restoreRequestId=null
        scope.launch {id?.let {withContext(Dispatchers.IO){files.clearIf(it)}}}
    }
    private fun confirmRestore() {
        val state=restoreController.state.value
        if(busy || covered || !state.acknowledged || state.stage!="CONFIRM")return
        busy=true
        // Freeze the confirmation UI immediately; draft flushing may suspend before the actual commit.
        restoreController.confirm {
            opening?.cancelAndJoin()
            busy=true
            model?.flushDraft()?.join()
            check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !access.needsAuthentication())
            model?.clearSensitiveCloud();clearPending()
            model=null;viewModelStore.clear();navigationEpoch++;covered=true
        }
        restoreCommit=scope.launch {
            try {
                restoreController.state.first {it.stage in setOf("DONE","ERROR")}
                val id=restoreRequestId;restoreRequestId=null
                id?.let {withContext(Dispatchers.IO){files.clearIf(it)}}
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){error="恢复状态需要重新核对，请重新打开资料库。"}
            finally {busy=false;restoreCommit=null}
        }
    }    private fun reopenRestoredLibrary() {
        if(busy)return
        restoreController.leave();restoreOpen=false;covered=true;model=null;viewModelStore.clear();navigationEpoch++
        if(keys.strict() || access.needsAuthentication())authenticate("UNLOCK")
        else openModel()
    }
    private fun launchFile(request: FileRequest,name: String?) {
        // 静默返回会让用户以为按钮失效；这里对可解释的状态给出提示。
        if(covered || access.needsAuthentication())return
        if(busy){notice="正在处理上一次操作，请稍候再试。";return}
        busy=true
        scope.launch {
            try {val intent=withContext(Dispatchers.IO){files.begin(request,name)};check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED));fileLauncher.launch(intent)}
            catch(_:Exception){notice="无法打开文件选择器，请处理上次选择或稍后重试。";restoreFile()}
            finally {busy=false}
        }
    }
    private fun restoreFile() {
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO){files.read()} ?: return@launch
                if(covered || access.needsAuthentication())return@launch
                if(result.request.kind=="RESTORE") {
                    if(!result.returned){fileResult=result;return@launch}
                    val vaultId=withContext(Dispatchers.IO){runtime.resources().material.id}
                    if(result.request.ownerId!=vaultId) {
                        withContext(Dispatchers.IO){files.clearIf(result.id)}
                        notice="资料库已变化，请重新选择恢复文件。";return@launch
                    }
                    val same=restoreRequestId==result.id && restoreOpen && restoreController.state.value.document==result.uris.single()
                    restoreRequestId=result.id;fileResult=null;restoreOpen=true
                    if(!same && restoreController.state.value.stage in setOf("SELECT","KEY","ERROR"))restoreController.select(result.uris.single())
                } else if(result.returned && !result.confirm && result.request.kind !in setOf("REPLACE","BACKUP"))applyFile(result) else fileResult=result
            }catch(_:Exception){fileResult=FileResult("unreadable",FileRequest("PDF"));notice="无法恢复文件选择状态，可取消这次选择后重试。原资料已保留。"}
        }
    }
    private fun discardFile() {
        if(busy)return
        scope.launch {try {withContext(Dispatchers.IO){files.clear()};fileResult=null}catch(_:Exception){notice="文件选择状态未清理，请稍后重试。"}}
    }
    private fun applyFile(result: FileResult) {
        if(covered || busy || access.needsAuthentication() || !result.canApply)return
        busy=true
        applyingFile=scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(!access.needsAuthentication() && access.foreground)
                    val latest=requireNotNull(files.read());require(latest==result && result.returned)
                    val importer=runtime.resources().importer
                    val request=result.request;val uris=result.uris.map(android.net.Uri::parse)
                    when(request.kind) {
                        "IMAGES","PDF","CAMERA"->importer.enqueueHandoff(request.ownerId,request.medication,uris,result.id)
                        "EXPORT"->importer.exportOriginal(requireNotNull(request.targetId),uris.single(),request.revision)
                        "BACKUP"->{
                            require(request.ownerId==runtime.resources().material.id) {"资料库已变化，请重新选择备份位置"}
                            files.startBackup(result.id)
                            backupProgress.value="正在准备备份"
                            runtime.backupExporter().export(uris.single()){backupProgress.value=it}
                        }
                        "SAFETY_EXPORT"->{
                            require(request.ownerId==runtime.resources().material.id) {"资料库已变化，请重新选择保存位置"}
                            files.startBackup(result.id)
                            backupProgress.value="正在导出并核对安全副本"
                            SafetyCopyExporter(applicationContext).export(cn.yunji.journal.core.backup.SafetyCopy(requireNotNull(request.targetId),requireNotNull(request.sourceSize),requireNotNull(request.revision)),uris.single())
                        }
                        "REPLACE"->importer.replaceOriginal(requireNotNull(request.targetId),uris.single(),request.revision)
                        "EXPORT_DATA","SUMMARY_PDF"->{
                            require(request.ownerId==runtime.resources().material.id) {"资料库已变化，请重新选择保存位置"}
                            cn.yunji.journal.data.ExportRequestCodec.decode(request.payload)
                            files.startBackup(result.id)
                            val target=uris.single();val kind=request.kind;val payload=request.payload
                            // The wizard owns progress and result reporting; the Activity only hands over the URI.
                            withContext(Dispatchers.Main){requireNotNull(model).export.apply(target,kind,payload)}
                        }
                        "RETRY"->importer.retry(requireNotNull(request.targetId),uris.single())
                    }
                    files.clearIf(result.id,keepCamera=true)
                }
                fileResult=null;notice=if(result.request.kind=="SAFETY_EXPORT")"安全副本已导出并通过文件回读校验。请保留该副本对应的恢复密钥。" else if(result.request.kind=="BACKUP")"加密备份已保存，并已通过原件及恢复数据校验。请单独保管恢复密钥。" else if(result.request.kind=="EXPORT")"原件已导出。" else if(result.request.kind=="REPLACE")"原件已替换并重新加密。" else if(result.request.kind in setOf("EXPORT_DATA","SUMMARY_PDF"))if(result.request.kind=="SUMMARY_PDF")"正在生成就诊摘要，结果会显示在导出页。" else "正在导出结构化数据包，结果会显示在导出页。" else "文件已加入处理队列。"
            }catch(e:CancellationException){
                if(result.request.kind in setOf("BACKUP","SAFETY_EXPORT"))withContext(NonCancellable+Dispatchers.IO){files.clearIf(result.id);fileResult=null}
                throw e
            }
            catch(_:BackupReplayException){
                fileResult=withContext(Dispatchers.IO){files.read()}
                notice="上次备份已开始，请取消这次选择并重新导出。原文件已保留。"
            }
            catch(e:Exception){
                if(result.request.kind in setOf("BACKUP","SAFETY_EXPORT")){
                    withContext(NonCancellable+Dispatchers.IO){files.clearIf(result.id)};fileResult=null
                    notice=if(e is BackupDestinationOccupiedException)"所选位置已有内容，请创建新的备份文件。原文件已保留。" else if(e is BackupDestinationCleanupException)"备份未完成，系统无法删除刚创建的不完整文件。请到保存位置手动删除该文件，再新建文件重试。原资料保持不变。" else if(result.request.kind=="SAFETY_EXPORT")"安全副本未导出完成，所选位置可能留下部分文件。请创建新文件重试，本机副本已保留。" else "备份未完成，请检查保存位置和剩余空间后重新导出。原资料保持不变。"
                }else {fileResult=result;notice="文件或所属记录已变化，操作未完成。可取消选择后重新核对。"}
            }
            finally {
                backupProgress.value=null
                busy=false
                if(covered && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !authenticationRunning && !access.needsAuthentication())openModel()
            }
        }
    }
    private fun securityAction(action: String) {
        when {
            action=="LOCK_NOW"->{opening?.cancel();applyingFile?.cancel();covered=true;scope.launch {applyingFile?.join();background?.join();model?.flushDraft()?.join();showSecurity=false;access.lock();model=null;viewModelStore.clear();runtime.clearPreviews();if(keys.strict())runtime.revoke()}}
            action.startsWith("TIMEOUT_")->{settings.setTimeoutMinutes(action.removePrefix("TIMEOUT_").toInt());securityTick++}
            action=="TOGGLE_LOCK"->authenticate(if(settings.lockEnabled())"DISABLE_LOCK" else "ENABLE_LOCK")
            action=="TOGGLE_STRICT"->authenticate(if(keys.strict())"DISABLE_STRICT" else "ENABLE_STRICT")
        }
    }
    private fun authenticate(purpose: String) {
        if(busy || authenticationRunning || restoreController.state.value.stage=="RESTORING")return
        busy=true;authenticationRunning=true;covered=true;error=null;opening?.cancel()
        scope.launch {
            try {
                background?.join();model?.flushDraft()?.join();model=null;viewModelStore.clear()
                if(keys.strict())runtime.revoke() else runtime.clearPreviews()
                val crypto=withContext(Dispatchers.IO){when {purpose=="ENABLE_STRICT"->keys.prepareEnableStrict();keys.strict()->keys.prepareStrictUnlock(purpose=="DISABLE_STRICT");else->null}}
                request=crypto
                if(!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))throw IllegalStateException("请返回应用后重新解锁")
                val authenticators=if(crypto!=null && Build.VERSION.SDK_INT<30)BiometricManager.Authenticators.BIOMETRIC_STRONG else
                    (if(crypto!=null)BiometricManager.Authenticators.BIOMETRIC_STRONG else BiometricManager.Authenticators.BIOMETRIC_WEAK) or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                require(BiometricManager.from(this@MainActivity).canAuthenticate(authenticators)==BiometricManager.BIOMETRIC_SUCCESS){"请先设置系统锁屏凭据或录入可用的生物识别；旧设备严格模式需要强生物识别。"}
                val info=BiometricPrompt.PromptInfo.Builder().setTitle("解锁孕迹").setSubtitle("使用系统认证保护本机资料").setAllowedAuthenticators(authenticators).apply {
                    if(authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL==0)setNegativeButtonText("取消")
                }.build()
                val callback=object:BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {super.onAuthenticationSucceeded(result);completeAuthentication(purpose,crypto,result)}
                    override fun onAuthenticationError(code: Int,message: CharSequence) {
                        super.onAuthenticationError(code,message);crypto?.let {keys.cancel(it)};request=null;authenticationRunning=false;busy=false;error="认证未完成，原资料与草稿已保留。"
                        if(purpose=="NOTIFICATION")clearPending()
                    }
                }
                prompt=BiometricPrompt(this@MainActivity,ContextCompat.getMainExecutor(this@MainActivity),callback).also {if(crypto==null)it.authenticate(info) else it.authenticate(info,BiometricPrompt.CryptoObject(crypto.cipher))}
                busy=false
            }catch(e:CancellationException){request?.let {keys.cancel(it)};request=null;authenticationRunning=false;busy=false;throw e}
            catch(e:Exception){request?.let {keys.cancel(it)};request=null;authenticationRunning=false;busy=false;error=e.message ?: "无法认证，原资料已保留"}
        }
    }
    private fun completeAuthentication(purpose: String,crypto: DeviceKeyStore.Authentication?,result: BiometricPrompt.AuthenticationResult) {
        busy=true
        scope.launch {
            try {
                val material=crypto?.let {withContext(Dispatchers.IO){keys.complete(it,requireNotNull(result.cryptoObject?.cipher))}}
                request=null
                if(purpose in setOf("ENABLE_STRICT","DISABLE_STRICT"))runtime.revoke()
                if(material!=null) {if(keys.strict())runtime.authenticated(material) else material.master.fill(0)}
                when(purpose){"ENABLE_LOCK","ENABLE_STRICT"->settings.setLockEnabled(true);"DISABLE_LOCK"->{require(!keys.strict());settings.setLockEnabled(false)}}
                settings.setStrict(keys.strict());access.unlock(verified=true);securityTick++
                authenticationRunning=false
                pendingAuthorized=purpose=="NOTIFICATION"
                openModel {
                    if(purpose=="NOTIFICATION")scope.launch {
                        val captured=pending ?: return@launch
                        val event=withContext(Dispatchers.IO){runtime.resources().reminders.resolveAction(captured.key,captured.generation)}
                        if(event==null){clearPending();notice="提醒已变化或已处理，请在应用中核对。"}else pendingEvent=event
                    }
                    ImportWorker.recover(applicationContext);TrashWorker.recover(applicationContext)
                    if(purpose!="NOTIFICATION")ReminderWorker.enqueue(applicationContext)
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(BuildConfig.DEBUG)android.util.Log.w("VaultSecurity","Authentication completion failed: ${e.javaClass.simpleName}");request?.let {keys.cancel(it)};request=null;authenticationRunning=false;busy=false;covered=true;error="认证或密钥切换未完成，原文件已保留，请重新解锁。"}
        }
    }
    private fun confirmPending() {
        val action=pending ?: return
        if(!pendingAuthorized || busy)return
        busy=true
        committing=scope.launch {
            try {withContext(Dispatchers.IO){runtime.resources().reminders.confirmAuthenticatedAction(action.key,action.generation,action.action)};clearPending();notice="操作已记录到本机。"}
            catch(e:CancellationException){throw e}
            catch(_:Exception){clearPending();notice="提醒或认证状态已变化，未覆盖原记录，请重新核对。"}
            finally {busy=false}
        }
    }
}

/**
 * FLAG_SECURE policy for the main window. Verification builds are the only ones allowed to expose
 * app content to `adb screencap`; this is decided at build time via `-PvisualVerification=true`.
 */
internal fun secureFlagRequired(visualVerification: Boolean = BuildConfig.VISUAL_VERIFICATION): Boolean = !visualVerification
