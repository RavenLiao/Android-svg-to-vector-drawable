package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolVersionTest {
    @Test
    fun `parses one strict version and preserves unrelated bytes while bumping patch`() {
        val path = Files.createTempFile("gradle", ".properties")
        Files.writeString(path, "# header\r\nsvg2vdVersion=0.1.9\r\nother=value\r\n")
        assertEquals(ToolVersion(0, 1, 10), bumpToolPatchVersionFile(path, ToolVersion(0, 1, 9)))
        assertEquals("# header\r\nsvg2vdVersion=0.1.10\r\nother=value\r\n", Files.readString(path))
    }

    @Test
    fun `rejects duplicate prerelease and missing assignments`() {
        assertFailsWith<IllegalArgumentException> { parseToolVersionFile("svg2vdVersion=1.2.3\nsvg2vdVersion=1.2.4\n") }
        assertFailsWith<IllegalArgumentException> { parseToolVersionFile("svg2vdVersion=1.2.3-rc1\n") }
        assertFailsWith<IllegalArgumentException> { parseToolVersionFile("other=value\n") }
    }

    @Test
    fun `rejects concurrent expected version mismatch`() {
        val path = Files.createTempFile("gradle", ".properties")
        Files.writeString(path, "svg2vdVersion=0.1.2\n")
        assertFailsWith<IllegalArgumentException> { bumpToolPatchVersionFile(path, ToolVersion(0, 1, 1)) }
    }
}
