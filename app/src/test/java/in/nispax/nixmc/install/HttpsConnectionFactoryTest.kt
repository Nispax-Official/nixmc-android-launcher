package `in`.nispax.nixmc.install

import org.junit.Assert.assertThrows
import org.junit.Test

class HttpsConnectionFactoryTest {
    @Test
    fun rejectsCleartextHttpBeforeOpeningConnection() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpsConnectionFactory.open("http://127.0.0.1/artifact")
        }
    }

    @Test
    fun rejectsCredentialBearingHttpsUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpsConnectionFactory.open("https://user:password@example.invalid/artifact")
        }
    }
}
