package cn.yunji.journal

import cn.yunji.journal.core.network.*
import org.junit.Assert.*
import org.junit.Test

class CloudCredentialsTest {
    private val saved=CloudConnection("https://synthetic.invalid/dav/","synthetic-account","synthetic-secret")
    @Test fun blankPasswordCanReuseOnlyTheSameNormalizedEndpointAndAccount() {
        val same=CloudCredentials.resolve("https://synthetic.invalid/dav","synthetic-account","",listOf("新目录"),saved)
        assertEquals(saved.password,same.password);assertEquals(listOf("新目录"),same.folder)
        assertTrue(runCatching {CloudCredentials.resolve("https://different.invalid/dav/",saved.username,"",emptyList(),saved)}.isFailure)
        assertTrue(runCatching {CloudCredentials.resolve(saved.endpoint,"different-account","",emptyList(),saved)}.isFailure)
        assertTrue(runCatching {CloudCredentials.resolve("https://synthetic.invalid/other/",saved.username,"",emptyList(),saved)}.isFailure)
    }
    @Test fun explicitlyTypedPasswordIsUsedForNewEndpointAndNeverAppearsInToString() {
        val candidate=CloudCredentials.resolve("https://different.invalid/",saved.username,"new-test-password",emptyList(),saved)
        assertEquals("new-test-password",candidate.password);assertFalse(candidate.toString().contains(candidate.password))
    }
    @Test fun insecureAddressAndAmbiguousCredentialOrFolderAreRejected() {
        for(endpoint in listOf("http://synthetic.invalid/","https://user:secret@synthetic.invalid/","https://synthetic.invalid/?token=x","https://synthetic.invalid/#fragment"))
            assertTrue(runCatching {saved.copy(endpoint=endpoint).normalized()}.isFailure)
        assertTrue(runCatching {saved.copy(username="bad:account").normalized()}.isFailure)
        assertTrue(runCatching {saved.copy(folder=listOf("..")).normalized()}.isFailure)
        assertTrue(runCatching {CloudCredentials.resolve(saved.endpoint,saved.username,"",emptyList(),null)}.isFailure)
    }
}
