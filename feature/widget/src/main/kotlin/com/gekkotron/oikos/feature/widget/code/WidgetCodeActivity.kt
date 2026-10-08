package com.gekkotron.oikos.feature.widget.code

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.model.CodeFormat

/** A transparent host for the code keypad (widgets spec §2.3). */
class WidgetCodeActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_FORMAT = "format"

        fun intent(context: Context, id: String, command: WidgetCommand, format: CodeFormat): Intent =
            Intent(context, WidgetCodeActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_COMMAND, command.encode())
                .putExtra(EXTRA_FORMAT, format.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
