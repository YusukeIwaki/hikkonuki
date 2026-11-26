package app.hikkonuki.android

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.hikkonuki.android.platform.AppGraph
import app.hikkonuki.android.theme.HikkonukiTheme
import app.hikkonuki.android.ui.AppViewModel
import app.hikkonuki.android.ui.BrowserScreen
import app.hikkonuki.android.ui.DetailScreen
import app.hikkonuki.android.ui.ExportsScreen

object Routes {
    const val BROWSER = "browser"
    const val DETAIL = "detail/{index}"
    const val EXPORTS = "exports"
    fun detail(index: Int) = "detail/$index"
}

class MainActivity : ComponentActivity() {
    /** Bumped whenever a notification asks to land on the exports screen. */
    private var openExportsSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark status bar icons over the light app bar (the platform draws edge to edge anyway).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        handleIntent(intent)
        val graph = AppGraph.get(this)
        setContent {
            HikkonukiTheme {
                HikkonukiApp(
                    viewModel = viewModel(factory = AppViewModel.factory(graph.library, graph.exports)),
                    openExportsSignal = openExportsSignal,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_EXPORTS, false) == true) {
            // Consume the extra so a later recreation does not navigate again.
            intent.removeExtra(EXTRA_OPEN_EXPORTS)
            openExportsSignal++
        }
    }

    companion object {
        const val EXTRA_OPEN_EXPORTS = "open_exports"
    }
}

@Composable
fun HikkonukiApp(viewModel: AppViewModel, openExportsSignal: Int = 0) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val jobs by viewModel.exportJobs.collectAsStateWithLifecycle()
    val batches by viewModel.exportBatches.collectAsStateWithLifecycle()

    LaunchedEffect(openExportsSignal) {
        if (openExportsSignal > 0) navController.navigate(Routes.EXPORTS) { launchSingleTop = true }
    }

    // Two filters on purpose: a filter with a data scheme never matches USB_DEVICE_*
    // broadcasts, which carry no data URI.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) = viewModel.onUsbChanged()
        }
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val mediaFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, usbFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(context, receiver, mediaFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // Some cameras mount without any MEDIA_MOUNTED broadcast; the documents provider
    // notifies observers of a granted folder when its volume appears.
    DisposableEffect(context, state.watchedFolders) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = viewModel.onUsbChanged()
        }
        state.watchedFolders.forEach { folder ->
            runCatching { resolver.registerContentObserver(Uri.parse(folder), true, observer) }
        }
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshOnResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) viewModel.onFolderPicked(uri)
    }

    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                state = state,
                onRefresh = viewModel::onUsbChanged,
                onToggleLayout = viewModel::toggleLayout,
                onItemClick = { item ->
                    val index = state.items.indexOfFirst { it.id == item.id }
                    if (index >= 0) navController.navigate(Routes.detail(index))
                },
                onItemLongClick = viewModel::startSelection,
                onToggleSelection = viewModel::toggleSelection,
                onClearSelection = viewModel::clearSelection,
                onSelectAll = viewModel::selectAll,
                onExportSelected = {
                    viewModel.exportSelected()
                    navController.navigate(Routes.EXPORTS)
                },
                onOpenExports = { navController.navigate(Routes.EXPORTS) },
                onPickFolder = { folderPicker.launch(viewModel.folderPickerIntent()) },
            )
        }
        composable(Routes.DETAIL, arguments = listOf(navArgument("index") { type = NavType.IntType })) { entry ->
            DetailScreen(
                items = state.items,
                initialIndex = entry.arguments?.getInt("index") ?: 0,
                exists = viewModel::exists,
                onShown = viewModel::ensureDetails,
                onBack = { navController.popBackStack() },
                onExport = { item ->
                    viewModel.exportItem(item)
                    navController.navigate(Routes.EXPORTS)
                },
            )
        }
        composable(Routes.EXPORTS) {
            ExportsScreen(
                jobs = jobs,
                batch = batches.firstOrNull(),
                onBack = { navController.popBackStack() },
                onCancel = viewModel::cancelExport,
                onClearHistory = viewModel::clearExportHistory,
            )
        }
    }
}
