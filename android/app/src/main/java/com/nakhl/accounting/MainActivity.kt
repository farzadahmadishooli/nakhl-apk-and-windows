package com.nakhl.accounting

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSaveName: String? = null
    private var pendingSaveData: ByteArray? = null

    companion object {
        private const val PICK_FILE = 1001
        private const val SAVE_FILE = 1002
        private const val PICK_FOLDER = 1003
        private const val PREFS = "nakhl_prefs"
        private const val PREF_TREE = "backup_tree_uri"
        private const val LATEST = "nakhl-backup-latest.json"
        private const val KEEP_DAYS = 30
    }

    // ---------- Automatic backup helpers ----------

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun savedTree(): Uri? = prefs().getString(PREF_TREE, null)?.let { Uri.parse(it) }

    private fun treeUsable(): Boolean {
        val tree = savedTree() ?: return false
        return contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
    }

    private fun appBackupDir(): File {
        val base = getExternalFilesDir(null) ?: filesDir
        val dir = File(base, "Backup")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun dayFileName(): String =
        "nakhl-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    private val dayFileRegex = Regex("^nakhl-backup-\\d{4}-\\d{2}-\\d{2}\\.json$")

    private fun pruneDir(dir: File) {
        val days = (dir.listFiles() ?: emptyArray<File>()).filter { dayFileRegex.matches(it.name) }.sortedBy { it.name }
        if (days.size > KEEP_DAYS) days.take(days.size - KEEP_DAYS).forEach { it.delete() }
    }

    // List of (documentId, displayName) of the files inside the chosen folder
    private fun listTree(tree: Uri): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                result.add(Pair(c.getString(0) ?: "", c.getString(1) ?: ""))
            }
        }
        return result
    }

    private fun writeToTree(tree: Uri, existing: List<Pair<String, String>>, name: String, bytes: ByteArray) {
        val found = existing.firstOrNull { it.second == name }
        val doc: Uri = if (found != null) {
            DocumentsContract.buildDocumentUriUsingTree(tree, found.first)
        } else {
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(contentResolver, root, "application/json", name)
                ?: throw IllegalStateException("create failed")
        }
        val out = contentResolver.openOutputStream(doc, "wt") ?: throw IllegalStateException("open failed")
        out.use { it.write(bytes) }
    }

    private fun pruneTree(tree: Uri, existing: List<Pair<String, String>>) {
        val days = existing.filter { dayFileRegex.matches(it.second) }.sortedBy { it.second }
        if (days.size > KEEP_DAYS) {
            for (d in days.take(days.size - KEEP_DAYS)) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, d.first))
                } catch (e: Exception) {
                }
            }
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun saveBackup(fileName: String, base64Data: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                runOnUiThread {
                    pendingSaveName = fileName
                    pendingSaveData = bytes
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, fileName)
                    }
                    startActivityForResult(intent, SAVE_FILE)
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "ذخیره فایل پشتیبان انجام نشد", Toast.LENGTH_LONG).show() }
            }
        }

        // Called by the page after every change. Writes the latest backup + one file per day.
        @Synchronized
        @JavascriptInterface
        fun autoBackup(json: String): String {
            val bytes = json.toByteArray(Charsets.UTF_8)
            val dayName = dayFileName()
            var appOk = false
            try {
                // 1) a copy in the app's own folder (always)
                val dir = appBackupDir()
                File(dir, LATEST).writeBytes(bytes)
                File(dir, dayName).writeBytes(bytes)
                pruneDir(dir)
                appOk = true
            } catch (e: Exception) {
            }
            if (treeUsable()) {
                // 2) a copy in the folder chosen by the user
                try {
                    val tree = savedTree()!!
                    val existing = listTree(tree)
                    writeToTree(tree, existing, LATEST, bytes)
                    writeToTree(tree, existing, dayName, bytes)
                    pruneTree(tree, existing)
                    return "{\"ok\":true}"
                } catch (e: Exception) {
                    return "{\"ok\":false}"
                }
            }
            return if (appOk) "{\"ok\":true}" else "{\"ok\":false}"
        }

        @JavascriptInterface
        fun hasBackupFolder(): Boolean = treeUsable()

        @JavascriptInterface
        fun getBackupInfo(): String {
            if (treeUsable()) {
                return try {
                    val id = DocumentsContract.getTreeDocumentId(savedTree()!!)
                    "پوشه انتخاب‌شده: " + id.replace("primary:", "حافظه داخلی/")
                } catch (e: Exception) {
                    "پوشه انتخاب‌شده"
                }
            }
            return "پوشه داخلی برنامه: " + appBackupDir().absolutePath
        }

        @JavascriptInterface
        fun getLatestBackup(): String {
            try {
                if (treeUsable()) {
                    val tree = savedTree()!!
                    val item = listTree(tree).firstOrNull { it.second == LATEST }
                    if (item != null) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, item.first)
                        val input = contentResolver.openInputStream(uri)
                        if (input != null) {
                            val text = input.use { String(it.readBytes(), Charsets.UTF_8) }
                            if (text.isNotEmpty()) return text
                        }
                    }
                }
            } catch (e: Exception) {
            }
            try {
                val f = File(appBackupDir(), LATEST)
                if (f.exists()) return f.readText(Charsets.UTF_8)
            } catch (e: Exception) {
            }
            return ""
        }

        @JavascriptInterface
        fun chooseBackupFolder() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        )
                    }
                    startActivityForResult(intent, PICK_FOLDER)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "انتخاب پوشه ممکن نشد", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.addJavascriptInterface(AndroidBridge(), "NakhlAndroid")
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try {
                    startActivityForResult(intent, PICK_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        web.isFocusableInTouchMode = true
        setContentView(web)
        if (savedInstanceState == null) web.loadUrl("file:///android_asset/index.html")
        else web.restoreState(savedInstanceState)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE) {
            val result = if (resultCode == RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        } else if (requestCode == SAVE_FILE) {
            if (resultCode == RESULT_OK && data?.data != null && pendingSaveData != null) {
                try {
                    contentResolver.openOutputStream(data.data!!)?.use { it.write(pendingSaveData!!) }
                    Toast.makeText(this, "فایل پشتیبان ذخیره شد", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "خطا در ذخیره فایل", Toast.LENGTH_LONG).show()
                }
            }
            pendingSaveName = null
            pendingSaveData = null
        } else if (requestCode == PICK_FOLDER) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    prefs().edit().putString(PREF_TREE, uri.toString()).apply()
                    web.evaluateJavascript("window.onBackupFolder&&window.onBackupFolder()", null)
                } catch (e: Exception) {
                    Toast.makeText(this, "انتخاب پوشه انجام نشد", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized) {
            // make sure the last change is written to the backup folder before the app goes to background
            web.evaluateJavascript("if(typeof abFlush==='function')abFlush()", null)
            web.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
