package com.scanwavepdf.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var emptyText: View
    private lateinit var documentsList: RecyclerView
    private lateinit var adapter: DocumentsAdapter

    private var allDocuments: MutableList<DocumentEntry> = mutableListOf()

    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // Anuncio de pantalla completa después de guardar un documento (eso
    // es una pausa natural entre tareas, permitido por las normas de
    // Google) — como mucho uno cada 15 minutos.
    private var interstitialAd: InterstitialAd? = null
    private var lastInterstitialShownAt: Long = 0L
    private val interstitialMinGapMs = 15 * 60 * 1000L

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            if (scanResult != null) {
                handleScanResult(scanResult)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        searchInput = findViewById(R.id.searchInput)
        emptyText = findViewById(R.id.emptyText)
        documentsList = findViewById(R.id.documentsList)

        allDocuments = DocumentStore.loadAll(this)

        adapter = DocumentsAdapter(
            items = allDocuments,
            onShare = { shareDocument(it) },
            onRename = { showRenameDialog(it) },
            onDelete = { showDeleteDialog(it) }
        )
        documentsList.layoutManager = LinearLayoutManager(this)
        documentsList.adapter = adapter

        refreshList("")

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<View>(R.id.scanButton).setOnClickListener { startScan() }

        setupAdBanner()
        loadInterstitialAd()
    }

    private fun refreshList(query: String) {
        val filtered = allDocuments.filter { DocumentStore.matchesSearch(it, query) }
        adapter.updateItems(filtered)
        emptyText.visibility = if (allDocuments.isEmpty()) View.VISIBLE else View.GONE
    }

    /* ---- Escanear un documento nuevo ---- */

    private fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                Toast.makeText(this, "No se pudo abrir el escáner. Inténtalo otra vez.", Toast.LENGTH_LONG).show()
            }
    }

    private fun handleScanResult(scanResult: GmsDocumentScanningResult) {
        val pdf = scanResult.pdf
        if (pdf == null) {
            Toast.makeText(this, "No se pudo guardar el documento.", Toast.LENGTH_LONG).show()
            return
        }
        val pageUris = scanResult.pages?.map { it.imageUri } ?: emptyList()
        Toast.makeText(this, "Guardando documento…", Toast.LENGTH_SHORT).show()
        recognizeTextForPages(pageUris, 0, StringBuilder()) { ocrText ->
            lifecycleScope.launch(Dispatchers.IO) {
                val entry = DocumentStore.addDocument(this@MainActivity, pdf.uri, pdf.pageCount, ocrText)
                runOnUiThread {
                    allDocuments.add(0, entry)
                    refreshList(searchInput.text?.toString() ?: "")
                    Toast.makeText(this@MainActivity, "Documento guardado", Toast.LENGTH_SHORT).show()
                    maybeShowInterstitialAd()
                }
            }
        }
    }

    /** Reconoce el texto de cada página, una detrás de otra, para no
     * complicar con más librerías de coroutines. Si una página falla,
     * se sigue con las demás — el documento se guarda igualmente. */
    private fun recognizeTextForPages(
        pageUris: List<Uri>,
        index: Int,
        acc: StringBuilder,
        onDone: (String) -> Unit
    ) {
        if (index >= pageUris.size) {
            onDone(acc.toString())
            return
        }
        try {
            val image = InputImage.fromFilePath(this, pageUris[index])
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    acc.append(visionText.text).append("\n")
                    recognizeTextForPages(pageUris, index + 1, acc, onDone)
                }
                .addOnFailureListener {
                    recognizeTextForPages(pageUris, index + 1, acc, onDone)
                }
        } catch (e: Exception) {
            recognizeTextForPages(pageUris, index + 1, acc, onDone)
        }
    }

    /* ---- Acciones sobre un documento ya guardado ---- */

    private fun shareDocument(entry: DocumentEntry) {
        val file = DocumentStore.fileFor(this, entry)
        if (!file.exists()) {
            Toast.makeText(this, "No se encuentra el archivo.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "com.scanwavepdf.app.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir documento"))
    }

    private fun showRenameDialog(entry: DocumentEntry) {
        val input = EditText(this)
        input.setText(entry.displayName)
        AlertDialog.Builder(this)
            .setTitle("Cambiar nombre")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    DocumentStore.renameDocument(this, entry.id, newName)
                    entry.displayName = newName
                    refreshList(searchInput.text?.toString() ?: "")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDeleteDialog(entry: DocumentEntry) {
        AlertDialog.Builder(this)
            .setTitle("Borrar documento")
            .setMessage("¿Seguro que quieres borrar \"${entry.displayName}\"? No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                DocumentStore.deleteDocument(this, entry.id)
                allDocuments.remove(entry)
                refreshList(searchInput.text?.toString() ?: "")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /* ---- Publicidad (AdMob) ---- */

    private fun setupAdBanner() {
        MobileAds.initialize(this) {}
        val testAdUnitId = "ca-app-pub-3940256099942544/6300978111"
        val adContainer = findViewById<FrameLayout>(R.id.adContainer)
        val adView = AdView(this)
        adView.adUnitId = testAdUnitId
        adView.setAdSize(AdSize.BANNER)
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                adContainer.visibility = View.VISIBLE
            }
            override fun onAdFailedToLoad(adError: LoadAdError) {
                adContainer.visibility = View.GONE
            }
            override fun onAdClosed() {
                adContainer.visibility = View.GONE
            }
        }
        adContainer.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun loadInterstitialAd() {
        val testInterstitialUnitId = "ca-app-pub-3940256099942544/1033173712"
        InterstitialAd.load(
            this,
            testInterstitialUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            interstitialAd = null
                            loadInterstitialAd()
                        }
                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            interstitialAd = null
                            loadInterstitialAd()
                        }
                    }
                }
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    interstitialAd = null
                }
            }
        )
    }

    private fun maybeShowInterstitialAd() {
        val now = System.currentTimeMillis()
        if (now - lastInterstitialShownAt < interstitialMinGapMs) return
        val ad = interstitialAd ?: return
        lastInterstitialShownAt = now
        ad.show(this)
    }
}
