package com.ditto.whiteboard

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ditto.whiteboard.app.WhiteboardApplication
import com.ditto.whiteboard.ui.WhiteboardApp
import com.ditto.whiteboard.ui.WhiteboardViewModel
import com.ditto.whiteboard.ui.theme.WhiteboardTheme

class MainActivity : ComponentActivity() {
  private val container by lazy { (application as WhiteboardApplication).container }
  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { result ->
    container.resolvePermissions(container.requiredPermissions.all { result[it] == true })
  }
  private val viewModel: WhiteboardViewModel by viewModels {
    WhiteboardViewModel.Factory(container.profileRepository, container.boardSession)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
    if (container.requiredPermissions.isEmpty()) {
      container.resolvePermissions(true)
    } else {
      permissionLauncher.launch(container.requiredPermissions.toTypedArray())
    }
    setContent { WhiteboardTheme { WhiteboardApp(viewModel) } }
  }
}
