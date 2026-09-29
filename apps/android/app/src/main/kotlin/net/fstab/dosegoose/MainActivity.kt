package net.fstab.dosegoose

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import net.fstab.dosegoose.app.DoseGooseApp
import net.fstab.dosegoose.app.DoseGooseViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: DoseGooseViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.alertPermissionChanged() }
    private val activityPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.activityPermissionChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DoseGooseApp(
                viewModel = viewModel,
                onRequestNotificationPermission = {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            },
                        )
                    }
                },
                onRequestExactAlarmAccess = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                "package:$packageName".toUri(),
                            ),
                        )
                    }
                },
                onRequestActivityPermission = {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACTIVITY_RECOGNITION,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    } else {
                        viewModel.activityPermissionChanged()
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.appEnteredForeground()
    }

    override fun onPause() {
        viewModel.appLeftForeground()
        super.onPause()
    }
}
