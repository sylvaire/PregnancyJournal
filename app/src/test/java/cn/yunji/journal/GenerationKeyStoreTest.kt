package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.security.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class GenerationKeyStoreTest {
    private fun fixture(block: (File,VaultMaterial,VaultMaterial,String)->Unit) {
        val root=Files.createTempDirectory("generation-key").toFile()
        val device=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        val restored=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        val id=UUID.randomUUID().toString();File(root,"gen/$id").mkdirs()
        try {block(root,device,restored,id)}finally {device.master.fill(0);restored.master.fill(0);root.deleteRecursively()}
    }
    @Test fun persistedWrapperReopensWithNewStoreInstance()=fixture {root,device,restored,id->
        GenerationKeyStore(root){}.install(id,device,restored)
        val result=GenerationKeyStore(root){}.open(id,device)
        try {assertEquals(restored.id,result.id);assertArrayEquals(restored.master,result.master)}finally {result.master.fill(0)}
        assertFalse(File(root,"gen/$id/material.pending").exists())
    }
    @Test fun existingWrapperIsNeverOverwrittenAndMissingFailsClosed()=fixture {root,device,restored,id->
        val store=GenerationKeyStore(root){}
        assertTrue(runCatching {store.open(id,device)}.isFailure)
        store.install(id,device,restored);val file=File(root,"gen/$id/material.enc");val before=file.readBytes()
        assertTrue(runCatching {store.install(id,device,restored)}.isFailure);assertArrayEquals(before,file.readBytes())
    }
    @Test fun directorySyncFailureDoesNotReportInstallationSuccess()=fixture {root,device,restored,id->
        assertTrue(runCatching {GenerationKeyStore(root){throw java.io.IOException("Injected sync failure")}.install(id,device,restored)}.isFailure)
    }
}