package com.acelik.asistan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class LogicTest {

    @Test fun norm_stripsTurkishAccents() {
        assertEquals("sessize al", Nlu.norm("Sessize AL"))
        assertTrue(Nlu.norm("ışığı").none { it in "ışğ" })
    }

    @Test fun verbDoesNotMatchLookalikes() {
        assertFalse(Nlu.isVerb("baska", listOf("bas")))
        assertTrue(Nlu.isVerb("bas", listOf("bas")))
    }

    @Test fun nameVariantsStripSuffix() {
        val v = Nlu.nameVariants("annemi")
        assertTrue(v.any { it == "annem" || it == "anne" })
    }

    @Test fun similarityOrdersSensibly() {
        assertTrue(Nlu.similarity("feneri ac", "feneri aç") > Nlu.similarity("feneri ac", "wifi kapat"))
    }

    @Test fun reminderRelative() {
        val now = Calendar.getInstance().apply { set(2026, 10, 16, 10, 0, 0); set(Calendar.MILLISECOND, 0) }
        val w = Nlu.parseWhen("5 dakika sonra ilaç al", now)
        assertNotNull(w)
        assertEquals(now.timeInMillis + 5 * 60_000, w!!.time)
    }

    @Test fun prayerTimesAreOrdered() {
        val m = PrayerTimes.minutes(2026, 11, 16, 41.0, 29.0, 3.0)
        assertEquals(6, m.size)
        for (i in 1 until m.size) assertTrue("vakit $i sırasız", m[i] > m[i - 1])
        assertTrue(m[0] in 180..400)      // imsak 03:00-06:40
        assertTrue(m[2] in 690..800)      // öğle ~12:00-13:20
    }

    @Test fun qiblaIstanbul() {
        val q = PrayerTimes.qibla(41.0, 29.0)
        assertTrue("kıble $q", q in 145.0..158.0)
    }
}
