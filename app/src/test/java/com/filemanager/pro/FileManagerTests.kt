package com.filemanager.pro

import com.filemanager.pro.data.model.FileItem
import com.filemanager.pro.data.model.FileType
import com.filemanager.pro.data.model.SortOption
import com.filemanager.pro.utils.FileUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

@RunWith(JUnit4::class)
class FileUtilsTest {

    @Test
    fun `formatSize returns correct unit for bytes`() {
        assertEquals("0 B", FileUtils.formatSize(0))
        assertEquals("512 B", FileUtils.formatSize(512))
        assertEquals("1,023 B", FileUtils.formatSize(1023))
    }

    @Test
    fun `formatSize returns correct unit for kilobytes`() {
        assertEquals("1 KB", FileUtils.formatSize(1024))
        assertEquals("1.5 KB", FileUtils.formatSize(1536))
    }

    @Test
    fun `formatSize returns correct unit for megabytes`() {
        val oneMB = 1024L * 1024L
        assertEquals("1 MB", FileUtils.formatSize(oneMB))
        assertEquals("10 MB", FileUtils.formatSize(10 * oneMB))
    }

    @Test
    fun `formatSize returns correct unit for gigabytes`() {
        val oneGB = 1024L * 1024L * 1024L
        assertEquals("1 GB", FileUtils.formatSize(oneGB))
    }

    @Test
    fun `sanitizeFileName replaces illegal characters`() {
        assertEquals("hello_world", FileUtils.sanitizeFileName("hello/world"))
        assertEquals("file_name", FileUtils.sanitizeFileName("file:name"))
        assertEquals("valid_name", FileUtils.sanitizeFileName("valid*name"))
        assertEquals("test", FileUtils.sanitizeFileName("test"))
    }

    @Test
    fun `sanitizeFileName trims whitespace`() {
        assertEquals("hello", FileUtils.sanitizeFileName("  hello  "))
    }

    @Test
    fun `isTextFile recognizes common text extensions`() {
        assertTrue(FileUtils.isTextFile(File("readme.txt")))
        assertTrue(FileUtils.isTextFile(File("script.py")))
        assertTrue(FileUtils.isTextFile(File("config.json")))
        assertTrue(FileUtils.isTextFile(File("style.css")))
        assertFalse(FileUtils.isTextFile(File("image.jpg")))
        assertFalse(FileUtils.isTextFile(File("video.mp4")))
        assertFalse(FileUtils.isTextFile(File("archive.zip")))
    }

    @Test
    fun `isArchive recognizes archive extensions`() {
        assertTrue(FileUtils.isArchive(File("file.zip")))
        assertTrue(FileUtils.isArchive(File("file.tar")))
        assertTrue(FileUtils.isArchive(File("file.gz")))
        assertTrue(FileUtils.isArchive(File("file.7z")))
        assertFalse(FileUtils.isArchive(File("file.txt")))
        assertFalse(FileUtils.isArchive(File("file.jpg")))
    }

    @Test
    fun `formatRelativeDate returns 'Just now' for recent timestamps`() {
        val now = System.currentTimeMillis()
        val result = FileUtils.formatRelativeDate(now - 30_000L) // 30 seconds ago
        assertEquals("Just now", result)
    }

    @Test
    fun `formatRelativeDate returns minutes for timestamps under an hour`() {
        val fiveMinutesAgo = System.currentTimeMillis() - 5 * 60_000L
        val result = FileUtils.formatRelativeDate(fiveMinutesAgo)
        assertEquals("5 minutes ago", result)
    }

    @Test
    fun `getUniqueFileName returns original if not exists`() {
        val tempDir = createTempDir()
        val result = FileUtils.getUniqueFileName(tempDir, "test.txt")
        assertEquals("test.txt", result)
        tempDir.deleteRecursively()
    }

    @Test
    fun `getUniqueFileName adds counter if file exists`() {
        val tempDir = createTempDir()
        File(tempDir, "test.txt").createNewFile()
        val result = FileUtils.getUniqueFileName(tempDir, "test.txt")
        assertEquals("test (1).txt", result)
        tempDir.deleteRecursively()
    }

    @Test
    fun `getUniqueFileName increments counter correctly`() {
        val tempDir = createTempDir()
        File(tempDir, "test.txt").createNewFile()
        File(tempDir, "test (1).txt").createNewFile()
        File(tempDir, "test (2).txt").createNewFile()
        val result = FileUtils.getUniqueFileName(tempDir, "test.txt")
        assertEquals("test (3).txt", result)
        tempDir.deleteRecursively()
    }
}

@RunWith(JUnit4::class)
class FileTypeTest {

    @Test
    fun `FileType fromExtension returns correct types`() {
        assertEquals(FileType.IMAGE,    FileType.fromExtension("jpg"))
        assertEquals(FileType.IMAGE,    FileType.fromExtension("JPEG"))
        assertEquals(FileType.IMAGE,    FileType.fromExtension("PNG"))
        assertEquals(FileType.VIDEO,    FileType.fromExtension("mp4"))
        assertEquals(FileType.VIDEO,    FileType.fromExtension("MKV"))
        assertEquals(FileType.AUDIO,    FileType.fromExtension("mp3"))
        assertEquals(FileType.AUDIO,    FileType.fromExtension("flac"))
        assertEquals(FileType.PDF,      FileType.fromExtension("pdf"))
        assertEquals(FileType.ARCHIVE,  FileType.fromExtension("zip"))
        assertEquals(FileType.ARCHIVE,  FileType.fromExtension("RAR"))
        assertEquals(FileType.APK,      FileType.fromExtension("apk"))
        assertEquals(FileType.CODE,     FileType.fromExtension("kt"))
        assertEquals(FileType.CODE,     FileType.fromExtension("java"))
        assertEquals(FileType.SPREADSHEET, FileType.fromExtension("xlsx"))
        assertEquals(FileType.PRESENTATION, FileType.fromExtension("pptx"))
        assertEquals(FileType.UNKNOWN,  FileType.fromExtension("xyz"))
        assertEquals(FileType.UNKNOWN,  FileType.fromExtension(""))
    }

    @Test
    fun `FileType fromExtension is case insensitive`() {
        assertEquals(FileType.fromExtension("JPG"), FileType.fromExtension("jpg"))
        assertEquals(FileType.fromExtension("MP4"), FileType.fromExtension("mp4"))
        assertEquals(FileType.fromExtension("ZIP"), FileType.fromExtension("zip"))
    }
}

@RunWith(JUnit4::class)
class FileItemTest {

    private fun makeFile(name: String, isDir: Boolean = false, size: Long = 0L): FileItem {
        return FileItem(
            path = "/test/$name",
            name = name,
            size = size,
            lastModified = System.currentTimeMillis(),
            isDirectory = isDir,
            isHidden = name.startsWith("."),
            extension = if (isDir) "" else name.substringAfterLast('.', ""),
            mimeType = "",
            canRead = true,
            canWrite = true,
            childCount = 0
        )
    }

    @Test
    fun `isHidden is true for dot-files`() {
        val hidden = makeFile(".hidden_file")
        val visible = makeFile("visible.txt")
        assertTrue(hidden.isHidden)
        assertFalse(visible.isHidden)
    }

    @Test
    fun `formattedSize shows item count for directories`() {
        val dir = makeFile("MyFolder", isDir = true).copy(childCount = 12)
        assertTrue(dir.formattedSize.contains("12"))
        assertTrue(dir.formattedSize.contains("items"))
    }

    @Test
    fun `formattedSize formats bytes correctly for files`() {
        val smallFile = makeFile("small.txt", size = 500L)
        val mbFile = makeFile("big.bin", size = 2 * 1024L * 1024L)
        assertTrue(smallFile.formattedSize.contains("B"))
        assertTrue(mbFile.formattedSize.contains("MB"))
    }

    @Test
    fun `fileType is FOLDER for directories`() {
        val dir = makeFile("Documents", isDir = true)
        assertEquals(FileType.FOLDER, dir.fileType)
    }

    @Test
    fun `fileType matches extension for files`() {
        val img = makeFile("photo.jpg")
        val vid = makeFile("movie.mp4")
        val doc = makeFile("report.docx")
        assertEquals(FileType.IMAGE, img.fileType)
        assertEquals(FileType.VIDEO, vid.fileType)
        assertEquals(FileType.DOCUMENT, doc.fileType)
    }
}
