package com.drivetest.rf

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.drivetest.rf.service.DriveTestService
import com.drivetest.rf.ui.DriveTestApp
import com.drivetest.rf.ui.DriveTestTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val required = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )
    private val requested = if (Build.VERSION.SDK_INT >= 33) required + Manifest.permission.POST_NOTIFICATIONS else required

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (missing().isNotEmpty()) {
            Toast.makeText(this, "Location, phone and call permissions are required for the drive test", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (missing().isNotEmpty()) permissionLauncher.launch(requested)
        setContent {
            DriveTestTheme {
                DriveTestApp(
                    onStart = ::startSession,
                    onStop = { DriveTestService.stop(this) },
                    onShare = ::shareSession,
                )
            }
        }
    }

    private fun missing() = required.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun startSession() {
        if (missing().isNotEmpty()) {
            permissionLauncher.launch(requested)
            return
        }
        DriveTestService.start(this)
    }

    private fun shareSession(dirPath: String) {
        val files = File(dirPath).listFiles()?.filter { it.isFile && it.length() > 0 }.orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, "No session files yet", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("text/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share drive test ${File(dirPath).name}"))
    }
}
