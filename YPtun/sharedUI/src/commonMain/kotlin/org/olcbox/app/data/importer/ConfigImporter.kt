package org.olcbox.app.data.importer

interface ConfigImporter {
    fun getFromClipboard(): String?
    fun copyToClipboard(text: String)

    /**
     * Reads text from an external source. Uses Any because source types differ per platform.
     */
    suspend fun readTextFromSource(source: Any): String?
}