package ru.czcheck.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultEvaluatorTest {

    private val now = 1_790_000_000_000L // 2026-09

    @Test
    fun introducedIsGreen() {
        val body = """
            {"codeFounded":true,"category":"milk","productName":"Молоко 3,2% 930 мл",
             "codeResolveData":{"gtin":"04607009780429"},
             "milkData":{"status":"INTRODUCED","producerName":"ООО Молзавод","expireDate":1795000000000}}
        """.trimIndent()
        val v = ResultEvaluator.evaluate(200, body, now)
        assertEquals(Level.OK, v.level)
        assertEquals("В обороте", v.title)
        assertEquals("Молоко 3,2% 930 мл", v.product)
        assertTrue(v.details.any { it.first == "Производитель" && it.second == "ООО Молзавод" })
        assertTrue(v.details.any { it.first == "GTIN" && it.second == "04607009780429" })
        assertTrue(v.details.any { it.first == "Товарная группа" && it.second == "Молочная продукция" })
    }

    @Test
    fun outerStatusHasPriority() {
        val v = ResultEvaluator.evaluate(200, """{"codeFounded":true,"outerStatus":"RETIRED","lpData":{"status":"INTRODUCED"}}""", now)
        assertEquals(Level.BAD, v.level)
        assertEquals("Выведен из оборота", v.title)
    }

    @Test
    fun notFoundIsRed() {
        val v = ResultEvaluator.evaluate(200, """{"codeFounded":false}""", now)
        assertEquals(Level.BAD, v.level)
        assertEquals("Код не найден в Честном знаке", v.title)
    }

    @Test
    fun wrongCodeIsRed() {
        val v = ResultEvaluator.evaluate(200, """{"status":"wrong"}""", now)
        assertEquals(Level.BAD, v.level)
    }

    @Test
    fun notIntroducedIsRed() {
        val v = ResultEvaluator.evaluate(200, """{"codeFounded":true,"shoesData":{"status":"APPLIED"}}""", now)
        assertEquals(Level.BAD, v.level)
        assertEquals("Не введён в оборот", v.title)
    }

    @Test
    fun soldIsRed() {
        val v = ResultEvaluator.evaluate(200, """{"codeFounded":true,"status":"SOLD"}""", now)
        assertEquals(Level.BAD, v.level)
    }

    @Test
    fun expiredIsRed() {
        val v = ResultEvaluator.evaluate(200, """{"codeFounded":true,"milkData":{"status":"INTRODUCED","expireDate":1700000000000}}""", now)
        assertEquals(Level.BAD, v.level)
        assertEquals("Истёк срок годности", v.title)
    }

    @Test
    fun expireDateAsIsoString() {
        val v = ResultEvaluator.evaluate(200, """{"codeFounded":true,"milkData":{"status":"INTRODUCED","expireDate":"2020-01-01T00:00:00Z"}}""", now)
        assertEquals(Level.BAD, v.level)
    }

    @Test
    fun foundWithoutStatusIsYellow() {
        val v = ResultEvaluator.evaluate(200, """{"codeFounded":true,"productName":"Кроссовки"}""", now)
        assertEquals(Level.WARN, v.level)
        assertEquals("Кроссовки", v.product)
    }

    @Test
    fun unknownStatusIsYellow() {
        val v = ResultEvaluator.evaluate(200, """{"codeFounded":true,"lpData":{"status":"SOMETHING_NEW"}}""", now)
        assertEquals(Level.WARN, v.level)
        assertTrue(v.title.contains("SOMETHING_NEW"))
    }

    @Test
    fun geoBlockIsGrey() {
        assertEquals(Level.ERROR, ResultEvaluator.evaluate(451, "<html>Unavailable</html>", now).level)
        assertEquals(Level.ERROR, ResultEvaluator.evaluate(451, """{"error":"blocked"}""", now).level)
    }

    @Test
    fun serverErrorIsGrey() {
        assertEquals(Level.ERROR, ResultEvaluator.evaluate(502, "Bad gateway", now).level)
        assertEquals(Level.ERROR, ResultEvaluator.evaluate(200, "", now).level)
    }

    @Test
    fun notFoundHttpIsRed() {
        assertEquals(Level.BAD, ResultEvaluator.evaluate(404, "", now).level)
        assertEquals(Level.BAD, ResultEvaluator.evaluate(404, """{"message":"code not found"}""", now).level)
    }

    @Test
    fun catalogDataUsed() {
        val body = """{"codeFounded":true,"status":"INTRODUCED","catalogData":[{"brand_name":"Brand","producer_name":"Factory","good_name":"Футболка"}]}"""
        val v = ResultEvaluator.evaluate(200, body, now)
        assertEquals(Level.OK, v.level)
        assertEquals("Футболка", v.product)
        assertTrue(v.details.any { it.first == "Бренд" && it.second == "Brand" })
        assertTrue(v.details.any { it.first == "Производитель" && it.second == "Factory" })
    }

    @Test
    fun failedSignatureMakesGreenYellow() {
        val v = ResultEvaluator.evaluate(200, """{"codeFounded":true,"checkResult":false,"milkData":{"status":"INTRODUCED"}}""", now)
        assertEquals(Level.WARN, v.level)
    }

    @Test
    fun blockedFlagIsRed() {
        val v = ResultEvaluator.evaluate(200, """{"codeFounded":true,"milkData":{"status":"INTRODUCED","isBlocked":true}}""", now)
        assertEquals(Level.BAD, v.level)
    }

    @Test
    fun notFoundDetection() {
        assertTrue(ResultEvaluator.isNotFound(200, """{"codeFounded":false}"""))
        assertTrue(ResultEvaluator.isNotFound(200, """{"status":"wrong"}"""))
        assertTrue(ResultEvaluator.isNotFound(404, ""))
        assertTrue(ResultEvaluator.isNotFound(400, """{"message":"bad code"}"""))
        assertTrue(!ResultEvaluator.isNotFound(200, """{"codeFounded":true,"milkData":{"status":"INTRODUCED"}}"""))
        assertTrue(!ResultEvaluator.isNotFound(502, "Bad gateway"))
        assertTrue(ResultEvaluator.isFound(200, """{"codeFounded":true}"""))
        assertTrue(ResultEvaluator.isFound(200, """{"sweetsData":{"status":"INTRODUCED"}}"""))
        assertTrue(!ResultEvaluator.isFound(200, """{"codeFounded":false}"""))
        assertTrue(!ResultEvaluator.isFound(404, """{"codeFounded":true}"""))
    }
}
