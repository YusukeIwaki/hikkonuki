package app.hikkonuki.android.platform

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.hikkonuki.android.core.BatchStatus
import app.hikkonuki.android.core.ExportBatch
import app.hikkonuki.android.core.ExportJob
import app.hikkonuki.android.core.ExportOutcome
import app.hikkonuki.android.core.ExportStatus
import app.hikkonuki.android.core.JobPersistence
import app.hikkonuki.android.core.MediaKind

class SqliteJobPersistence(context: Context, name: String? = "exports.db") :
    SQLiteOpenHelper(context, name, null, 2), JobPersistence {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE jobs(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              batch_id INTEGER NOT NULL,
              source_id TEXT NOT NULL,
              display_name TEXT NOT NULL,
              kind TEXT NOT NULL,
              source_size INTEGER NOT NULL,
              status TEXT NOT NULL,
              output_uri TEXT,
              output_size INTEGER NOT NULL DEFAULT 0,
              outcome TEXT,
              detail TEXT,
              error TEXT,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE batches(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              status TEXT NOT NULL,
              total INTEGER NOT NULL DEFAULT 0,
              succeeded INTEGER NOT NULL DEFAULT 0,
              failed INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS jobs")
        db.execSQL("DROP TABLE IF EXISTS batches")
        onCreate(db)
    }

    override fun loadJobs(): List<ExportJob> {
        val result = ArrayList<ExportJob>()
        readableDatabase.query("jobs", null, null, null, null, null, "id ASC").use { cursor ->
            fun text(column: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(column))
            fun long(column: String): Long = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            while (cursor.moveToNext()) {
                result += ExportJob(
                    id = long("id"),
                    batchId = long("batch_id"),
                    sourceId = text("source_id")!!,
                    displayName = text("display_name")!!,
                    kind = MediaKind.valueOf(text("kind")!!),
                    sourceSizeBytes = long("source_size"),
                    status = ExportStatus.valueOf(text("status")!!),
                    progressPercent = if (text("status") == ExportStatus.COMPLETED.name) 100 else 0,
                    outputUri = text("output_uri"),
                    outputSizeBytes = long("output_size"),
                    outcome = text("outcome")?.let(ExportOutcome::valueOf),
                    detail = text("detail"),
                    errorMessage = text("error"),
                    createdAtMillis = long("created_at"),
                    updatedAtMillis = long("updated_at"),
                )
            }
        }
        return result
    }

    private fun values(job: ExportJob) = ContentValues().apply {
        put("batch_id", job.batchId)
        put("source_id", job.sourceId)
        put("display_name", job.displayName)
        put("kind", job.kind.name)
        put("source_size", job.sourceSizeBytes)
        put("status", job.status.name)
        put("output_uri", job.outputUri)
        put("output_size", job.outputSizeBytes)
        put("outcome", job.outcome?.name)
        put("detail", job.detail)
        put("error", job.errorMessage)
        put("created_at", job.createdAtMillis)
        put("updated_at", job.updatedAtMillis)
    }

    override fun insert(job: ExportJob): Long = writableDatabase.insertOrThrow("jobs", null, values(job))

    override fun update(job: ExportJob) {
        writableDatabase.update("jobs", values(job), "id=?", arrayOf(job.id.toString()))
    }

    override fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.delete("jobs", "id IN (${ids.joinToString(",")})", null)
    }

    override fun loadBatches(): List<ExportBatch> {
        val result = ArrayList<ExportBatch>()
        readableDatabase.query("batches", null, null, null, null, null, "id DESC").use { cursor ->
            fun long(column: String): Long = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            while (cursor.moveToNext()) {
                result += ExportBatch(
                    id = long("id"),
                    status = BatchStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
                    totalCount = long("total").toInt(),
                    succeededCount = long("succeeded").toInt(),
                    failedCount = long("failed").toInt(),
                    createdAtMillis = long("created_at"),
                    updatedAtMillis = long("updated_at"),
                )
            }
        }
        return result
    }

    private fun values(batch: ExportBatch) = ContentValues().apply {
        put("status", batch.status.name)
        put("total", batch.totalCount)
        put("succeeded", batch.succeededCount)
        put("failed", batch.failedCount)
        put("created_at", batch.createdAtMillis)
        put("updated_at", batch.updatedAtMillis)
    }

    override fun insertBatch(batch: ExportBatch): Long = writableDatabase.insertOrThrow("batches", null, values(batch))

    override fun updateBatch(batch: ExportBatch) {
        writableDatabase.update("batches", values(batch), "id=?", arrayOf(batch.id.toString()))
    }

    override fun deleteBatches(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.delete("batches", "id IN (${ids.joinToString(",")})", null)
    }
}
