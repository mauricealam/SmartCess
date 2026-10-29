package com.smartcess.app.features.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.smartcess.app.R
import com.smartcess.app.core.ai.ContentSafety
import com.smartcess.app.core.ai.DocumentSummary
import com.smartcess.app.core.ai.OcrEngine
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.ai.SessionExpiredException
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.work.ReminderScheduler
import com.smartcess.app.data.local.entity.DocumentEntity
import com.smartcess.app.data.local.entity.ReminderEntity
import com.smartcess.app.data.repository.DocumentRepository
import com.smartcess.app.data.repository.ReminderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DocumentsUiState(
    val reading: Boolean = false,
    val draft: DocumentSummary? = null,
    val draftSource: String? = null,
    val messageRes: Int? = null,
    val errorRes: Int? = null,
    val offline: Boolean = false,
    val signInRequired: Boolean = false,
)

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val spark: SparkClient,
    private val ocr: OcrEngine,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val items: StateFlow<List<DocumentEntity>> = documents.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(DocumentsUiState())
    val state: StateFlow<DocumentsUiState> = _state.asStateFlow()

    /** Imports a text, image, or single-page PDF file; images/PDFs are read on-device with ML Kit OCR. */
    fun importFile(uri: Uri) {
        viewModelScope.launch {
            _state.value = DocumentsUiState(reading = true)
            val mime = context.contentResolver.getType(uri) ?: ""
            if (!isSupported(mime)) {
                _state.value = DocumentsUiState(errorRes = R.string.doc_unsupported)
                return@launch
            }
            val read = runCatching { readText(uri) }.getOrNull()
            val text = read?.text
            if (text.isNullOrBlank()) {
                val res = if (mime == "application/pdf") {
                    R.string.doc_pdf_failed
                } else {
                    R.string.documents_import_failed
                }
                _state.value = DocumentsUiState(errorRes = res)
                return@launch
            }
            val result = runCatching { spark.summarizeDocument(text, null) }
            val summary = result.getOrNull()
            if (summary == null) {
                _state.value = DocumentsUiState(
                    draft = DocumentSummary(
                        title = "",
                        type = "",
                        summary = "${ContentSafety.OFFLINE_LABEL} ${text.take(500)}",
                        confidence = 0.0,
                    ),
                    draftSource = uri.toString(),
                    offline = true,
                    signInRequired = result.exceptionOrNull() is SessionExpiredException,
                    messageRes = read?.noteRes,
                )
            } else {
                _state.value = DocumentsUiState(
                    draft = summary,
                    draftSource = uri.toString(),
                    messageRes = read?.noteRes,
                )
            }
        }
    }

    private fun isSupported(mime: String): Boolean =
        mime.isBlank() || mime.startsWith("text/") || mime.startsWith("image/") ||
            mime == "application/pdf"

    fun clearDraft() {
        _state.value = _state.value.copy(draft = null, draftSource = null)
    }

    /** Saves the draft only after the caller shows the confirmation dialog. */
    fun confirmSaveDraft(onSaved: (Long) -> Unit) {
        val draft = _state.value.draft ?: return
        viewModelScope.launch {
            val outcome = documents.save(
                DocumentEntity(
                    title = draft.title.ifBlank { draft.summary.take(40) },
                    type = draft.type.ifBlank { "other" },
                    summary = draft.summary,
                    keyPoints = draft.key_points,
                    deadline = draft.deadline,
                    amount = draft.amount,
                    requiredAction = draft.required_action,
                    sourcePath = _state.value.draftSource,
                    confidence = draft.confidence,
                ),
            )
            if (outcome.validation.hasErrors || outcome.savedId == null) {
                _state.value = _state.value.copy(errorRes = R.string.error_generic)
            } else {
                _state.value = DocumentsUiState(messageRes = R.string.documents_saved)
                onSaved(outcome.savedId)
            }
        }
    }

    companion object {
        /** PDF render cap: readable for OCR without blowing memory. */
        private const val MAX_PDF_EDGE = 2048f
        private const val MAX_PDF_SCALE = 2.5f
    }

    private data class ReadResult(val text: String, @StringRes val noteRes: Int? = null)

    private suspend fun readText(uri: Uri): ReadResult = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri) ?: ""
        if (mime.startsWith("text/")) {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader().use {
                it?.readText()
            } ?: ""
            return@withContext ReadResult(text)
        }
        if (mime == "application/pdf") return@withContext renderFirstPageAndOcr(uri)
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return@withContext ReadResult("")
        val text = ocr.recognizeText(InputImage.fromBitmap(bitmap, 0))
        bitmap.recycle()
        ReadResult(text)
    }

    /**
     * Single-page-first PDF support (spec): renders page 0 on-device and OCRs
     * it. Never leaves the phone; multi-page PDFs keep a first-page note.
     */
    private suspend fun renderFirstPageAndOcr(uri: Uri): ReadResult = withContext(Dispatchers.IO) {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        } ?: return@withContext ReadResult("")
        pfd.use { descriptor ->
            try {
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) return@withContext ReadResult("")
                    renderer.openPage(0).use { page ->
                        val scale = minOf(
                            MAX_PDF_EDGE / page.width.toFloat(),
                            MAX_PDF_EDGE / page.height.toFloat(),
                            MAX_PDF_SCALE,
                        ).coerceAtLeast(1f)
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).toInt(),
                            (page.height * scale).toInt(),
                            Bitmap.Config.ARGB_8888,
                        )
                        val matrix = Matrix().apply { setScale(scale, scale) }
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val text = ocr.recognizeText(InputImage.fromBitmap(bitmap, 0))
                        bitmap.recycle()
                        val note = if (renderer.pageCount > 1) R.string.doc_first_page_only else null
                        return@withContext ReadResult(text, note)
                    }
                }
            } catch (_: Exception) {
                return@withContext ReadResult("")
            }
        }
    }
}

data class DocumentResultUiState(
    val loading: Boolean = true,
    val document: DocumentEntity? = null,
    val notFound: Boolean = false,
    val answer: String? = null,
    val noAnswer: Boolean = false,
    val messageRes: Int? = null,
    val errorRes: Int? = null,
)

@HiltViewModel
class DocumentResultViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val speaker: Speaker,
) : ViewModel() {

    private val _state = MutableStateFlow(DocumentResultUiState())
    val state: StateFlow<DocumentResultUiState> = _state.asStateFlow()

    fun load(docId: Long) {
        viewModelScope.launch {
            val doc = documents.getById(docId)
            _state.value = if (doc == null) {
                DocumentResultUiState(loading = false, notFound = true)
            } else {
                DocumentResultUiState(loading = false, document = doc)
            }
        }
    }

    fun speak(text: String) = speaker.speak(text)

    fun stop() = speaker.stop()

    /** On-device extractive Q&A: finds document sentences matching the question. */
    fun ask(question: String) {
        val doc = _state.value.document ?: return
        val words = question.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
        val sentences = (listOf(doc.summary) + doc.keyPoints)
            .flatMap { it.split(Regex("(?<=[.!?])\\s+")) }
            .filter { it.isNotBlank() }
        val best = sentences.maxByOrNull { sentence ->
            val lower = sentence.lowercase()
            words.count { lower.contains(it) }
        }
        val score = best?.let { sentence ->
            words.count { sentence.lowercase().contains(it) }
        } ?: 0
        _state.value = if (best != null && score > 0) {
            _state.value.copy(answer = best.trim(), noAnswer = false)
        } else {
            _state.value.copy(answer = null, noAnswer = true)
        }
    }

    /** Creates a deadline reminder only after the caller confirms. */
    fun confirmDeadlineReminder() {
        val doc = _state.value.document ?: return
        val deadline = doc.deadline ?: return
        viewModelScope.launch {
            val triggerAt = runCatching {
                LocalDate.parse(deadline).atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }.getOrNull() ?: return@launch
            if (triggerAt <= System.currentTimeMillis()) {
                _state.value = _state.value.copy(errorRes = R.string.error_generic)
                return@launch
            }
            val id = reminders.create(
                kind = ReminderEntity.KIND_DOCUMENT,
                title = doc.title,
                triggerAtMillis = triggerAt,
                referenceId = doc.id,
            )
            scheduler.schedule(id)
            _state.value = _state.value.copy(messageRes = R.string.bill_reminder_set)
        }
    }
}
