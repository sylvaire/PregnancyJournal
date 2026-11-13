package cn.yunji.journal.feature

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import cn.yunji.journal.core.designsystem.JournalTextAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.yunji.journal.data.DocumentPreview
import kotlinx.coroutines.*
import kotlin.math.*

/** One native page at a time, no bitmap cache; close waits for native rendering before removing plaintext. */
internal class PdfSession(private val source: DocumentPreview) : AutoCloseable {
    private val descriptor: ParcelFileDescriptor = try {
        // API 26 的 pdfium 会把一次坏 PDF 的失败记成进程级状态，先做结构检查再交给原生解析。
        cn.yunji.journal.data.PdfReadability.requireReadable(source.file)
        ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (e: Throwable) {
        source.close()
        throw e
    }
    private val renderer = try { PdfRenderer(descriptor) } catch (e: Throwable) {
        descriptor.close(); throw e
    }
    val pageCount = renderer.pageCount
    private var closed = false
    init {source.whenClosed {close()}}

    @Synchronized fun render(index: Int, requestedWidth: Int): Bitmap {
        check(!closed)
        require(index in 0 until pageCount)
        return renderer.openPage(index).use { page ->
            val ratio = page.height.toDouble() / page.width
            // Both long-edge and total-pixel bounds also cover unusually shaped pages.
            val width = min(min(requestedWidth.coerceIn(1, 8192).toDouble(), sqrt(4_000_000.0 / ratio)), 8192.0 / ratio).toInt().coerceAtLeast(1)
            val height = (width * ratio).toInt().coerceIn(1, 8192)
            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val matrix = Matrix().apply { setScale(width.toFloat() / page.width, height.toFloat() / page.height) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (e: Throwable) { bitmap.recycle(); throw e }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { renderer.close() } finally { try { descriptor.close() } finally { source.close() } }
    }
}

@Composable
internal fun PdfDocumentView(id: String, model: JournalViewModel, modifier: Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var session by remember(id) { mutableStateOf<PdfSession?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    var page by rememberSaveable(id) { mutableIntStateOf(0) }
    var zoom by rememberSaveable(id) { mutableIntStateOf(1) }
    var jumping by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(id, foreground, attempt) {
        if (!foreground) return@LaunchedEffect
        var source: DocumentPreview? = null
        var opened: PdfSession? = null
        try {
            error = null
            source = model.documents.openPreview(id)
            withContext(Dispatchers.IO) { opened = PdfSession(source!!) }
            require(opened!!.pageCount > 0)
            page = page.coerceIn(0, opened!!.pageCount - 1)
            session = opened
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "无法预览此PDF，可能已损坏或设置了密码。加密保存的原件仍然保留。" }
        finally {
            session = null
            withContext(NonCancellable + Dispatchers.IO) { if (opened != null) opened?.close() else source?.close() }
        }
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var image by remember(session, page) { mutableStateOf<Bitmap?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var renderError by remember(session, page) { mutableStateOf(false) }
    val current = session
    LaunchedEffect(current, page, viewport.width, zoom) {
        if (current == null || viewport.width == 0) return@LaunchedEffect
        val requestedPage = page
        val requestedWidth = viewport.width * zoom
        var pending: Bitmap? = null
        try {
            rendering = true; renderError = false
            withContext(Dispatchers.IO) { pending = current.render(requestedPage, requestedWidth) }
            image = pending; pending = null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { renderError = true }
        finally { pending?.recycle(); rendering = false }
    }
    val density = LocalDensity.current
    val xScroll = rememberScrollState()
    val yScroll = rememberScrollState()
    LaunchedEffect(page, zoom) { xScroll.scrollTo(0); yScroll.scrollTo(0) }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = it }, contentAlignment = Alignment.Center) {
            if (current != null && foreground) {
                val bitmap = image
                if (bitmap != null) {
                    val fit = min(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
                    val width = with(density) { (bitmap.width * fit * zoom).toDp() }
                    val height = with(density) { (bitmap.height * fit * zoom).toDp() }
                    Box(Modifier.fillMaxSize().horizontalScroll(xScroll).verticalScroll(yScroll), contentAlignment = Alignment.Center) {
                        Image(bitmap.asImageBitmap(), "PDF第${page + 1}页", Modifier.requiredSize(width, height).testTag("document-pdf")
                            .pointerInput(id, page, zoom) { detectTapGestures(onDoubleTap = { zoom = if (zoom == 1) 3 else 1 }) })
                    }
                }
                if (renderError) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("本页无法显示，原件已保留")
                    JournalTextAction("重试", onClick = { attempt++ })
                }
                if (rendering) CircularProgressIndicator()
            } else if (error != null) Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!)
                JournalTextAction("重试", onClick = { attempt++ })
            } else if (foreground) CircularProgressIndicator()
        }
        if (current != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton({ page--; zoom = 1 }, enabled = page > 0) { Text("上一页") }
                TextButton({ jumping = true }, Modifier.testTag("pdf-page-count")) { Text("${page + 1} / ${current.pageCount}") }
                TextButton({ page++; zoom = 1 }, enabled = page < current.pageCount - 1) { Text("下一页") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton({ zoom-- }, enabled = zoom > 1) { Text("缩小") }
                TextButton({ zoom = 1 }) { Text(if (zoom == 1) "适合页面" else "${zoom * 100}% · 复位") }
                TextButton({ zoom++ }, enabled = zoom < 4) { Text("放大") }
            }
        }
    }
    if (jumping && current != null) {
        var number by rememberSaveable(id) { mutableStateOf((page + 1).toString()) }
        val target = number.toIntOrNull()
        AlertDialog(onDismissRequest = { jumping = false }, title = { Text("跳转到页码") },
            text = { OutlinedTextField(number, { number = it.filter(Char::isDigit).take(8) }, singleLine = true, label = { Text("1至${current.pageCount}") }, modifier = Modifier.testTag("pdf-page-input")) },
            confirmButton = { TextButton({ page = target!! - 1; zoom = 1; jumping = false }, enabled = target != null && target in 1..current.pageCount) { Text("跳转") } },
            dismissButton = { TextButton({ jumping = false }) { Text("取消") } })
    }
}
