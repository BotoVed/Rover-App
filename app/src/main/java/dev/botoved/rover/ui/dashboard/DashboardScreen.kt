package dev.botoved.rover.ui.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Room
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dev.botoved.rover.data.ServerPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private val Gold = Color(0xFFC9A84C)
private val GoldAlpha = Color(0x33C9A84C)
private val Red = Color(0xFFEF5350)
private val Green = Color(0xFF66BB6A)
private val ShimmerBase = Color(0xFF333333)
private val ShimmerHighlight = Color(0xFF444444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = koinViewModel(),
    prefs: ServerPreferences = koinInject()
) {
    val uiState by viewModel.uiState.collectAsState()
    val destHash by prefs.serverDestHash.collectAsState(initial = null)
    var selectedTab by remember { mutableIntStateOf(0) }
    var sheetDevice by remember { mutableStateOf<DeviceState?>(null) }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val json = intent?.getStringExtra("states") ?: return
                try {
                    val arr = JSONArray(json)
                    val list = (0 until arr.length()).map { i ->
                        val obj = arr.getJSONObject(i)
                        obj.keys().asSequence().associateWith { k -> obj.get(k) }
                    }
                    viewModel.onStatusReceived(list)
                } catch (_: Exception) {}
            }
        }
        val pushReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val json = intent?.getStringExtra("fields") ?: return
                try {
                    val obj = JSONObject(json)
                    val map = obj.keys().asSequence().associateWith { k -> obj.get(k) }
                    viewModel.onPushReceived(map)
                } catch (_: Exception) {}
            }
        }
        val pongReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val channel = intent?.getStringExtra("channel") ?: return
                viewModel.onPongReceived(channel)
            }
        }
        LocalBroadcastManager.getInstance(context).apply {
            registerReceiver(statusReceiver, IntentFilter("dev.botoved.rover.ACTION_STATUS"))
            registerReceiver(pushReceiver, IntentFilter("dev.botoved.rover.ACTION_PUSH"))
            registerReceiver(pongReceiver, IntentFilter("dev.botoved.rover.ACTION_PONG"))
        }
        onDispose {
            LocalBroadcastManager.getInstance(context).apply {
                unregisterReceiver(statusReceiver)
                unregisterReceiver(pushReceiver)
                unregisterReceiver(pongReceiver)
            }
        }
    }

    ModalBottomSheetHost(
        sheetDevice = sheetDevice,
        onDismissSheet = { sheetDevice = null },
        viewModel = viewModel
    ) {
        Scaffold(
            topBar = {
                DashboardTopBar(
                    serverName = uiState.serverName,
                    isOnline = uiState.isOnline,
                    channel = uiState.channel,
                    destHash = destHash
                )
            },
            bottomBar = {
                DashboardBottomNav(
                    selectedTab = selectedTab,
                    onTabChange = { selectedTab = it }
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when {
                    selectedTab == 0 && uiState.isLoading -> SkeletonContent()
                    selectedTab == 0 -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 16.dp, vertical = 12.dp
                            )
                        ) {
                            items(uiState.zones, key = { it.areaId ?: -1 }) { zone ->
                                ZoneSection(
                                    zone = zone,
                                    onToggle = { viewModel.onZoneExpandToggle(zone.areaId) },
                                    onDeviceClick = { sheetDevice = it },
                                    onDeviceToggle = { device, isOn -> viewModel.onToggle(device, isOn) },
                                    onSceneActivate = { viewModel.onAction(it, "activate") }
                                )
                            }
                        }
                    }
                    else -> {
                        SettingsTab(onReconnect = { viewModel.reconnect() }, onDebugSendReq = { viewModel.handleDebugSendReqArray() })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardTopBar(
    serverName: String,
    isOnline: Boolean,
    channel: String?,
    destHash: String?,
    viewModel: DashboardViewModel = koinViewModel()
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    serverName.ifEmpty { "Rover" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Rover · ${(destHash ?: "").take(8)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (isOnline) Green else Red,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (isOnline && channel != null) channel else "офлайн",
                    fontSize = 11.sp,
                    color = if (isOnline) Green else Red
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@Composable
private fun DashboardBottomNav(
    selectedTab: Int,
    onTabChange: (Int) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onTabChange(0) },
            icon = { Icon(Icons.Default.Home, contentDescription = "Дом") },
            label = { Text("дом") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Gold,
                selectedTextColor = Gold,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                indicatorColor = GoldAlpha
            )
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = { onTabChange(1) },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") },
            label = { Text("настройки") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Gold,
                selectedTextColor = Gold,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                indicatorColor = GoldAlpha
            )
        )
    }
}

@Composable
private fun SkeletonContent() {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000),
            repeatMode = RepeatMode.Reverse
        )
    )
    val shimmerColor = ShimmerBase.copy(alpha = alpha)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        items(2) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .background(shimmerColor, RoundedCornerShape(4.dp))
                )
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .background(shimmerColor, RoundedCornerShape(12.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoneSection(
    zone: ZoneUiState,
    onToggle: () -> Unit,
    onDeviceClick: (DeviceState) -> Unit,
    onDeviceToggle: (DeviceState, Boolean) -> Unit,
    onSceneActivate: (DeviceState) -> Unit = {}
) {
    val activeCount = zone.devices.count { it.isOn == true }
    val hasAlarm = zone.devices.any { it.type == "AL" && (it.isOn == true || it.primaryValue != null) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    zoneIcon(zone.areaName),
                    contentDescription = null,
                    tint = Gold,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                zone.areaName,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (activeCount > 0) {
                Text(
                    "$activeCount вкл",
                    fontSize = 12.sp,
                    color = Gold,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            if (hasAlarm) {
                Text(
                    "⚠",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer {
                    rotationZ = if (zone.isExpanded) 90f else 0f
                }
            )
        }

        AnimatedVisibility(
            visible = zone.isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val grouped = zone.devices.groupBy { deviceTypeGroup(it.type) }
                grouped.forEach { (groupLabel, devices) ->
                    Text(
                        groupLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp)
                    )
                    devices.forEach { device ->
                        DeviceCard(
                            device = device,
                            onClick = {
                                if (device.type == "SC") onSceneActivate(device)
                                else onDeviceClick(device)
                            },
                            onToggle = { isOn -> onDeviceToggle(device, isOn) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceState,
    onClick: () -> Unit,
    onToggle: ((Boolean) -> Unit)? = null
) {
    val isActive = device.isOn == true
    val borderColor = if (isActive) Gold else MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (device.isPending) 0.6f else 1f)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    deviceIcon(device.type),
                    contentDescription = null,
                    tint = if (isActive) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (device.primaryValue != null) {
                        Text(
                            device.primaryValue,
                            fontSize = 12.sp,
                            color = if (isActive) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                DeviceRightControl(device = device, onToggle = onToggle)
            }
            if (device.isPending) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(8.dp)
                        .background(Gold, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun DeviceRightControl(
    device: DeviceState,
    onToggle: ((Boolean) -> Unit)? = null
) {
    when (device.type) {
        "SW", "LT", "FN", "CL" -> {
            var checked by remember(device.isOn) { mutableStateOf(device.isOn == true) }
            Switch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    onToggle?.invoke(it)
                },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = Gold,
                    checkedTrackColor = GoldAlpha,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
        "LK" -> {
            val label = if (device.isOn == true) "открыт" else if (device.isOn == false) "закрыт" else ""
            if (label.isNotEmpty()) {
                Text(
                    label,
                    fontSize = 12.sp,
                    color = if (device.isOn == true) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        "SC" -> {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Активировать",
                tint = Gold,
                modifier = Modifier.size(32.dp)
            )
        }
        "SE", "CV" -> {
            if (device.primaryValue != null) {
                Text(
                    device.primaryValue,
                    fontSize = 13.sp,
                    color = Gold,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        "MS" -> {
            if (device.primaryValue != null) {
                Text(
                    device.primaryValue,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        "AL" -> {
            val label = when (device.isOn) {
                true -> "тревога"
                false -> "охрана"
                else -> null
            }
            if (label != null) {
                Text(
                    label,
                    fontSize = 12.sp,
                    color = if (device.isOn == true) Red else Green,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModalBottomSheetHost(
    sheetDevice: DeviceState?,
    onDismissSheet: () -> Unit,
    viewModel: DashboardViewModel,
    content: @Composable () -> Unit
) {
    if (sheetDevice != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismissSheet,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            DeviceDetailSheetContent(device = sheetDevice, onClose = onDismissSheet, viewModel = viewModel)
        }
    }
    content()
}

@Composable
private fun DeviceDetailSheetContent(
    device: DeviceState,
    onClose: () -> Unit,
    viewModel: DashboardViewModel
) {
    val isActive = device.isOn == true
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                deviceIcon(device.type),
                contentDescription = null,
                tint = if (isActive) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    device.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    deviceTypeLabel(device.type),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when (device.type) {
            "SW" -> SwitchControl(device, viewModel)
            "LT" -> LightControl(device, viewModel)
            "CV" -> CoverControl(device, viewModel)
            "CL" -> ClimateControl(device, viewModel)
            "LK" -> LockControl(device, viewModel)
            "MS" -> MediaControl(device, viewModel)
            "AL" -> AlarmControl(device, viewModel)
            "SE" -> SensorControl(device)
            "FN" -> FanControl(device, viewModel)
            "SC" -> SceneControl(device, viewModel)
            "BT" -> ButtonControl(device, viewModel)
        }

        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Gold,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Закрыть")
        }
    }
}

@Composable
private fun SwitchControl(device: DeviceState, viewModel: DashboardViewModel) {
    var checked by remember { mutableStateOf(device.isOn ?: false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Включить", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp)
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                viewModel.onToggle(device, it)
            },
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Gold,
                checkedTrackColor = GoldAlpha
            )
        )
    }
}

@Composable
private fun LightControl(device: DeviceState, viewModel: DashboardViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SwitchControl(device, viewModel)
        SliderRow("Яркость", 75, 0, 100, "%",
            onValueChangeFinished = { viewModel.onSlider(device, 3, it) })
        SliderRow("Цвет. температура", 4200, 2700, 6500, "K",
            onValueChangeFinished = { viewModel.onSlider(device, 4, it) })
    }
}

@Composable
private fun CoverControl(device: DeviceState, viewModel: DashboardViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.onAction(device, "open") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Gold),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Открыть") }
            FilledTonalButton(
                onClick = { viewModel.onAction(device, "stop") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Стоп") }
            Button(
                onClick = { viewModel.onAction(device, "close") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Закрыть", color = Color.White) }
        }
        SliderRow("Позиция", 50, 0, 100, "%",
            onValueChangeFinished = { viewModel.onSlider(device, 5, it) })
    }
}

@Composable
private fun ClimateControl(device: DeviceState, viewModel: DashboardViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SwitchControl(device, viewModel)
        SliderRow("Температура", 22, 16, 30, "°C",
            onValueChangeFinished = { viewModel.onSlider(device, 6, it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("cool", "heat", "fan_only", "auto", "dry").forEach { mode ->
                Chip(mode, mode == "cool", onClick = { viewModel.onHvacMode(device, mode) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("low", "mid", "high", "auto").forEach { speed ->
                Chip(speed, speed == "auto", onClick = { viewModel.onFanMode(device, speed) })
            }
        }
    }
}

@Composable
private fun LockControl(device: DeviceState, viewModel: DashboardViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.onAction(device, "unlock") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Gold),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Открыть") }
            Button(
                onClick = { viewModel.onAction(device, "lock") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Закрыть", color = Color.White) }
        }
        val stateLabel = when (device.isOn) {
            true -> "Открыто"
            false -> "Закрыто"
            else -> "Неизвестно"
        }
        Row {
            Text("Состояние: ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stateLabel, color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun MediaControl(device: DeviceState, viewModel: DashboardViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = { viewModel.onAction(device, "prev") }) {
                Icon(Icons.Default.ChevronRight,
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.graphicsLayer { rotationZ = 180f }
                )
            }
            IconButton(onClick = { viewModel.onAction(device, "play") }) {
                Icon(Icons.Default.PlayArrow,
                    contentDescription = "Воспроизвести",
                    tint = Gold,
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = { viewModel.onAction(device, "next") }) {
                Icon(Icons.Default.ChevronRight,
                    contentDescription = "Далее",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        SliderRow("Громкость", 50, 0, 100, "%",
            onValueChangeFinished = { viewModel.onSlider(device, 8, it) })
    }
}

@Composable
private fun AlarmControl(device: DeviceState, viewModel: DashboardViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("arm_away" to "Полная", "arm_home" to "Дома", "arm_night" to "Ночь", "disarm" to "Снята")
            .forEach { (value, label) ->
                FilledTonalButton(
                    onClick = { viewModel.onAction(device, value) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text(label) }
            }
    }
}

@Composable
private fun SensorControl(device: DeviceState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val parts = (device.primaryValue ?: "---").split(" ")
        val value = parts.getOrElse(0) { "---" }
        val unit = parts.getOrElse(1) { "" }
        Text(
            value,
            fontSize = 48.sp,
            fontWeight = FontWeight.Light,
            color = Gold
        )
        if (unit.isNotEmpty()) {
            Text(
                unit,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FanControl(device: DeviceState, viewModel: DashboardViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SwitchControl(device, viewModel)
        SliderRow("Скорость", 60, 1, 100, "%",
            onValueChangeFinished = { viewModel.onSlider(device, 7, it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("normal", "sleep", "natural", "auto").forEach { mode ->
                Chip(mode, mode == "normal", onClick = { viewModel.onFanMode(device, mode) })
            }
        }
    }
}

@Composable
private fun SceneControl(device: DeviceState, viewModel: DashboardViewModel) {
    Button(
        onClick = { viewModel.onAction(device, "activate") },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Gold),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("Активировать", color = MaterialTheme.colorScheme.onPrimary, fontSize = 16.sp)
    }
}

@Composable
private fun ButtonControl(device: DeviceState, viewModel: DashboardViewModel) {
    Button(
        onClick = { viewModel.onAction(device, "press") },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Gold),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("Нажать", color = MaterialTheme.colorScheme.onPrimary, fontSize = 16.sp)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    rangeStart: Int,
    rangeEnd: Int,
    unit: String,
    onValueChangeFinished: ((Float) -> Unit)? = null
) {
    var sliderValue by remember { mutableStateOf(value.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
            Text("${sliderValue.toInt()}$unit", fontSize = 14.sp, color = Gold)
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChangeFinished?.invoke(sliderValue) },
            valueRange = rangeStart.toFloat()..rangeEnd.toFloat(),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Gold,
                activeTrackColor = Gold,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit = {}) {
    val bgColor = if (selected) Gold else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = textColor,
        modifier = Modifier
            .clickable { onClick() }
            .background(bgColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

private fun zoneIcon(name: String): ImageVector {
    val lower = name.lowercase()
    return when {
        "кухн" in lower -> Icons.Default.Kitchen
        "спальн" in lower -> Icons.Default.Bed
        "гостин" in lower || "зал" in lower -> Icons.Default.Home
        "ванн" in lower || "санузел" in lower -> Icons.Default.Bathtub
        "улиц" in lower || "двор" in lower -> Icons.Default.Park
        "техн" in lower || "котельн" in lower -> Icons.Default.Build
        "детск" in lower -> Icons.Default.ChildCare
        else -> Icons.Default.Room
    }
}

private fun deviceIcon(type: String): ImageVector = when (type) {
    "SW" -> Icons.Default.Power
    "LT" -> Icons.Default.Lightbulb
    "CV" -> Icons.Default.Blinds
    "CL" -> Icons.Default.AcUnit
    "LK" -> Icons.Default.Lock
    "MS" -> Icons.Default.PlayArrow
    "SC" -> Icons.Default.Movie
    "AL" -> Icons.Default.Security
    "SE" -> Icons.Default.Sensors
    "FN" -> Icons.Default.Air
    "BT" -> Icons.Default.TouchApp
    else -> Icons.Default.Power
}

private fun deviceTypeGroup(type: String): String = when (type) {
    "SW" -> "Выключатели"
    "LT" -> "Свет"
    "CV" -> "Шторы"
    "CL" -> "Климат"
    "LK" -> "Замки"
    "MS" -> "Медиа"
    "SC" -> "Сценарии"
    "AL" -> "Охрана"
    "SE" -> "Датчики"
    "FN" -> "Вентиляция"
    "BT" -> "Кнопки"
    else -> "Прочее"
}

private fun deviceTypeLabel(type: String): String = when (type) {
    "SW" -> "Выключатель"
    "LT" -> "Светильник"
    "CV" -> "Штора"
    "CL" -> "Климат"
    "LK" -> "Замок"
    "MS" -> "Медиа"
    "SC" -> "Сценарий"
    "AL" -> "Охрана"
    "SE" -> "Датчик"
    "FN" -> "Вентилятор"
    "BT" -> "Кнопка"
    else -> "Устройство"
}

@Composable
private fun SettingsTab(onReconnect: () -> Unit, onDebugSendReq: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Настройки",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp
        )
        Button(
            onClick = onReconnect,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Переподключиться")
        }
        Button(
            onClick = onDebugSendReq,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            )
        ) {
            Text("Send REQ (m,a,d)")
        }
    }
}
