package cn.yunji.journal.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunji.journal.core.designsystem.*
import cn.yunji.journal.data.DrugForm
import cn.yunji.journal.data.DrugRules

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DrugCatalogScreen(pregnancyId: String?,model: JournalViewModel,edit: (String?)->Unit,photos: (String)->Unit) {
    if(pregnancyId==null){Inset {Text("请先创建或恢复孕期档案。")};return}
    val drugs by remember(pregnancyId,model){model.medications.medications(pregnancyId)}.collectAsStateWithLifecycle(emptyList())
    val busy by model.busy.collectAsStateWithLifecycle()
    var deleting by remember {mutableStateOf<String?>(null)}
    var query by rememberSaveable(pregnancyId) { mutableStateOf("") }
    var selectedId by rememberSaveable(pregnancyId) { mutableStateOf<String?>(null) }
    val visible = drugs.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) ||
        it.specification.contains(query.trim(), ignoreCase = true) || it.dosageForm.contains(query.trim(), ignoreCase = true) }
    val chosenDrug = visible.firstOrNull { it.id == selectedId } ?: visible.firstOrNull()
    LazyColumn(Modifier.testTag("drug-catalog"),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Inset {OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth().testTag("drug-search"),
            placeholder={Text("搜索药品")},singleLine=true,shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp))}}
        item {Inset {Text("药品目录",style=MaterialTheme.typography.titleMedium)}}
        if(drugs.isEmpty())item {Inset {JournalEmptyState("还没有药品，可以先录入基本信息，再创建用药方案。")}}
        else if(visible.isEmpty())item {Inset {JournalEmptyState("没有找到相关药品", "试试其他关键词")}}
        items(visible,key={it.id},contentType={"drug"}) {drug ->Inset {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordRow(drug.name, listOf(drug.specification, drug.dosageForm).filter {it.isNotBlank()}.joinToString(" · ").ifBlank {"查看药品资料"},
                    "ic-pill.svg", {selectedId=drug.id}, Modifier.testTag("drug-${drug.id}")
                        .then(if (chosenDrug?.id == drug.id) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)) else Modifier)
                        .semantics { selected = chosenDrug?.id == drug.id })
                if (chosenDrug?.id == drug.id) JournalCard {
                    JournalSectionTitle("资料管理")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        JournalTextAction("药品信息", Modifier.testTag("edit-drug-${drug.id}"), enabled = !busy,
                            onClick = {edit(drug.id)})
                        JournalTextAction("包装照片", Modifier.testTag("drug-photos-${drug.id}"), enabled = !busy,
                            onClick = {photos(drug.id)})
                    }
                    JournalDangerButton("删除药品", Modifier.testTag("delete-drug-${drug.id}"), enabled = !busy,
                        onClick = { deleting=drug.id })
                }
            }
        }}
        item {Inset {JournalButton("新增药品",enabled=!busy,onClick={edit(null)})}}
        item {Inset {Text("修改基础信息不改变旧方案和服药记录中的药品依据。包装照片加密保存在本机。",style=MaterialTheme.typography.bodySmall)}}
    }
    deleting?.let {id ->RootDeletionDialog("MEDICATION",id,model,{deleting=null})}
}

@Composable
internal fun DrugEditorScreen(form: DrugForm,model: JournalViewModel,busy: Boolean) {
    var attempted by rememberSaveable(form.id, form.pregnancyId) { mutableStateOf(false) }
    val error = runCatching { DrugRules.normalize(form); null }.getOrElse { it.message ?: "请检查填写内容" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        JournalCard {
            JournalField(form.name,{model.updateDrug(form.copy(name=it))}, "药品名称",
                modifier=Modifier.testTag("drug-name"), enabled=!busy, isError=form.name.length>100)
            JournalField(form.specification,{model.updateDrug(form.copy(specification=it))}, "规格（选填）",
                modifier=Modifier.testTag("drug-specification"), enabled=!busy, isError=form.specification.length>120)
            JournalField(form.dosageForm,{model.updateDrug(form.copy(dosageForm=it))}, "剂型（选填）",
                modifier=Modifier.testTag("drug-form"), enabled=!busy, isError=form.dosageForm.length>40)
        }
        JournalSectionTitle("包装照片")
        RecordRow("保存后添加包装照片", "在药品目录中添加或查看加密照片", "ic-image.svg")
        JournalSectionTitle("关联方案")
        RecordRow("添加到用药方案", "先保存药品，再设置剂量与时间", "ic-pill.svg")
        if (attempted && error != null) JournalError(error, Modifier.testTag("drug-validation"))
        JournalButton(if (busy) "正在保存…" else "保存药品",enabled=!busy,onClick={attempted=true;if(error==null)model.saveDrug()},modifier=Modifier.testTag("save-drug"))
        Text("基础信息可单独保存；返回会保留草稿。修改后，已保存方案仍保留原名称、规格与剂型。",style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}
