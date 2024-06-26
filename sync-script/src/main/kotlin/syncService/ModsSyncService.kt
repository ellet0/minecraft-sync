package syncService

import constants.SyncScriptDotMinecraftFiles
import gui.dialogs.LoadingIndicatorDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import syncInfo.models.Mod
import syncInfo.models.SyncInfo
import syncInfo.models.getDisplayName
import syncInfo.models.hasValidFileIntegrityOrError
import syncInfo.models.instance
import syncInfo.models.shouldSyncOnCurrentEnvironment
import syncInfo.models.shouldVerifyFileIntegrity
import utils.ExecutionTimer
import utils.FileDownloader
import utils.calculateProgressByIndex
import utils.convertBytesToReadableMegabytesAsString
import utils.deleteExistingOrTerminate
import utils.getFileNameFromUrlOrError
import utils.showErrorMessageAndTerminate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isHidden
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.streams.toList

// TODO: Use JarFile(modFile).manifest.mainAttributes to read the mod name, id and some info to solve the duplicating
//  mods issue when allowing the user to install other mods

class ModsSyncService : SyncService {
    // TODO: Avoid converting to File and use the Path instead
    private val modsDirectoryPath = SyncScriptDotMinecraftFiles.Mods.file.toPath()

    companion object {
        private const val MOD_FILE_EXTENSION = "jar"
    }

    private val syncInfo = SyncInfo.instance

    override suspend fun syncData() =
        withContext(Dispatchers.IO) {
            val modsExecutionTimer = ExecutionTimer()
            modsExecutionTimer.setStartTime()
            println("\n\uD83D\uDD04 Syncing mods...")

            // All mods from the remote
            val mods = syncInfo.mods
            println("📥 Total received mods from server: ${mods.size}")

            validateModsDirectory()
            deleteUnSyncedLocalModFiles(mods = mods)

            val currentEnvironmentModsOrAll = getCurrentEnvironmentModsOrAll(mods = mods)
            println("📥 Current environment mods: ${currentEnvironmentModsOrAll.size}")

            val loadingIndicatorDialog: LoadingIndicatorDialog? =
                LoadingIndicatorDialog.createIfGuiEnabled("Syncing Mods...")
            loadingIndicatorDialog?.isVisible = true

            // Download the missing/new mods && remove the modified or the ones that has invalid file integrity

            val modsToDownload =
                getModsForDownloadAndValidateIfRequired(
                    mods = currentEnvironmentModsOrAll,
                    loadingIndicatorDialog = loadingIndicatorDialog,
                )
            println("\n🔍 Mods to download: ${modsToDownload.size}")

            downloadMods(
                modsToDownload = modsToDownload,
                totalMods = currentEnvironmentModsOrAll,
                loadingIndicatorDialog = loadingIndicatorDialog,
            )

            loadingIndicatorDialog?.isVisible = false

            println("\uD83D\uDD52 Finished syncing the mods in ${modsExecutionTimer.getRunningUntilNowDuration().inWholeMilliseconds}ms.")
        }

    private fun validateModsDirectory() {
        if (!modsDirectoryPath.exists()) {
            println("\uD83D\uDCC1 The mods folder doesn't exist, creating it..")
            modsDirectoryPath.createDirectories()
        }

        if (!modsDirectoryPath.isDirectory()) {
            showErrorMessageAndTerminate(
                title = "❌ Invalid Mods Folder",
                message =
                    "\uD83D\uDEE0 Mods must be stored in a directory/folder \uD83D\uDCC2 called " +
                        "`${SyncScriptDotMinecraftFiles.Mods.file.name}`" +
                        ", a file was found instead.",
            )
        }
    }

    private fun deleteUnSyncedLocalModFiles(mods: List<Mod>) {
        val localModFiles =
            try {
                Files
                    .list(modsDirectoryPath)
                    .use { stream ->
                        stream
                            .filter { !it.isDirectory() && !it.isHidden() && it.extension == MOD_FILE_EXTENSION }
                            .toList()
                    }
            } catch (e: Exception) {
                showErrorMessageAndTerminate(
                    title = "📁 File Listing Error",
                    message = "⚠ Failed to list the files in the mods folder: ${e.message}",
                )
                return
            }

        // Get only the mods that are created by the script if the admin allows the player to install other mods

        /**
         * The mods to deal with based on [SyncInfo.allowUsingOtherMods]
         * will or will not remove the mods that are created by the script
         * */
        val localModFilesToProcess =
            if (syncInfo.allowUsingOtherMods) {
                localModFiles.filter { isScriptModFile(it) }
            } else {
                localModFiles.toList()
            }

        // Delete the old un-synced mods

        val remoteModFileNames: List<String> = mods.map { getModFilePath(it).name }
        for (localModFile in localModFilesToProcess) {
            if (localModFile.name !in remoteModFileNames) {
                println("\uD83D\uDEAB Deleting the mod '${localModFile.name}' as it's no longer on the server.")
                localModFile.deleteExistingOrTerminate(
                    fileEntityType = "mod",
                    reasonOfDelete = "it's no longer on the server",
                )
            }
        }
    }

    private fun getCurrentEnvironmentModsOrAll(mods: List<Mod>): List<Mod> {
        if (syncInfo.shouldSyncOnlyModsForCurrentEnvironment) {
            val currentEnvironmentMods =
                mods.filter { mod ->
                    if (!mod.shouldSyncOnCurrentEnvironment()) {
                        val modFilePath = getModFilePath(mod)
                        if (modFilePath.exists()) {
                            println("❌ Deleting the mod '${modFilePath.name}' as it's not needed on the current environment.")
                            modFilePath.deleteExistingOrTerminate(
                                fileEntityType = "mod",
                                reasonOfDelete = "it's not required on the current environment",
                            )
                        }
                        // Exclude the mod as it's not needed in the current environment
                        return@filter false
                    }
                    // Include the mod
                    true
                }
            return currentEnvironmentMods
        }
        return mods
    }

    private suspend fun getModsForDownloadAndValidateIfRequired(
        mods: List<Mod>,
        loadingIndicatorDialog: LoadingIndicatorDialog?,
    ): List<Mod> {
        return mods.filter { mod ->
            val modFileName = getFileNameFromUrlOrError(mod.downloadUrl)
            val modFilePath = getModFilePath(mod)
            if (modFilePath.exists()) {
                if (!mod.shouldVerifyFileIntegrity()) {
                    println("ℹ️ The mod: '$modFileName' is set to not be verified. Skipping to the next mod.")
                    return@filter false
                }

                loadingIndicatorDialog?.updateComponentProperties(
                    title =
                        "Verifying mods",
                    infoText = "Verifying ${mod.getDisplayName()}",
                    progress =
                        mods.calculateProgressByIndex(currentIndex = mods.indexOf(mod)),
                    detailsText =
                        "Verifying the mod files integrity...",
                )
                val hasValidModIntegrity = mod.hasValidFileIntegrityOrError(modFilePath)
                if (hasValidModIntegrity == null) {
                    println("❓ The mod: '$modFileName' has an unknown integrity. Skipping to the next mod.")
                    return@filter false
                }
                if (hasValidModIntegrity) {
                    println("✅ The mod: '$modFileName' has valid file integrity. Skipping to the next mod.")
                    return@filter false
                }
                println(
                    "❌ The mod: '$modFileName' has invalid integrity. Deleting the mod " +
                        "and downloading it again.",
                )
                modFilePath.deleteExistingOrTerminate(
                    fileEntityType = "mod",
                    reasonOfDelete = "it has invalid file integrity",
                )
            }

            // Add this mod to the download list process
            true
        }
    }

    private suspend fun downloadMods(
        modsToDownload: List<Mod>,
        totalMods: List<Mod>,
        loadingIndicatorDialog: LoadingIndicatorDialog?,
    ) {
        for ((index, mod) in modsToDownload.withIndex()) {
            val modFileName = getFileNameFromUrlOrError(mod.downloadUrl)
            val modFilePath = getModFilePath(mod)
            if (modFilePath.exists()) {
                println("⚠\uFE0F The mod: '$modFileName' already exists.")
            }

            println("\uD83D\uDD3D Downloading mod '$modFileName' from ${mod.downloadUrl}")

            FileDownloader(
                downloadUrl = mod.downloadUrl,
                // TODO: Avoid converting to File and use the Path instead
                targetFile = modFilePath.toFile(),
                progressListener = { downloadedBytes, downloadedProgress, bytesToDownload ->
                    loadingIndicatorDialog?.updateComponentProperties(
                        title =
                            buildTitleMessage(
                                currentModIndex = index,
                                pendingMods = modsToDownload.size,
                                totalMods = totalMods.size,
                            ),
                        infoText = "Downloading ${mod.getDisplayName()}",
                        progress = downloadedProgress.toInt(),
                        detailsText =
                            "${downloadedBytes.convertBytesToReadableMegabytesAsString()} MB /" +
                                " ${bytesToDownload.convertBytesToReadableMegabytesAsString()} MB",
                    )
                },
            ).downloadFile()

            // This will always validate newly downloaded mods regardless of the configurations
            val isNewlyDownloadedFileHasValidFileIntegrity = mod.hasValidFileIntegrityOrError(modFilePath)
            if (isNewlyDownloadedFileHasValidFileIntegrity == false) {
                showErrorMessageAndTerminate(
                    title = "❌ File Integrity Check Failed",
                    message =
                        "\uD83D\uDEA8 The newly downloaded file has failed the integrity check. This might be due to a bug " +
                            "in the script \uD83D\uDC1B or an incorrect integrity info value provided by the admin.",
                )
            }
        }
    }

    /**
     * @return The file that will be used, we use [SyncInfo.modSyncMarker] to support [isScriptModFile]
     * will be the same file name from the [Mod.downloadUrl] if [SyncInfo.modSyncMarker] is null
     *
     * @see isScriptModFile
     * */
    private fun getModFilePath(mod: Mod): Path {
        val modFileNameWithoutExtension =
            Paths.get(getFileNameFromUrlOrError(mod.downloadUrl)).nameWithoutExtension
        val modFileName =
            buildString {
                append(modFileNameWithoutExtension)
                SyncInfo.instance.modSyncMarker?.let { append(it) }
                append(".${MOD_FILE_EXTENSION}")
            }
        return modsDirectoryPath.resolve(modFileName)
    }

    /**
     * @return if this mod is created/synced by the script
     * it will be identified by [SyncInfo.modSyncMarker] and will always return true
     * if [SyncInfo.modSyncMarker] is null
     *
     * @see getModFilePath
     * */
    private fun isScriptModFile(modFilePath: Path): Boolean =
        modFilePath.name.endsWith(
            "${SyncInfo.instance.modSyncMarker.orEmpty()}.${MOD_FILE_EXTENSION}",
        )

    /**
     * @return The message that will be used for the dialog that will show the progress
     * of syncing, downloading and verifying mods
     * */
    private fun buildTitleMessage(
        currentModIndex: Int,
        pendingMods: Int,
        totalMods: Int,
    ): String =
        buildString {
            append("${currentModIndex + 1} of $pendingMods")
            if (pendingMods != totalMods) {
                append(" ($totalMods total)")
            }
        }
}
