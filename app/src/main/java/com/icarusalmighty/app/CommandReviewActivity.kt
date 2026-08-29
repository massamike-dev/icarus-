package com.icarusalmighty.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.icarusalmighty.app.tools.CommandRouter

class CommandReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        val parsed = CommandRouter.parse(command)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        root.addView(TextView(this).apply { text = "ICARUS heard:\n\n$command\n\n${parsed.description}"; textSize = 20f })
        root.addView(Button(this).apply {
            text = if (parsed.requiresConfirmation) "Confirm" else "Run"
            setOnClickListener { CommandRouter.execute(this@CommandReviewActivity, parsed); finish() }
        })
        root.addView(Button(this).apply { text = "Cancel"; setOnClickListener { finish() } })
        setContentView(root)
    }

    companion object {
        private const val EXTRA_COMMAND = "command"
        fun launch(context: Context, command: String) = context.startActivity(Intent(context, CommandReviewActivity::class.java).putExtra(EXTRA_COMMAND, command).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
