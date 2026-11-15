package cn.yunji.journal.feature

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.yunji.journal.core.designsystem.DesignIcon
import cn.yunji.journal.core.designsystem.JournalFullScreenHeader
import cn.yunji.journal.core.designsystem.JournalTextAction
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunji.journal.BuildConfig
import cn.yunji.journal.core.database.AttachmentEntity
import cn.yunji.journal.data.DocumentPreview
import cn.yunji.journal.core.security.LocalFileLaunch
import cn.yunji.journal.core.security.FileRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.*

internal data class ImageTile(val bitmap: Bitmap, val rect: Rect)

/** Decode only the visible source region, with at most four million pixels per tile. */
internal class ImageRegion(private val preview: DocumentPreview) : AutoCloseable {
    private val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val width: Int
    val height: Int
    val rawToLogical = Matrix()
    private val logicalToRaw = Matrix()
    private val readMutex = Mutex()
    private var closed = false
    @Suppress("DEPRECATION") private val decoder: BitmapRegionDecoder?
    init {
        BitmapFactory.decodeFile(preview.file.path, bounds)
        val w = bounds.outWidth.toFloat(); val h = bounds.outHeight.toFloat()
        require(w > 0 && h > 0) { "图片已损坏或格式无法预览" }
        val orientation = runCatching { ExifInterface(preview.file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        width = if (orientation in 5..8) h.toInt() else w.toInt()
        height = if (orientation in 5..8) w.toInt() else h.toInt()
        val affine = when (orientation) {
            2 -> floatArrayOf(-1f,0f,w, 0f,1f,0f, 0f,0f,1f)
            3 -> floatArrayOf(-1f,0f,w, 0f,-1f,h, 0f,0f,1f)
            4 -> floatArrayOf(1f,0f,0f, 0f,-1f,h, 0f,0f,1f)
            5 -> floatArrayOf(0f,1f,0f, 1f,0f,0f, 0f,0f,1f)
            6 -> floatArrayOf(0f,-1f,h, 1f,0f,0f, 0f,0f,1f)
            7 -> floatArrayOf(0f,-1f,h, -1f,0f,w, 0f,0f,1f)
            8 -> floatArrayOf(0f,1f,0f, -1f,0f,w, 0f,0f,1f)
            else -> floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        }
        rawToLogical.setValues(affine); rawToLogical.invert(logicalToRaw)
        @Suppress("DEPRECATION")
        decoder = runCatching { BitmapRegionDecoder.newInstance(preview.file.path, false) }.getOrNull()
        preview.whenClosed {close()}
    }
    suspend fun tile(viewport: IntSize, zoom: Float, pan: Offset): ImageTile = readMutex.withLock {
        var decoded: ImageTile? = null
        try { withContext(Dispatchers.IO) { synchronized(this@ImageRegion) { decode(viewport, zoom, pan).also { decoded = it } } } }
        catch (e: CancellationException) { decoded?.bitmap?.recycle(); throw e }
    }
    private fun decode(viewport: IntSize, zoom: Float, pan: Offset): ImageTile {
        check(!closed)
        val scale = min(viewport.width.toFloat() / width, viewport.height.toFloat() / height) * zoom
        val left = (viewport.width - width * scale) / 2 + pan.x
        val top = (viewport.height - height * scale) / 2 + pan.y
        val visible = RectF(-left / scale, -top / scale, (viewport.width - left) / scale, (viewport.height - top) / scale)
        logicalToRaw.mapRect(visible)
        val rect = if (decoder != null) Rect(floor(visible.left.toDouble()).toInt().coerceIn(0, bounds.outWidth - 1),
            floor(visible.top.toDouble()).toInt().coerceIn(0, bounds.outHeight - 1),
            ceil(visible.right.toDouble()).toInt().coerceIn(1, bounds.outWidth),
            ceil(visible.bottom.toDouble()).toInt().coerceIn(1, bounds.outHeight)) else Rect(0, 0, bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (sample * 2 <= 1f / scale) sample *= 2
        while ((rect.width().toLong() / sample) * (rect.height() / sample) > 4_000_000) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = if (decoder != null) decoder.decodeRegion(rect, options) else BitmapFactory.decodeFile(preview.file.path, options)
        return ImageTile(requireNotNull(bitmap) { "图片无法预览" }, rect)
    }
    @Synchronized override fun close() { closed = true; decoder?.recycle(); preview.close() }
}

@Composable
internal fun DocumentPreviewDialog(attachment: AttachmentEntity, model: JournalViewModel, close: () -> Unit, remove: () -> Unit) {
    val compact = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val fileLaunch=LocalFileLaunch.current
    val latest by remember(attachment.id) { model.documents.observeAttachment(attachment.id) }.collectAsStateWithLifecycle(attachment)
    LaunchedEffect(latest) { if (latest == null || latest?.deletedAt != null) close() }
    val initialObject = remember(attachment.id) { attachment.objectId }
    LaunchedEffect(latest?.objectId) { if (latest != null && latest?.objectId != initialObject) close() }
    var replacePending by remember { mutableStateOf<Uri?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) model.exportDocument(attachment.id, uri)
    }
    val replacer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) replacePending = uri
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val dark = isSystemInDarkTheme()
        DisposableEffect(view, dark) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (!BuildConfig.DEBUG) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window?.let {
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            onDispose { }
        }
        Surface(Modifier.fillMaxSize().testTag("document-preview"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                JournalFullScreenHeader(
                    title = if (attachment.mime == "application/pdf") "PDF 预览" else "图片预览",
                    subtitle = attachment.name,
                    onBack = close,
                    compact = compact,
                ) {
                    JournalTextAction("导出", onClick = {
                        val row = latest ?: attachment
                        if (fileLaunch != null) fileLaunch(FileRequest("EXPORT", medication = row.medicationId != null, targetId = row.id, revision = row.revision), exportName(row))
                        else runCatching { exporter.launch(exportName(row)) }
                    })
                    JournalTextAction("替换", onClick = {
                        val row = latest ?: attachment
                        if (fileLaunch != null) fileLaunch(FileRequest("REPLACE", medication = row.medicationId != null, targetId = row.id, revision = row.revision), null)
                        else runCatching { replacer.launch(if (row.medicationId != null) arrayOf("image/*") else arrayOf("image/*", "application/pdf")) }
                    })
                    JournalTextAction("删除", Modifier.testTag("preview-delete"), danger = true, onClick = remove)
                }
                if (attachment.mime == "application/pdf") PdfDocumentView(attachment.id, model, Modifier.weight(1f).fillMaxWidth(),compact=compact)
                else ZoomableDocument(attachment.id, model, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    replacePending?.let { uri ->
        AlertDialog(onDismissRequest = { replacePending = null }, modifier = Modifier.testTag("replace-dialog"),
            title = { Text("替换这份资料的原件？") },
            text = { Text("新文件会重新加密并校验，名称、分类、报告日期、备注和所在产检保持不变；当前原件会从本应用删除。") },
            confirmButton = { TextButton({ replacePending = null; model.replaceDocument(attachment.id, uri, (latest ?: attachment).revision) }, Modifier.testTag("confirm-replace-original")) { Text("替换并重新加密") } },
            dismissButton = { TextButton({ replacePending = null }) { Text("取消") } })
    }
}

@Composable
private fun ZoomableDocument(id: String, model: JournalViewModel, modifier: Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) foreground = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) foreground = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var renderer by remember(id) { mutableStateOf<ImageRegion?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, foreground, attempt) {
        if (!foreground) return@LaunchedEffect
        var source: DocumentPreview? = null
        var region: ImageRegion? = null
        try {
            error = null
            source = model.documents.openPreview(id)
            withContext(Dispatchers.IO) { region = ImageRegion(source!!) }
            renderer = region
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "暂时无法打开图片，原件已保留" }
        finally {
            renderer = null
            withContext(NonCancellable + Dispatchers.IO) { if (region != null) region?.close() else source?.close() }
        }
    }
    var zoom by rememberSaveable(id) { mutableFloatStateOf(1f) }
    var pan by remember(id) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var tile by remember(renderer) { mutableStateOf<ImageTile?>(null) }
    val current = renderer
    fun clamp(value: Offset, scale: Float): Offset {
        val image = renderer ?: return Offset.Zero
        if (viewport.width == 0 || viewport.height == 0) return Offset.Zero
        val fit = min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
        val x = max(0f, (image.width * fit * scale - viewport.width) / 2)
        val y = max(0f, (image.height * fit * scale - viewport.height) / 2)
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    fun transform(centroid: Offset, delta: Offset, factor: Float) {
        val next = (zoom * factor).coerceIn(1f, 20f)
        val anchor = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        pan = clamp((pan - anchor) * (next / zoom) + anchor + delta, next)
        zoom = next
    }
    LaunchedEffect(current, viewport) { pan = clamp(pan, zoom) }
    LaunchedEffect(current, viewport, zoom, pan) {
        val image = current ?: return@LaunchedEffect
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        delay(24)
        try { tile = withContext(Dispatchers.IO) { image.tile(viewport, zoom, pan) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "这一区域暂时无法显示，请重试" }
    }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = it }, contentAlignment = Alignment.Center) {
            if (current != null) Canvas(Modifier.fillMaxSize().testTag("document-image")
                .semantics { contentDescription = "产检图片，可缩放"; stateDescription = "${(zoom * 100).roundToInt()}%" }
                .pointerInput(current, viewport) { detectTapGestures(onDoubleTap = { at ->
                    if (zoom > 1.1f) { zoom = 1f; pan = Offset.Zero } else transform(at, Offset.Zero, 3f)
                }) }
                .pointerInput(current, viewport) { detectTransformGestures { centroid, delta, factor, _ -> transform(centroid, delta, factor) } }) {
                val frame = tile ?: return@Canvas
                val scale = min(size.width / current.width, size.height / current.height) * zoom
                val left = (size.width - current.width * scale) / 2 + pan.x
                val top = (size.height - current.height * scale) / 2 + pan.y
                val matrix = Matrix(current.rawToLogical).apply { postScale(scale, scale); postTranslate(left, top) }
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save(); native.concat(matrix)
                    native.drawBitmap(frame.bitmap, null, RectF(frame.rect), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    native.restore()
                }
            }
            if (error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, Modifier.padding(24.dp))
                TextButton({ attempt++ }) { Text("重新打开") }
            } else if (foreground && (current == null || tile == null)) CircularProgressIndicator()
            else if (!foreground) Text("返回应用后重新加载图片")
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("双指缩放 · 双击放大 · 拖动查看", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton({ zoom = 1f; pan = Offset.Zero }) { Text("复位") }
        }
    }
}
