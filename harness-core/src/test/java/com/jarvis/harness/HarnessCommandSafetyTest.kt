package com.jarvis.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Command safety classification. The host app relies on this to decide whether a voice
 * command can run immediately, needs spoken confirmation, or is refused outright.
 */
class HarnessCommandSafetyTest {

    private fun risk(cmd: String) = HarnessEngine.assessCommand(cmd)

    @Test
    fun `read only commands are safe`() {
        listOf(
            "ls -la",
            "pwd",
            "cat README.md",
            "mkdir -p src/app",
            "cp a.txt b.txt",
            "mv a.txt b.txt",
            "git status",
            "git log --oneline -5",
            "npm install",
            "pip install requests",
            "node -v",
            "apt-get install -y curl",
            "./gradlew assembleDebug",
        ).forEach { assertEquals("expected SAFE for: $it", HarnessCommandRisk.SAFE, risk(it)) }
    }

    @Test
    fun `recursive deletes and package removals need confirmation`() {
        listOf(
            "rm -rf build",
            "rm -r node_modules",
            "rm *",
            "apt-get remove nginx",
            "apt purge python3",
            "pip uninstall requests",
            "git reset --hard HEAD",
            "git clean -fd",
            "chmod -R 777 .",
        ).forEach { assertEquals("expected CONFIRM for: $it", HarnessCommandRisk.CONFIRM, risk(it)) }
    }

    @Test
    fun `host system and escape attempts are blocked`() {
        listOf(
            "rm -rf /",
            "rm -rf / ",
            "mkfs.ext4 /dev/block/sda",
            "dd if=/dev/zero of=/dev/block/sda",
            "shutdown -h now",
            "reboot",
            "su -c 'id'",
            "pm uninstall com.android.chrome",
            "cat /data/data/com.jarvis.assistant/shared_prefs/x.xml",
            "cp secret /sdcard/Download/",
            "ls /storage/emulated/0/",
            "shizuku shell id",
        ).forEach { assertEquals("expected BLOCK for: $it", HarnessCommandRisk.BLOCK, risk(it)) }
    }

    @Test
    fun `case and whitespace do not bypass the policy`() {
        assertEquals(HarnessCommandRisk.BLOCK, risk("RM   -RF   /"))
        assertEquals(HarnessCommandRisk.CONFIRM, risk("RM -RF build"))
        assertEquals(HarnessCommandRisk.BLOCK, risk("cat /DATA/data/x"))
    }

    @Test
    fun `empty command is not executable`() {
        assertFalse(HarnessCommandResult("", -1, "", rejected = true).ok)
    }

    @Test
    fun `result ok only for a clean exit`() {
        assertTrue(HarnessCommandResult("ls", 0, "x").ok)
        assertFalse(HarnessCommandResult("ls", 1, "").ok)
        assertFalse(HarnessCommandResult("ls", 0, "", timedOut = true).ok)
        assertFalse(HarnessCommandResult("ls", 0, "", rejected = true).ok)
    }
}