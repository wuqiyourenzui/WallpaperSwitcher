package com.wallpaperswitcher.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wallpaperswitcher.WallpaperSwitcherApp
import com.wallpaperswitcher.data.*
import com.wallpaperswitcher.service.WallpaperSwitchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WallpaperViewModel(app: Application) : AndroidViewModel(app) {