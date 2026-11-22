package com.margelo.nitro.splashmotion

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.facebook.react.ReactActivity

/**
 * Zero-config setup: Android instantiates this provider before
 * `Application.onCreate`, letting us hook the first ReactActivity right
 * before its `onCreate` — no MainActivity changes needed.
 */
class SplashMotionInitProvider : ContentProvider() {
  override fun onCreate(): Boolean {
    val app = context?.applicationContext as? Application ?: return true
    SplashMotionController.preload(app)
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      // Every ReactActivity, not just the first: one reopened after Back or
      // recreated still starts on the splash theme and needs to leave it.
      private fun handle(activity: Activity) {
        if (activity is ReactActivity) SplashMotionController.install(activity)
      }

      // API 29+: runs before Activity.onCreate — the ideal moment.
      override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        handle(activity)
      }

      // Older devices: runs inside super.onCreate, still before setContentView.
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) handle(activity)
      }

      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityResumed(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {}
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {
        if (activity is ReactActivity) SplashMotionController.onDestroyed(activity)
      }
    })
    return true
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
