package cn.yunji.journal.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class DrugForm(val requestId: String=UUID.randomUUID().toString(),val id: String?=null,val pregnancyId: String="",val expectedRevision: Long?=null,
    val name: String="",val specification: String="",val dosageForm: String="") {
    val draftKey get()=id ?: "new:$pregnancyId"
}

object DrugRules {
    /** Names may evolve, but every saved plan keeps the exact snapshot it was created with. */
    fun normalize(form: DrugForm): DrugForm {
        val name = form.name.trim()
        require(name.length in 1..100) { "药品名称需1–100字" }
        require(form.specification.length <= 120) { "规格最多120字" }
        require(form.dosageForm.length <= 40) { "剂型最多40字" }
        return form.copy(name = name, specification = form.specification.trim(), dosageForm = form.dosageForm.trim())
    }
}
