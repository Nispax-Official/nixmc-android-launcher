package in.nispax.nixmc.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerNameValidatorTest {
    @Test
    fun acceptsTypicalUsername() {
        assertNull(PlayerNameValidator.errorFor("NixPlayer"))
        assertNull(PlayerNameValidator.errorFor("Steve_123"))
    }

    @Test
    fun acceptsMinimumAndMaximumLengths() {
        assertNull(PlayerNameValidator.errorFor("abc"))
        assertNull(PlayerNameValidator.errorFor("A".repeat(16)))
    }

    @Test
    fun rejectsBlankAndOutOfRangeNames() {
        assertEquals("Enter a player name.", PlayerNameValidator.errorFor(""))
        assertEquals("Enter a player name.", PlayerNameValidator.errorFor("   "))
        assertEquals(
            "Player names must be 3–16 characters long.",
            PlayerNameValidator.errorFor("ab")
        )
        assertEquals(
            "Player names must be 3–16 characters long.",
            PlayerNameValidator.errorFor("A".repeat(17))
        )
    }

    @Test
    fun rejectsUnsupportedCharactersAndEdgeSpaces() {
        assertEquals(
            "Use only English letters, numbers, and underscores.",
            PlayerNameValidator.errorFor("No spaces")
        )
        assertEquals(
            "Use only English letters, numbers, and underscores.",
            PlayerNameValidator.errorFor("name!")
        )
        assertEquals(
            "Remove spaces at the start or end.",
            PlayerNameValidator.errorFor(" Alex")
        )
    }
}
