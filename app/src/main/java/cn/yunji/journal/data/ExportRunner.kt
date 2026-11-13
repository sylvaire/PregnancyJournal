package cn.yunji.journal.data

import android.content.Context
import android.net.Uri
import cn.yunji.journal.core.security.VaultRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock

/** Boundary the export wizard uses so no database or key handling leaks into Compose state. */
interface ExportAccess {
    suspend fun preview(request: ExportRequest): Map<String, Int>
    suspend fun exportData(destination: Uri, request: ExportRequest): ExportResult
    suspend fun exportSummary(destination: Uri, request: ExportRequest): ExportResult
}

/** Builds a service per call from the current vault resources, so a revoked vault is never reused. */
class ExportRunner(
    private val context: Context,
    private val runtime: VaultRuntime,
    private val clock: Clock,
) : ExportAccess {
    private fun service(): ExportService {
        val resources = runtime.resources()
        return ExportService(context, resources.database, resources.importer, resources.material.id, clock)
    }

    override suspend fun preview(request: ExportRequest): Map<String, Int> =
        withContext(Dispatchers.IO) { service().preview(request) }

    override suspend fun exportData(destination: Uri, request: ExportRequest): ExportResult =
        withContext(Dispatchers.IO) { service().exportDataPackage(destination, request) }

    override suspend fun exportSummary(destination: Uri, request: ExportRequest): ExportResult =
        withContext(Dispatchers.IO) { service().exportSummaryPdf(destination, request) }
}
