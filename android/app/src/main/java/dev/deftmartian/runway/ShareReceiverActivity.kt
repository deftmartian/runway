package dev.deftmartian.runway

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : ComponentActivity() {
    private var status by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        status = getString(R.string.share_checking)
        setContent {
            RunwayTheme {
                ShareReceiverScreen(
                    status = status,
                    onOpenRunway = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
        inspectSharedFile()
    }

    private fun inspectSharedFile() {
        val uri = resolveSingleContentUri()
        if (intent.action != Intent.ACTION_SEND || uri == null) {
            status = getString(R.string.share_rejected)
            return
        }

        val permission = checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        if (permission != PackageManager.PERMISSION_GRANTED) {
            status = getString(R.string.share_rejected)
            return
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                oneOffGpxStatus(OneOffGpxImport.importUri(this@ShareReceiverActivity, uri))
            }
            if (!isDestroyed) status = getString(result)
        }
    }

    private fun resolveSingleContentUri(): Uri? {
        if (intent.clipData?.itemCount?.let { it > 1 } == true) return null

        val candidates = buildList {
            sharedStreamExtra()?.let(::add)
            intent.clipData?.getItemAt(0)?.uri?.let(::add)
        }.distinctBy(Uri::toString)

        return candidates.singleOrNull()?.takeIf { it.scheme == "content" }
    }

    @Suppress("DEPRECATION")
    private fun sharedStreamExtra(): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }
}

@androidx.compose.runtime.Composable
private fun ShareReceiverScreen(
    status: String,
    onOpenRunway: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.share_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = status,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onOpenRunway,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text(stringResource(R.string.open_runway)) }
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text(stringResource(R.string.close)) }
        }
    }
}
