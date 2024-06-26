package constants

import java.io.File

/**
 * Files of the minecraft instance (e.g.,`.minecraft`) where it has the mods, resource-packs and shaders
 * The script executable file is on the same level as those files
 *
 * This should be only used by the script that syncs the content
 * */
sealed class SyncScriptDotMinecraftFiles(
    val file: File,
) {
    data object Mods : SyncScriptDotMinecraftFiles(File(DotMinecraftFileNames.MODS_DIRECTORY))

    data object ResourcePacks : SyncScriptDotMinecraftFiles(File(DotMinecraftFileNames.RESOURCE_PACKS_DIRECTORY))

    data object ShaderPacks : SyncScriptDotMinecraftFiles(File(DotMinecraftFileNames.SHADER_PACKS_DIRECTORY))

    data object Config : SyncScriptDotMinecraftFiles(File(DotMinecraftFileNames.CONFIG_DIRECTORY))

    data object Saves : SyncScriptDotMinecraftFiles(File(DotMinecraftFileNames.SAVES_DIRECTORY))

    /**
     * A txt file contains the minecraft options/settings or data like the key bindings, sound settings
     * enabled resource-packs, language and the minecraft video settings, and more
     * */
    data object Options : SyncScriptDotMinecraftFiles(File(DotMinecraftFileNames.OPTIONS_FILE))

    /**
     * A file that contains all the information about the list of servers
     * */
    data object ServersDat : SyncScriptDotMinecraftFiles(File(DotMinecraftFileNames.SERVERS_FILE))

    /**
     * This directory will be created and managed by the script, it's specific to it
     * */
    data object SyncScriptData : SyncScriptDotMinecraftFiles(File(DotMinecraftFileNames.SYNC_SCRIPT_DIRECTORY)) {
        /**
         * The script needs a config file that contains required data and other optional to configure it
         * */
        data object ScriptConfig : SyncScriptDotMinecraftFiles(File(SyncScriptData.file, "config.json"))

        /**
         * A directory that contain the temporary files used by the script,
         * for example, when downloading a file, the content will be saved to this directory
         * and after finish downloading, the file will be moved into where it should.
         * */
        data object Temp : SyncScriptDotMinecraftFiles(File(SyncScriptData.file, "temp"))

        /**
         * A file will be used to indicate if the user did set up the preferences for using the script, and it will
         * be only shown once. If this file doesn't exit, then we will ask for the user preferences like the preferred theme
         *
         * The reason why we store this in a file and not in the [ScriptConfig] to make it easy for the user to delete
         * it later and launch the dialog again.
         * */
        data object IsPreferencesConfigured :
            SyncScriptDotMinecraftFiles(File(SyncScriptData.file, "isPreferencesConfigured"))
    }
}