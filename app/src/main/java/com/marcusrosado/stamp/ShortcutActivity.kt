package com.marcusrosado.stamp

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Where the app icon's shortcuts land. The launcher starts a static shortcut with
 * FLAG_ACTIVITY_CLEAR_TASK, which would throw away the open app, a card or a half-written note
 * with it. This activity sits in a task of its own, so that flag clears only that task, and it
 * hands the shortcut to MainActivity, which takes it in onNewIntent when it is already open.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(intent.action)
                .putExtras(intent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}
