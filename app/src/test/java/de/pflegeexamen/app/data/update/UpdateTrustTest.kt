package de.pflegeexamen.app.data.update

import org.junit.Assert.*
import org.junit.Test

class UpdateTrustTest {
    @Test fun rejectsWrongPackageSignatureVersionsAndMissingCertificates() {
        fun accepted(pkg: String = "app", candidate: Long = 20, installed: Long = 19,
                     trusted: Set<String> = setOf("cert"), received: Set<String> = setOf("cert"),
                     expected: Long = 20) = UpdateTrust.accepts("app", installed, trusted, pkg, candidate, received, expected)
        assertTrue(accepted())
        assertFalse(accepted(pkg = "other"))
        assertFalse(accepted(candidate = 19))
        assertFalse(accepted(candidate = 18))
        assertFalse(accepted(expected = 21))
        assertFalse(accepted(received = setOf("different")))
        assertFalse(accepted(received = setOf("cert", "different")))
        assertFalse(accepted(trusted = emptySet(), received = emptySet()))
    }
}
