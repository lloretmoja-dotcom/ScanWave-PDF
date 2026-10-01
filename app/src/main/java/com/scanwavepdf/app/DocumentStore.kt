package com.scanwavepdf.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Guarda cada PDF escaneado dentro del propio espacio privado de la
 * aplicación (nadie más puede acceder a esos archivos, ni falta ningún
 * permiso de almacenamiento para esto). Junto a los PDF se guarda un
 * archivo "documents.json" con los datos de cada uno: nombre que ve la
 * persona, fecha, número de páginas, y el texto reconocido (OCR) para
 * poder buscar por contenido además de por nombre.
 */
data class DocumentEntry(
    val id: String,
    val fileName: String,
    var displayName: String,
    val createdAt: Long,
    val pageCount: Int,
    val ocrText: String
)

object DocumentStore {

    private fun documentsDir(context: Context): File {
        val dir = File(context.filesDir, "documentos")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun metadataFile(context: Context): File = File(documentsDir(context), "documents.json")

    fun loadAll(context: Context): MutableList<DocumentEntry> {
        val file = metadataFile(context)
        if (!file.exists()) return mutableListOf()
        return try {
            val text = file.readText()
            val arr = JSONArray(text)
            val list = mutableListOf<DocumentEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    DocumentEntry(
                        id = o.getString("id"),
                        fileName = o.getString("fileName"),
                        displayName = o.getString("displayName"),
                        createdAt = o.getLong("createdAt"),
                        pageCount = o.optInt("pageCount", 1),
                        ocrText = o.optString("ocrText", "")
                    )
                )
            }
            list.sortByDescending { it.createdAt }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveAll(context: Context, list: List<DocumentEntry>) {
        val arr = JSONArray()
        for (entry in list) {
            val o = JSONObject()
            o.put("id", entry.id)
            o.put("fileName", entry.fileName)
            o.put("displayName", entry.displayName)
            o.put("createdAt", entry.createdAt)
            o.put("pageCount", entry.pageCount)
            o.put("ocrText", entry.ocrText)
            arr.put(o)
        }
        metadataFile(context).writeText(arr.toString())
    }

    /** Copia el PDF que entrega el escáner a nuestra carpeta privada y
     * añade su entrada a la lista guardada. Se llama fuera del hilo
     * principal (copiar un archivo puede tardar un poco). */
    fun addDocument(
        context: Context,
        sourcePdfUri: Uri,
        pageCount: Int,
        ocrText: String
    ): DocumentEntry {
        val id = UUID.randomUUID().toString()
        val fileName = "doc_$id.pdf"
        val destFile = File(documentsDir(context), fileName)
        context.contentResolver.openInputStream(sourcePdfUri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val dateLabel = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val entry = DocumentEntry(
            id = id,
            fileName = fileName,
            displayName = "Documento $dateLabel",
            createdAt = System.currentTimeMillis(),
            pageCount = pageCount,
            ocrText = ocrText
        )
        val all = loadAll(context)
        all.add(0, entry)
        saveAll(context, all)
        return entry
    }

    fun renameDocument(context: Context, id: String, newName: String) {
        val all = loadAll(context)
        val entry = all.find { it.id == id } ?: return
        entry.displayName = newName
        saveAll(context, all)
    }

    fun deleteDocument(context: Context, id: String) {
        val all = loadAll(context)
        val entry = all.find { it.id == id } ?: return
        File(documentsDir(context), entry.fileName).delete()
        all.remove(entry)
        saveAll(context, all)
    }

    fun fileFor(context: Context, entry: DocumentEntry): File =
        File(documentsDir(context), entry.fileName)

    /** Quita acentos para que buscar "informacion" encuentre también
     * "información", sin importar mayúsculas/minúsculas. */
    private fun normalize(text: String): String {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{Mn}+"), "").lowercase(Locale.getDefault())
    }

    fun matchesSearch(entry: DocumentEntry, query: String): Boolean {
        if (query.isBlank()) return true
        val needle = normalize(query)
        return normalize(entry.displayName).contains(needle) || normalize(entry.ocrText).contains(needle)
    }
}
