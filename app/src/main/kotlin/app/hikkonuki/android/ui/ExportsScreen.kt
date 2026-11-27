package app.hikkonuki.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.hikkonuki.android.R
import androidx.compose.ui.unit.dp
import app.hikkonuki.android.core.BatchStatus
import app.hikkonuki.android.core.ExportBatch
import app.hikkonuki.android.core.ExportJob
import app.hikkonuki.android.core.ExportStatus
import app.hikkonuki.android.core.FailureReason
import app.hikkonuki.android.platform.MediaStoreGallery

/**
 * Shows the latest export batch only: its rows while it is ACTIVE (running or queued),
 * or its one-line summary once DONE. By then the rows
 * have been deleted and only the final result is kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportsScreen(
    jobs: List<ExportJob>,
    batch: ExportBatch?,
    onBack: () -> Unit,
    onCancel: (Long) -> Unit,
    onClearHistory: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_status)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("exportsBack")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (batch != null) {
                        TextButton(onClick = onClearHistory, modifier = Modifier.testTag("clearExportHistory")) {
                            Text(stringResource(R.string.clear_history))
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            batch == null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.no_export_history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("exportsEmpty"))
            }
            batch.status == BatchStatus.DONE -> Column(modifier = Modifier.fillMaxSize().padding(padding).testTag("exportsSummary")) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.export_result)) },
                    supportingContent = {
                        Column {
                            Text(
                                if (batch.failedCount > 0) {
                                    stringResource(R.string.result_summary_with_failures, batch.totalCount, batch.succeededCount, batch.failedCount)
                                } else {
                                    pluralStringResource(R.plurals.result_all_succeeded, batch.totalCount, batch.totalCount)
                                },
                            )
                            Text(stringResource(R.string.finished_at, formatDetailDate(batch.updatedAtMillis)))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).testTag("exportsList")) {
                items(jobs, key = { it.id }) { job ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        ListItem(
                            headlineContent = { Text(job.displayName) },
                            supportingContent = {
                                Column {
                                    Text(statusText(job))
                                    if (job.status == ExportStatus.COMPLETED) {
                                        Text(stringResource(R.string.joined, formatSize(job.outputSizeBytes), "DCIM/${MediaStoreGallery.FOLDER}"))
                                    }
                                    failureText(job)?.let { Text(it) }
                                    // What the system reported, for a failure the app has no words
                                    // for. It is not translated, so it sits below the localized line.
                                    if (job.status == ExportStatus.FAILED && (job.failure ?: FailureReason.OTHER) == FailureReason.OTHER) {
                                        job.errorMessage?.takeIf { it.isNotEmpty() }?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("failureDetail_${job.id}"))
                                        }
                                    }
                                }
                            },
                            trailingContent = {
                                if (job.status == ExportStatus.QUEUED || job.status == ExportStatus.RUNNING) {
                                    IconButton(onClick = { onCancel(job.id) }, modifier = Modifier.testTag("cancelJob_${job.id}")) {
                                        Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.cancel))
                                    }
                                }
                            },
                        )
                        if (job.status == ExportStatus.RUNNING || job.status == ExportStatus.QUEUED) {
                            LinearProgressIndicator(
                                progress = { job.progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth().testTag("exportProgress_${job.id}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(job: ExportJob): String = when (job.status) {
    ExportStatus.QUEUED -> stringResource(R.string.status_queued)
    ExportStatus.RUNNING -> stringResource(R.string.status_running, job.progressPercent)
    ExportStatus.COMPLETED -> stringResource(R.string.status_completed)
    ExportStatus.FAILED -> stringResource(R.string.status_failed)
}

/** The failure in the reader's language. */
@Composable
private fun failureText(job: ExportJob): String? = if (job.status != ExportStatus.FAILED) null else when (job.failure) {
    FailureReason.USB_REMOVED -> stringResource(R.string.error_usb_removed)
    FailureReason.NO_SPACE -> stringResource(R.string.error_no_space)
    FailureReason.INCOMPLETE_COPY -> stringResource(R.string.error_incomplete_copy)
    FailureReason.EMPTY_OUTPUT -> stringResource(R.string.error_empty_output)
    FailureReason.DESTINATION_UNAVAILABLE -> stringResource(R.string.error_destination_unavailable)
    FailureReason.OTHER, null -> stringResource(R.string.error_unexpected)
}
