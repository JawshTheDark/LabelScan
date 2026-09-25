package dev.jawsh.labelscan.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pushes local changes to the PocketBase backend (and, in restore mode, pulls the
 * catalog back). Runs under WorkManager so it survives process death, waits for a
 * network, and retries with backoff. Auto-sync enqueues the "push" mode after each
 * write; the Sync settings screen triggers "push" (Back up now) and "restore".
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = SyncPrefs(applicationContext)
        if (!prefs.enabled || !prefs.configured) return@withContext Result.success()
        val db = LabelDb(applicationContext)
        val pb = PocketBase(prefs.baseUrl)
        val restore = inputData.getString(MODE) == MODE_RESTORE
        try {
            withAuth(pb, prefs) { token ->
                pushDeletions(db, pb, token)
                pushProducts(db, pb, token)
                pushPhotos(db, pb, token)
                if (restore) restore(db, pb, token)
            }
            prefs.lastSyncAt = System.currentTimeMillis()
            Result.success()
        } catch (e: PocketBase.UnauthorizedException) {
            // Bad credentials — retrying won't help until the user fixes settings.
            Result.failure(workDataOf(ERROR to "Sign-in failed — check email/password"))
        } catch (e: IOException) {
            if (runAttemptCount < 5) Result.retry()
            else Result.failure(workDataOf(ERROR to (e.message ?: "network error")))
        } finally {
            db.close()
        }
    }

    /** Ensures a valid token (re-authenticating once on 401), then runs [block] with it. */
    private fun withAuth(pb: PocketBase, prefs: SyncPrefs, block: (String) -> Unit) {
        val token = prefs.token.ifEmpty { authFresh(pb, prefs) }
        try {
            block(token)
        } catch (e: PocketBase.UnauthorizedException) {
            block(authFresh(pb, prefs))
        }
    }

    private fun authFresh(pb: PocketBase, prefs: SyncPrefs): String =
        pb.authWithPassword(prefs.email, prefs.password).also { prefs.token = it }

    private fun pushDeletions(db: LabelDb, pb: PocketBase, token: String) {
        for (d in db.pendingDeletions()) {
            when (d.kind) {
                "product" -> pb.deleteProductByUpc(token, d.upc)
                "photo" -> if (d.remoteId.isNotEmpty()) pb.deleteRecord(token, "photos", d.remoteId)
            }
            db.clearDeletion(d.id)
        }
    }

    private fun pushProducts(db: LabelDb, pb: PocketBase, token: String) {
        for (p in db.dirtyProducts()) {
            pb.upsertProduct(token, p)
            db.markProductSynced(p.upc, p.lastSeen)
        }
    }

    private fun pushPhotos(db: LabelDb, pb: PocketBase, token: String) {
        for (photo in db.dirtyPhotos()) {
            val file = File(photo.path)
            if (!file.exists()) continue // local file gone; skip
            val id = pb.createPhoto(token, photo.upc, photo.kind, photo.createdAt, file.name, file.readBytes())
            if (id.isNotEmpty()) db.markPhotoSynced(photo.id, id)
        }
    }

    private fun restore(db: LabelDb, pb: PocketBase, token: String) {
        var page = 1
        do {
            val (items, pages) = pb.listProducts(token, page)
            items.forEach { db.upsertFromRemote(it.product) }
            page++
        } while (page <= pages)
        // Photo metadata only — the image bytes download lazily when an item is opened.
        page = 1
        do {
            val (items, pages) = pb.listPhotos(token, page)
            for (rp in items) {
                if (rp.filename.isNotEmpty() && !db.photoExistsByRemote(rp.id)) {
                    db.addRemotePhoto(rp.upc, rp.kind, rp.id, rp.createdMs)
                }
            }
            page++
        } while (page <= pages)
    }

    companion object {
        const val MODE = "mode"
        const val MODE_PUSH = "push"
        const val MODE_RESTORE = "restore"
        const val ERROR = "error"
        private const val WORK_PUSH = "labelscan-sync-push"
        private const val WORK_RESTORE = "labelscan-sync-restore"

        private fun request(mode: String) = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(MODE to mode))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        /** Enqueue a push; coalesces bursts of writes into the trailing run. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_PUSH, ExistingWorkPolicy.REPLACE, request(MODE_PUSH))
        }

        /** Pull the catalog from the server (also pushes any local changes first). */
        fun restore(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_RESTORE, ExistingWorkPolicy.REPLACE, request(MODE_RESTORE))
        }
    }
}
