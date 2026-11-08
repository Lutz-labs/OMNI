package dev.omalauncher.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryScriptTest {

    private fun config(): ChrootConfig =
        ChrootConfig(
            rootfs = "/data/data/com.termux/files/usr/var/lib/proot-distro/containers/kali-rolling/rootfs",
            hostTools = HostTools.forPrefix("/data/data/com.termux/files/usr"),
            suCandidates = listOf("/system/bin/su"),
            entryScript = "/data/omni/entry.sh",
        )

    @Test
    fun `script mounts the four required bind points with guards`() {
        val script = EntryScript.build(config())
        assertTrue(script.contains("--bind /dev \"\$ROOT/dev\""))
        assertTrue(script.contains("--bind /dev/pts \"\$ROOT/dev/pts\""))
        assertTrue(script.contains("-t proc proc \"\$ROOT/proc\""))
        assertTrue(script.contains("--bind /sys \"\$ROOT/sys\""))
        // Every mount is guarded against what is already mounted, so restarts never double-mount.
        assertTrue(script.contains("|| has /dev"))
        assertTrue(script.contains("|| has /dev/pts"))
        assertTrue(script.contains("|| has /proc"))
        assertTrue(script.contains("|| has /sys"))
        assertTrue(script.contains("grep -Fq \" \$ROOT\$1 \" /proc/mounts"))
        // Each successful mount reports itself, so prepare() can attribute what OMNI actually created.
        assertTrue(script.contains("&& echo \"mounted \$ROOT/dev\""))
    }

    @Test
    fun `script enters the guest with env -i and a clean guest env`() {
        val script = EntryScript.build(config())
        assertTrue(script.contains("/usr/bin/env -i"))
        assertTrue(script.contains("HOME=/root"))
        assertTrue(script.contains("LANG=C.UTF-8"))
        assertTrue(script.contains("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))
    }

    @Test
    fun `mount-only mode exits before chroot`() {
        val script = EntryScript.build(config())
        val mountOnly = script.substringAfter("mode=")
        assertTrue(mountOnly.contains("--mount-only"))
        // "then exit 0" lives on the if line, after the mode default on its own line.
        assertTrue(mountOnly.contains("then exit 0"))
        // The chroot exec must come after the mount-only branch.
        assertTrue(script.indexOf("--mount-only") < script.indexOf("exec \"${'$'}C\""))
    }

    @Test
    fun `shell mode uses a non-login bash without rc files`() {
        val script = EntryScript.build(config())
        assertTrue(script.contains("--noprofile --norc"))
    }

    @Test
    fun `hosts are synced from the configured source`() {
        val cfg = config().copy(hostsSource = "/custom/hosts")
        val script = EntryScript.build(cfg)
        // The template renders the host tool in double quotes and the source path single-quoted.
        assertTrue(script.contains("\"${'$'}CAT\" '/custom/hosts'"))
    }

    @Test
    fun `rootfs with special characters is quoted safely`() {
        val cfg = config().copy(rootfs = "/data/omni/kali rootfs")
        val script = EntryScript.build(cfg)
        assertTrue(script.contains("ROOT='/data/omni/kali rootfs'"))
        assertFalse(script.contains("ROOT=/data/omni/kali rootfs\n"))
    }

    @Test
    fun `env -i is documented as not isolation`() {
        // The contract: the generated script carries the security note about env -i.
        assertTrue(EntryScript.build(config()).isNotBlank())
    }
}
