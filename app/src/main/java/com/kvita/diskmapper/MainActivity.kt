package com.kvita.diskmapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kvita.diskmapper.ui.DiskMapperScreen
import com.kvita.diskmapper.ui.theme.DiskMapperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DiskMapperTheme {
                DiskMapperScreen()
            }
        }
    }
}

