package cn.yunji.journal.core.backup

import java.io.File
import java.io.RandomAccessFile

/** APPNOTE 4.3.12–4.3.16. Bounds checked before ZipFile allocates its central-directory index. */
internal object ZipPreflight {
    private const val MAX_CENTRAL=32L*1024*1024
    private const val MAX_ARCHIVE=8L*1024*1024*1024+64L*1024*1024
    private fun u16(b: ByteArray,p: Int)=(b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray,p: Int)=u16(b,p).toLong() or (u16(b,p+2).toLong() shl 16)
    private fun u64(b: ByteArray,p: Int): Long {
        val high=u32(b,p+4);require(high<=0x7fff_ffffL)
        return u32(b,p) or (high shl 32)
    }
    fun check(file: File,maxEntries: Int,checkpoint: ()->Unit) {
        RandomAccessFile(file,"r").use {input->
            val length=input.length();require(length in 22..MAX_ARCHIVE) {"备份文件大小无效"}
            val tailSize=minOf(length,65557).toInt();val tail=ByteArray(tailSize)
            input.seek(length-tailSize);input.readFully(tail)
            val end=(tailSize-22 downTo 0).firstOrNull {p->u32(tail,p)==0x06054b50L && p+22+u16(tail,p+20)==tailSize}
                ?: throw IllegalArgumentException("备份目录不完整")
            val endOffset=length-tailSize+end
            require(u16(tail,end+4)==0 && u16(tail,end+6)==0) {"不支持分卷备份"}
            var count=u16(tail,end+10).toLong();var size=u32(tail,end+12);var offset=u32(tail,end+16)
            var directoryEnd=endOffset
            if(count==65535L || size==0xffff_ffffL || offset==0xffff_ffffL) {
                require(endOffset>=20)
                val locator=ByteArray(20);input.seek(endOffset-20);input.readFully(locator)
                require(u32(locator,0)==0x07064b50L && u32(locator,4)==0L && u32(locator,16)==1L)
                val recordOffset=u64(locator,8)
                require(recordOffset>=0 && recordOffset<=endOffset-76)
                val record=ByteArray(56);input.seek(recordOffset);input.readFully(record)
                require(u32(record,0)==0x06064b50L && u64(record,4) in 44..4096)
                require(recordOffset+12+u64(record,4)==endOffset-20)
                require(u32(record,16)==0L && u32(record,20)==0L) {"不支持分卷备份"}
                count=u64(record,32);require(u64(record,24)==count)
                size=u64(record,40);offset=u64(record,48);directoryEnd=recordOffset
                require(u16(tail,end+8)==65535 || u16(tail,end+8).toLong()==count)
                require(u16(tail,end+10)==65535 || u16(tail,end+10).toLong()==count)
                require(u32(tail,end+12)==0xffff_ffffL || u32(tail,end+12)==size)
                require(u32(tail,end+16)==0xffff_ffffL || u32(tail,end+16)==offset)
            } else require(u16(tail,end+8).toLong()==count)
            require(count in 2..maxEntries.toLong() && size in 92..MAX_CENTRAL) {"备份目录超过资源限制"}
            require(offset>=0 && offset<=directoryEnd && size==directoryEnd-offset) {"备份目录范围无效"}
            input.seek(offset)
            val header=ByteArray(46)
            repeat(count.toInt()) {
                checkpoint();require(input.filePointer<=directoryEnd-46);input.readFully(header)
                require(u32(header,0)==0x02014b50L)
                require(u16(header,8) and 0x41==0) {"不支持ZIP层加密"}
                require(u16(header,10) in setOf(0,8))
                val name=u16(header,28);val extra=u16(header,30);val comment=u16(header,32)
                require(name in 1..128 && extra<=1024 && comment<=1024 && u16(header,34)==0)
                val next=input.filePointer+name+extra+comment
                require(next<=directoryEnd);input.seek(next)
            }
            require(input.filePointer==directoryEnd) {"备份目录条目数量不匹配"}
        }
    }
}
