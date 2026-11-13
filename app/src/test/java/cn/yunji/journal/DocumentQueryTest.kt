package cn.yunji.journal

import cn.yunji.journal.core.database.ImportJobEntity
import cn.yunji.journal.data.queryDocuments
import org.junit.Assert.*
import org.junit.Test

class DocumentQueryTest {
    @Test fun suppliedNotesAndHospitalAreSearchableWithoutClaimingFileContent() {
        val job=ImportJobEntity("a","v","","合成报告",1,"READY")
        val attachment=cn.yunji.journal.core.database.AttachmentEntity("a","v","合成报告","image/png",1,33,"p","c",null,null,null,null,null,1,
            hospital="测试医院",note="留存复查说明")
        assertEquals(1,queryDocuments(listOf(job),listOf(attachment),"复查","",0).size)
        assertEquals(1,queryDocuments(listOf(job),listOf(attachment),"测试医院","",0).size)
        assertTrue(queryDocuments(listOf(job),listOf(attachment),"图片中的未知文字","",0).isEmpty())
        assertTrue(queryDocuments(listOf(job),listOf(attachment.copy(deletedAt=1)),"复查","",0).isEmpty())
    }
    @Test fun nameSearchNormalizesWidthAndCaseAndSortsDeterministically() {
        val jobs = listOf(
            ImportJobEntity("b", "v", "", "Report B", 20, "READY"),
            ImportJobEntity("a", "v", "", "report A", 20, "FAILED"),
            ImportJobEntity("c", "v", "", "超声", 10, "READY"),
            ImportJobEntity("d", "v", "", "Report deleted", 40, "DELETED"),
        )
        assertEquals(listOf("a", "b"), queryDocuments(jobs, emptyList(), " ＲＥＰＯＲＴ ", "", 0).map { it.id })
        assertEquals(listOf("c", "a", "b"), queryDocuments(jobs, emptyList(), "", "", 1).map { it.id })
        assertEquals(listOf("a", "b", "c"), queryDocuments(jobs, emptyList(), "", "", 2).map { it.id })
        assertTrue(queryDocuments(jobs, emptyList(), "不存在", "", 0).isEmpty())
    }
}
