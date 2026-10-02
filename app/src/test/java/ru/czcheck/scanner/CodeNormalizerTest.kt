package ru.czcheck.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeNormalizerTest {

    private val gs = CodeNormalizer.GS

    // Обувь/одежда: 01 + GTIN(14) + 21 + серийный(13) GS 91 + ключ(4) GS 92 + код проверки(44)
    private val shoesSerial = "5eJ:Ft(lPa/-p"
    private val shoesCrypto = "Ah0nMHL7NAfp2YXoR0V+rNEqrQmIa3aPRe+gcN7+ccl4OYPxd8ucW9hOBRUzKxQ6c/BOoW6V8Sw2mgfpBTqaJA=="
        .substring(0, 44)
    private val shoes = "0104600439931256" + "21" + shoesSerial + gs + "91" + "ee10" + gs + "92" + shoesCrypto

    // Молоко: 01 + GTIN + 21 + серийный(6) GS 93 + код(4)
    private val milk = "0104607009780429" + "21" + "Qz7Gr8" + gs + "93" + "dGVz"

    @Test
    fun keepsCodeWithGs() {
        val n = CodeNormalizer.normalize(shoes)
        assertEquals(shoes, n.code)
        assertEquals(CodeNormalizer.Kind.GS1, n.kind)
        assertEquals("04600439931256", n.gtin)
        assertEquals(shoesSerial, n.serial)
        assertTrue(n.notes.isEmpty())
    }

    @Test
    fun restoresGsInLongFormat() {
        val noGs = shoes.replace(gs.toString(), "")
        val n = CodeNormalizer.normalize(noGs)
        assertEquals(shoes, n.code)
        assertEquals(shoesSerial, n.serial)
        assertTrue(n.notes.contains("восстановлены разделители GS"))
    }

    @Test
    fun restoresGsInShortFormat() {
        val noGs = milk.replace(gs.toString(), "")
        val n = CodeNormalizer.normalize(noGs)
        assertEquals(milk, n.code)
        assertEquals("04607009780429", n.gtin)
        assertEquals("Qz7Gr8", n.serial)
    }

    @Test
    fun restoresTobaccoBlock() {
        val block = "0104600266011725" + "21" + "-pZ4,?6" + gs + "8005" + "112000" + gs + "93" + "abcd"
        val n = CodeNormalizer.normalize(block.replace(gs.toString(), ""))
        assertEquals(block, n.code)
        assertEquals("-pZ4,?6", n.serial)
    }

    @Test
    fun restoresWeightProduct() {
        val w = "0104607009780429" + "21" + "Qz7Gr8" + gs + "93" + "dGVz" + gs + "3103" + "000452"
        val n = CodeNormalizer.normalize(w.replace(gs.toString(), ""))
        assertEquals(w, n.code)
    }

    @Test
    fun stripsPrefixesAndNewlines() {
        val n = CodeNormalizer.normalize("]d2" + gs + milk + "\r\n")
        assertEquals(milk, n.code)
        val n2 = CodeNormalizer.normalize("{FNC1}$milk")
        assertEquals(milk, n2.code)
    }

    @Test
    fun replacesTextGsSubstitutes() {
        val n = CodeNormalizer.normalize(milk.replace(gs.toString(), "<GS>"))
        assertEquals(milk, n.code)
        val n2 = CodeNormalizer.normalize(milk.replace(gs.toString(), "~"))
        assertEquals(milk, n2.code)
    }

    @Test
    fun fixesRussianKeyboardLayout() {
        // так код «напечатает» сканер, если на ТСД включена русская раскладка
        val typed = CodeNormalizer.fixKeyboardLayout("0104607009780429") // цифры не меняются
        assertEquals("0104607009780429", typed)
        val ru = "0104607009780429" + "21" + "Йя7Пк8" + "93" + "вПМя"
        val n = CodeNormalizer.normalize(ru)
        assertEquals("0104607009780429" + "21" + "Qz7Gr8" + gs + "93" + "dGVz", n.code)
        assertTrue(n.notes.contains("исправлена русская раскладка"))
        // знаки препинания: '.'->'/', ','->'?', '"'->'@', ';'->'$'
        assertEquals("/?@$", CodeNormalizer.fixKeyboardLayout(".,\";"))
    }

    @Test
    fun detectsEan() {
        val n = CodeNormalizer.normalize("4607009780429")
        assertEquals(CodeNormalizer.Kind.EAN, n.kind)
    }

    @Test
    fun detectsTobaccoPack() {
        val n = CodeNormalizer.normalize("04600266011725-pZ4,?6AAAAabcd")
        assertEquals(CodeNormalizer.Kind.TOBACCO_PACK, n.kind)
        assertEquals("04600266011725", n.gtin)
        assertEquals("-pZ4,?6", n.serial)
    }

    @Test
    fun wireCodeHasFnc1ForGs1Only() {
        assertEquals("{FNC1}$milk", CodeNormalizer.wireCode(CodeNormalizer.normalize(milk)))
        val pack = CodeNormalizer.normalize("04600266011725-pZ4,?6AAAAabcd")
        assertEquals("04600266011725-pZ4,?6AAAAabcd", CodeNormalizer.wireCode(pack))
    }

    @Test
    fun unknownStructureLeftAsIs() {
        assertNull(CodeNormalizer.restoreGs("0104607009780429" + "21" + "ABCDEFG"))
        val n = CodeNormalizer.normalize("0104607009780429" + "21" + "ABCDEFG")
        assertEquals("0104607009780429" + "21" + "ABCDEFG", n.code)
    }

    @Test
    fun cisWithoutCryptoTail() {
        val n = CodeNormalizer.normalize(shoes)
        assertEquals("0104600439931256" + "21" + shoesSerial, n.cis)
    }
}
