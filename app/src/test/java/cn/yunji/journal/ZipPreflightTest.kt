package cn.yunji.journal

import cn.yunji.journal.core.backup.ZipPreflight
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipPreflightTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun buffer(bytes: ByteArray)=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun standard(): ByteArray {
        val file=temporary.newFile()
        ZipOutputStream(file.outputStream()).use {zip->listOf("vault.json","manifest.enc").forEach {zip.putNextEntry(ZipEntry(it));zip.write(byteArrayOf(1));zip.closeEntry()}}
        return file.readBytes()
    }
    private fun check(bytes: ByteArray) {val file=temporary.newFile();file.writeBytes(bytes);ZipPreflight.check(file,200002){}}
    private fun zip64(bytes: ByteArray,count: Long=2): ByteArray {
        val end=bytes.size-22;val old=buffer(bytes)
        val record=ByteArray(56);buffer(record).apply {putInt(0,0x06064b50);putLong(4,44);putShort(12,45);putShort(14,45);putLong(24,count);putLong(32,count);putLong(40,old.getInt(end+12).toLong() and 0xffff_ffffL);putLong(48,old.getInt(end+16).toLong() and 0xffff_ffffL)}
        val locator=ByteArray(20);buffer(locator).apply {putInt(0,0x07064b50);putLong(8,end.toLong());putInt(16,1)}
        val eocd=bytes.copyOfRange(end,bytes.size);buffer(eocd).apply {putShort(8,(-1).toShort());putShort(10,(-1).toShort());putInt(12,-1);putInt(16,-1)}
        return bytes.copyOfRange(0,end)+record+locator+eocd
    }
    @Test fun ordinaryAndBoundedZip64DirectoriesPass() {val bytes=standard();check(bytes);check(zip64(bytes))}
    @Test fun oversizedZip64CountAndCentralDirectoryRejectBeforeIndexing() {
        assertTrue(runCatching {check(zip64(standard(),200003))}.isFailure)
        val bytes=standard();buffer(bytes).putInt(bytes.size-22+12,32*1024*1024+1)
        assertTrue(runCatching {check(bytes)}.isFailure)
    }
    @Test fun truncatedSplitAndMismatchedDirectoryMetadataReject() {
        val original=standard()
        assertTrue(runCatching {check(original.copyOf(original.size-1))}.isFailure)
        val split=original.copyOf();buffer(split).putShort(split.size-22+4,1)
        assertTrue(runCatching {check(split)}.isFailure)
        val count=original.copyOf();buffer(count).apply {putShort(count.size-22+8,3);putShort(count.size-22+10,3)}
        assertTrue(runCatching {check(count)}.isFailure)
        val offset=original.copyOf();buffer(offset).putInt(offset.size-22+16,Int.MAX_VALUE)
        assertTrue(runCatching {check(offset)}.isFailure)
    }
    @Test fun oversizedPerEntryMetadataAndEncryptedZipReject() {
        val original=standard();val central=buffer(original).getInt(original.size-22+16)
        val name=original.copyOf();buffer(name).putShort(central+28,(-1).toShort())
        assertTrue(runCatching {check(name)}.isFailure)
        val encrypted=original.copyOf();val flags=buffer(encrypted).getShort(central+8).toInt() or 1
        buffer(encrypted).putShort(central+8,flags.toShort())
        assertTrue(runCatching {check(encrypted)}.isFailure)
    }
}
