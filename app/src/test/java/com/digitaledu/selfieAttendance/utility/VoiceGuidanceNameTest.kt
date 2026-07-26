package com.example.selfieAttendance.utility

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceGuidanceNameTest {

    @Test
    fun uppercaseName_isChangedToNaturalCase() {
        assertEquals("Gourav Kumar", VoiceGuidance.speakableName("GOURAV KUMAR"))
    }

    @Test
    fun individuallySeparatedLetters_areJoinedAsAName() {
        assertEquals("Gourav", VoiceGuidance.speakableName("G O U R A V"))
    }

    @Test
    fun databaseSeparators_areChangedToWordSpaces() {
        assertEquals(
            "Anil Kumar Sharma",
            VoiceGuidance.speakableName("ANIL_KUMAR-SHARMA")
        )
    }

    @Test
    fun naturallyCasedName_isPreserved() {
        assertEquals("McDonald", VoiceGuidance.speakableName("McDonald"))
    }

    @Test
    fun blankName_hasSafeFallback() {
        assertEquals("User", VoiceGuidance.speakableName("   "))
    }
}
