package nz.co.mixport.customsvision

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.co.mixport.customsvision.ui.AppViewModel
import nz.co.mixport.customsvision.ui.AppViewModelFactory
import nz.co.mixport.customsvision.ui.CustomsApp
import nz.co.mixport.customsvision.ui.theme.MixportCustomsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as CustomsApplication).repository

        setContent {
            MixportCustomsTheme {
                val viewModel: AppViewModel = viewModel(
                    factory = AppViewModelFactory(repository),
                )
                CustomsApp(viewModel = viewModel)
            }
        }
    }
}

