package com.murshed.kdrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Placeholder home screen.
 *
 * TODO: permission request flow (media + notifications) — prompts/08-android-project-init.md
 * TODO: navigation to Gallery/Settings — prompts/11-android-settings-gallery-ui.md
 * TODO: start MediaObserverService — prompts/09-android-mediastore-observer.md
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(text = "KDrive")
                        Text(text = "Scaffold running. Run prompts 08–11 to build the real app.")
                    }
                }
            }
        }
    }
}
