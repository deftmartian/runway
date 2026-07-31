package dev.deftmartian.runway

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : ComponentActivity() {
    private val importModel by viewModels<ShareReceiverImportViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RunwayTheme {
                ShareReceiverScreen(
                    status = stringResource(importModel.statusResource),
                    onOpenRunway = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
        importModel.inspect(intent)
    }
}

/** Retains one share operation across configuration changes without retaining an Activity. */
internal class ShareReceiverImportViewModel(
    application: Application,
) : AndroidViewModel(application) {
    var statusResource by mutableIntStateOf(R.string.share_checking)
        private set
    private var inspectionStarted = false

    fun inspect(intent: Intent) {
        if (inspectionStarted) return
        inspectionStarted = true
        val uri = intent.resolveSingleContentUri()
        if (intent.action != Intent.ACTION_SEND || uri == null) {
            statusResource = R.string.share_rejected
            return
        }

        val context = getApplication<Application>()
        val permission = context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        if (permission != PackageManager.PERMISSION_GRANTED) {
            statusResource = R.string.share_rejected
            return
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                oneOffGpxStatus(OneOffGpxImport.importUri(context, uri))
            }
            statusResource = result
        }
    }
}

private fun Intent.resolveSingleContentUri(): Uri? {
    if (clipData?.itemCount?.let { it > 1 } == true) return null

    val candidates = buildList {
        sharedStreamExtra()?.let(::add)
        clipData?.getItemAt(0)?.uri?.let(::add)
    }.distinctBy(Uri::toString)

    return candidates.singleOrNull()?.takeIf { it.scheme == "content" }
}

@Suppress("DEPRECATION")
private fun Intent.sharedStreamExtra(): Uri? = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
} else {
    getParcelableExtra(Intent.EXTRA_STREAM)
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
