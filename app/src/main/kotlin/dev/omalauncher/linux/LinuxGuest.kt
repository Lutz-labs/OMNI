package dev.omalauncher.linux

import dev.omalauncher.upgrade.Managed
import java.io.File

/**
 * The guest Linux a consumer talks to. Two implementations exist: the legacy
 * PRoot/Arch guest ([ProotGuest]) and the ACTIVE Kali chroot backend
 * ([ChrootGuest]); Runtime hands consumers whichever one is current, so path
 * mapping, command execution and managed files follow the active backend
 * without consumers knowing which one it is.
 */
interface LinuxGuest {
    /** The launcher's own files (host-side); backend-independent. */
    val filesDir: File

    /** The guest root filesystem, as a host path. */
    val rootfs: File

    /** The guest root user's home (host path). */
    val home: File

    /** `~/.local/share/applications` of the guest root user (host path). */
    val applications: File

    /** `~/.config/omalauncher` of the guest root user (host path). */
    val launcherConfig: File

    /** The guest's /tmp (host path, inside the active rootfs). */
    val tmpDir: File

    /** Guest-visible directory for launcher FIFOs and state (host path, inside the active rootfs). */
    val runtimeDir: File

    /** Host-side keymap the Wayland server uses; backend-independent. */
    val keymap: File

    /** The guest's files as it sees them; see [GuestFs]. */
    val guestFs: GuestFs

    /** A guest path as the host file the guest would see; null when it escapes the guest. */
    fun guestFile(guestPath: String): File?

    /** The guest path of a host file the guest can see; null otherwise. */
    fun guestPath(host: File): String?

    /** A guest path as the host sees it (for the launcher's own files). */
    fun hostFile(guestPath: String): File

    /** Runs [script] to completion, blocking; [onLine] gets each output line. Returns the exit code. */
    fun run(script: String, onLine: (String) -> Unit = {}): Int

    /** A fresh guest process for [script]; call [ProcessBuilder.start] and manage it yourself. */
    fun process(script: String): ProcessBuilder

    /** Launcher-managed files in the guest (the proot bwrap shim and friends); empty when the guest needs none. */
    fun managed(): List<Managed>

    /** The graphics environment guest commands run with (software rendering by default). */
    var graphicsEnv: List<String>
}
