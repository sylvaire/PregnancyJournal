package cn.yunji.journal

import cn.yunji.journal.core.database.*
import cn.yunji.journal.domain.MeasurementRules
import org.junit.Assert.*
import org.junit.Test

class MeasurementRulesTest {
    @Test fun exactDecimalsRejectExponentAndOverprecision() {
        assertEquals("123456789012.123456",MeasurementRules.decimal("123456789012.123456"))
        assertEquals("0",MeasurementRules.decimal("000.000"))
        assertEquals("0.125",MeasurementRules.decimal("0.125000"))
        listOf("1e2","-1","NaN","1.0000001","1234567890123").forEach { text ->
            try { MeasurementRules.decimal(text); fail("Accepted invalid number") } catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun groupsKeepUnitScenarioMinutesAndSameInstantReadingsSeparate() {
        val first=MetricDefinitionEntity("d1","p","logical",1,"CUSTOM","测试","mmol/L","value",0)
        val second=first.copy(id="d2",version=2,unit="mg/dL")
        fun row(id: String,def: String="d1",scenario: String="POST_MEAL",minutes: Int?=120)=MeasurementEntity(id,"p",def,1000,"UTC",scenario,minutes,"","",1000,1000)
        val rows=listOf(row("a"),row("b"),row("c",minutes=60),row("d","d2"),row("e",scenario="FASTING",minutes=null),row("deleted").copy(deletedAt=1))
        val groups=MeasurementRules.groups(listOf(first,second),rows,rows.map {MeasurementValueEntity(it.id,"value","0.125")})
        assertEquals(4,groups.size)
        assertEquals(listOf("a","b"),groups.values.single {it.size==2}.map {it.measurement.id})
        assertEquals(5,groups.values.sumOf {it.size})
        assertTrue(groups.values.flatten().all {it.value=="0.125"})
    }
}
