package com.example.lightDisable

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lightDisable.ui.theme.ГрафікВідключеньTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ThemeMode(val label: String) {
	LIGHT("Світла"),
	DARK("Темна"),
	SYSTEM("Тема телефону"),
}

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		Dtek.clear(this) // очищення при вході
		NotificationHelper.createNotificationChannel(this)
		ScheduleManager.scheduleNextNormalUpdate(this)
		enableEdgeToEdge()
		val settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
		setContent {
			var mode by remember {
				mutableStateOf(
					runCatching { ThemeMode.valueOf(settings.getString("theme", "") ?: "") }
						.getOrDefault(ThemeMode.SYSTEM)
				)
			}
			val dark = when (mode) {
				ThemeMode.LIGHT -> false
				ThemeMode.DARK -> true
				ThemeMode.SYSTEM -> isSystemInDarkTheme()
			}

			// Іконки системних панелей мають відповідати обраній темі
			DisposableEffect(dark) {
				val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
				else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
				enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
				onDispose {}
			}

			ГрафікВідключеньTheme(darkTheme = dark) {
				// Змінюємо лише тло (та колір тексту на ньому): чорне / біле
				val bg = if (dark) Color.Black else Color.White
				val on = if (dark) Color.White else Color.Black
				MaterialTheme(
					colorScheme = MaterialTheme.colorScheme.copy(
						background = bg, surface = bg, onBackground = on, onSurface = on,
					),
					typography = MaterialTheme.typography,
				) {
					App(
						themeMode = mode,
						onThemeMode = {
							mode = it
							settings.edit().putString("theme", it.name).apply()
						},
					)
				}
			}
		}
	}

	override fun onDestroy() {
		Dtek.clear(this) // очищення при виході
		super.onDestroy()
	}
}

private sealed interface Screen {
	data object Home : Screen
	data object Add : Screen
	data class Detail(val address: Address) : Screen
}

@Composable
fun App(themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
	val context = LocalContext.current
	val store = remember { AddressStore(context) }
	var addresses by remember { mutableStateOf(store.load()) }
	var screen by remember { mutableStateOf<Screen>(Screen.Home) }

	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
		val launcher = rememberLauncherForActivityResult(
			contract = ActivityResultContracts.RequestPermission(),
			onResult = {}
		)
		LaunchedEffect(Unit) {
			launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
		}
	}

	BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

	Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
		Box(Modifier.padding(padding)) {
			when (val s = screen) {
				Screen.Home -> HomeScreen(
					addresses = addresses,
					themeMode = themeMode,
					onThemeMode = onThemeMode,
					onAdd = { screen = Screen.Add },
					onOpen = { screen = Screen.Detail(it) },
					onDelete = {
						addresses = addresses - it
						store.save(addresses)
						ScheduleStore(context).remove(it)
					},
				)

				Screen.Add -> AddScreen(
					onCancel = { screen = Screen.Home },
					onSave = {
						addresses = addresses + it
						store.save(addresses)
						screen = Screen.Home
					},
				)

				is Screen.Detail -> DetailScreen(s.address, onBack = { screen = Screen.Home })
			}
		}
	}
}

@Composable
private fun HomeScreen(
	addresses: List<Address>,
	themeMode: ThemeMode,
	onThemeMode: (ThemeMode) -> Unit,
	onAdd: () -> Unit,
	onOpen: (Address) -> Unit,
	onDelete: (Address) -> Unit,
) {
	Column(Modifier.fillMaxSize().padding(16.dp)) {
		Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Додати адресу") }
		Spacer(Modifier.height(12.dp))
		if (addresses.isEmpty()) Text("Адрес ще немає")
		LazyColumn(Modifier.weight(1f)) {
			items(addresses) { a ->
				Card(
					Modifier
						.fillMaxWidth()
						.padding(vertical = 4.dp)
						.clickable { onOpen(a) }
				) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(a.title, Modifier.weight(1f).padding(16.dp))
						TextButton(onClick = { onDelete(a) }) { Text("Видалити") }
					}
				}
			}
		}
		Spacer(Modifier.height(8.dp))
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			ThemeMode.entries.forEach {
				FilterChip(
					selected = it == themeMode,
					onClick = { onThemeMode(it) },
					label = { Text(it.label) },
				)
			}
		}
	}
}

@Composable
private fun Suggestions(items: List<String>, onPick: (String) -> Unit) {
	if (items.isEmpty()) return
	Card(Modifier.fillMaxWidth()) {
		Column {
			items.forEach {
				Text(
					it,
					Modifier.fillMaxWidth().clickable { onPick(it) }.padding(12.dp),
				)
			}
		}
	}
}

@Composable
private fun AddScreen(onCancel: () -> Unit, onSave: (Address) -> Unit) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	var city by remember { mutableStateOf("") }
	var street by remember { mutableStateOf("") }
	var house by remember { mutableStateOf("") }
	var cityOk by remember { mutableStateOf(false) }
	var streetOk by remember { mutableStateOf(false) }
	var houseOk by remember { mutableStateOf(false) }

	var ready by remember { mutableStateOf(Dtek.isLoaded) }
	var error by remember { mutableStateOf<String?>(null) }
	var houses by remember { mutableStateOf<List<String>>(emptyList()) }
	var housesFor by remember { mutableStateOf<String?>(null) }
	var housesLoading by remember { mutableStateOf(false) }

	// GET сторінки паралельно з відкриттям екрана
	LaunchedEffect(Unit) {
		try {
			Dtek.load(context, force = true)
			ready = true
		} catch (e: Exception) {
			error = "Не вдалося завантажити дані: ${e.message}"
		}
	}

	fun loadHouses() {
		val key = "$city|$street"
		if (housesFor == key) return
		housesFor = key
		housesLoading = true
		scope.launch {
			try {
				houses = Dtek.houses(context, city, street)
				error = null
			} catch (e: Exception) {
				housesFor = null
				error = "Не вдалося отримати номери будинків: ${e.message}"
			}
			housesLoading = false
		}
	}

	Column(
		Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		if (!ready && error == null) CircularProgressIndicator()
		error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

		OutlinedTextField(
			value = city,
			onValueChange = {
				city = it; cityOk = false
				street = ""; streetOk = false
				house = ""; houseOk = false
				houses = emptyList(); housesFor = null
			},
			label = { Text("Місто") },
			enabled = ready,
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		if (ready && !cityOk && city.isNotBlank()) {
			Suggestions(Dtek.streets.keys.filter { it.contains(city, true) }.take(15)) {
				city = it; cityOk = true
			}
		}

		OutlinedTextField(
			value = street,
			onValueChange = {
				street = it; streetOk = false
				house = ""; houseOk = false
				houses = emptyList(); housesFor = null
			},
			label = { Text("Вулиця") },
			enabled = cityOk,
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		if (cityOk && !streetOk && street.isNotBlank()) {
			Suggestions(Dtek.streets[city].orEmpty().filter { it.contains(street, true) }.take(15)) {
				street = it; streetOk = true
			}
		}

		OutlinedTextField(
			value = house,
			onValueChange = { house = it; houseOk = false },
			label = { Text("Номер будинку") },
			enabled = streetOk,
			singleLine = true,
			modifier = Modifier
				.fillMaxWidth()
				.onFocusChanged { if (it.isFocused && cityOk && streetOk) loadHouses() },
		)
		if (housesLoading) CircularProgressIndicator()
		if (streetOk && !houseOk && house.isNotBlank()) {
			Suggestions(houses.filter { it.startsWith(house, true) }.take(15)) {
				house = it; houseOk = true
			}
		}

		Button(
			onClick = { onSave(Address(city, street, house)) },
			enabled = houseOk,
			modifier = Modifier.fillMaxWidth(),
		) { Text("Зберегти") }
		TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Скасувати") }
	}
}

@Composable
private fun DetailScreen(address: Address, onBack: () -> Unit) {
	val context = LocalContext.current
	var schedule by remember { mutableStateOf<Schedule?>(null) }
	var error by remember { mutableStateOf<String?>(null) }
	// Час збереження, якщо показані дані з пам'яті, і причина невдачі оновлення
	var cachedAt by remember { mutableStateOf<Long?>(null) }
	var refreshError by remember { mutableStateOf<String?>(null) }
	var showTomorrow by remember { mutableStateOf(false) }

	LaunchedEffect(address) {
		val store = ScheduleStore(context)
		try {
			val fresh = Dtek.schedule(context, address)
			store.save(address, fresh)
			schedule = fresh
			ScheduleManager.recordSuccessfulFetch(context)
			NotificationHelper.rescheduleAllNotifications(context)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			val cached = store.load(address)
			if (cached != null) {
				schedule = cached.first
				cachedAt = cached.second
				refreshError = e.message
			} else {
				error = "Не вдалося отримати графік: ${e.message}"
			}
		}
	}

	Column(
		Modifier.fillMaxSize().padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text(address.title, fontSize = 18.sp)
		Column(
			Modifier.weight(1f).verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			val s = schedule
			when {
				error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
				s == null -> CircularProgressIndicator()
				else -> {
					cachedAt?.let { StaleBanner(it, refreshError) }
					val tomorrow = s.tomorrow
					val viewingTomorrow = showTomorrow && tomorrow != null
					val day = if (viewingTomorrow) tomorrow!! else s.today
					fun dm(d: Day) = d.dateTs?.let { Kyiv.format("dd.MM.yy", it * 1000) }.orEmpty()

					day.dateTs?.let {
						// дата + назва дня тижня з preset.days
						val date = dm(day) + s.preset.dayName(it * 1000).let { n -> if (n.isEmpty()) "" else ", $n" }
						// Збережені дані можуть бути за іншу дату — тоді не пишемо «на сьогодні»
						val isToday = !viewingTomorrow && Kyiv.format("dd.MM.yy", it * 1000) == Kyiv.format("dd.MM.yy", System.currentTimeMillis())
						Text(
							when {
								viewingTomorrow -> "На завтра $date"
								isToday -> "На сьогодні $date"
								else -> "Графік на $date"
							},
							fontSize = 16.sp,
						)
					}
					if (tomorrow != null) {
						Button(onClick = { showTomorrow = !viewingTomorrow }, modifier = Modifier.fillMaxWidth()) {
							Text(
								if (viewingTomorrow) "Графік на сьогодні (${dm(s.today)})"
								else "Графік на завтра (${dm(tomorrow)})"
							)
						}
					}
					// Жовта плашка — лише для поточного дня
					if (!viewingTomorrow) {
						if (s.subType.isNotEmpty() || s.startDate.isNotEmpty() || s.endDate.isNotEmpty()) {
							OutageInfo(s)
						} else if (s.updatedAt.isNotBlank()) {
							Text("Оновлено: ${s.updatedAt}", fontSize = 13.sp)
						}
					}
					OutageList(s.schName, day.hours, s.preset)
					// Дві таблиці по 30% ширини екрану; три однакові проміжки (край — таблиця — таблиця — край)
					// по (100% - 60%) / 3. requiredWidth розтягує ряд на весь екран, поміч із відступу 16dp.
					val screenW = LocalConfiguration.current.screenWidthDp.dp
					val tableW = screenW * 0.3f
					val gap = screenW * 0.4f / 3
					Row(
						Modifier.requiredWidth(screenW).padding(horizontal = gap),
						horizontalArrangement = Arrangement.spacedBy(gap),
					) {
						HoursTable(day.hours, s.preset, from = 0, modifier = Modifier.width(tableW))
						HoursTable(day.hours, s.preset, from = 12, modifier = Modifier.width(tableW))
					}
					Legend(day.hours.toSet(), s.preset)
				}
			}
		}
		Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Повернутись на головну") }
	}
}

/** Червона плашка: показані збережені дані, оновити не вдалося */
@Composable
private fun StaleBanner(savedAt: Long, reason: String?) {
	val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)
		.apply { timeZone = TimeZone.getTimeZone("Europe/Kyiv") }
	val textColor = Color(0xFF8C1D18)
	Column(
		Modifier
			.fillMaxWidth()
			.background(Color(0xFFFFDAD6), RoundedCornerShape(8.dp))
			.border(1.dp, Color(0xFFE5A09B), RoundedCornerShape(8.dp))
			.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text("Не вдалося отримати актуальні дані. Відображено збережені дані від ${fmt.format(Date(savedAt))}", color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
		if (!reason.isNullOrBlank()) Text("Причина: $reason", color = textColor, fontSize = 12.sp)
	}
}

/** Жовта плашка з поясненням про поточне відключення */
@Composable
private fun OutageInfo(s: Schedule) {
	val bold = SpanStyle(fontWeight = FontWeight.Bold)
	fun line(label: String, value: String, prefix: String = "") = buildAnnotatedString {
		append(label)
		if (prefix.isNotEmpty()) append(prefix)
		withStyle(bold) { append(value) }
	}

	val update = s.updateTimestamp.ifEmpty { s.updatedAt }
	// Текст завжди темний, бо плашка жовта в будь-якій темі
	val textColor = Color(0xFF4A4A4A)
	Column(
		Modifier
			.fillMaxWidth()
			.background(Color(0xFFFFF7B3), RoundedCornerShape(8.dp))
			.border(1.dp, Color(0xFFD9D4A0), RoundedCornerShape(8.dp))
			.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text("За вашою адресою в даний момент відсутня електроенергія", color = textColor, fontSize = 14.sp)
		if (s.subType.isNotEmpty()) Text(line("Причина: ", s.subType), color = textColor, fontSize = 14.sp)
		if (s.startDate.isNotEmpty()) Text(line("Час початку – ", s.startDate), color = textColor, fontSize = 14.sp)
		if (s.endDate.isNotEmpty()) {
			Text(
				line("Орієнтовний час відновлення електроенергії – ", "до ${s.endDate}"),
				color = textColor, fontSize = 14.sp,
			)
		}
		if (update.isNotEmpty()) {
			Spacer(Modifier.height(8.dp))
			Text("Дата оновлення інформації – $update", color = textColor, fontSize = 14.sp)
		}
	}
}

/** Відрізки часу «світла не буде» за 24 годинами дня; «maybe» у список не входить */
private fun outageIntervals(hours: List<String>, preset: Preset): List<String> {
	val off = BooleanArray(48)
	hours.take(24).forEachIndexed { h, v ->
		when (v) {
			"no" -> { off[2 * h] = true; off[2 * h + 1] = true }
			"first" -> off[2 * h] = true
			"second" -> off[2 * h + 1] = true
		}
	}
	fun t(slot: Int) = preset.slotTime(slot)
	val result = mutableListOf<String>()
	var i = 0
	while (i < 48) {
		if (off[i]) {
			val start = i
			while (i < 48 && off[i]) i++
			result += "${t(start)} - ${t(i)}"
		} else i++
	}
	return result
}

/** Заголовок черги і список відрізків, коли світла не буде */
@Composable
private fun OutageList(title: String, hours: List<String>, preset: Preset) {
	val intervals = outageIntervals(hours, preset)
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
		if (intervals.isEmpty()) {
			Text("Повністю без відключень", fontSize = 15.sp)
		} else {
			Text("⚡️ Світла не буде:", fontSize = 15.sp)
			intervals.forEach { Text("☑️$it", fontSize = 15.sp) }
		}
	}
}

private val OffColor = Color(0xFFD3D4D5)
private val IconColor = Color(0xFF11203E)

/** Таблиця 2 стовпці × 13 рядків: заголовок + 12 годин, починаючи з [from] */
@Composable
private fun HoursTable(hours: List<String>, preset: Preset, from: Int, modifier: Modifier = Modifier) {
	val shape = RoundedCornerShape(8.dp)
	Column(modifier.clip(shape).border(1.dp, Color.Gray, shape)) {
		TableRow("Час", null)
		for (h in from until from + 12) {
			// ключ "1".."24" у відповіді = година (h-1)..h, тобто індекс h у списку
			TableRow(preset.hourLabel(h), hours.getOrElse(h) { "yes" })
		}
	}
}

@Composable
private fun TableRow(label: String, value: String?) {
	Row(Modifier.fillMaxWidth().height(32.dp)) {
		Box(
			Modifier.width(48.dp).fillMaxHeight().border(0.5.dp, Color.Gray),
			contentAlignment = Alignment.Center,
		) { Text(label, fontSize = 12.sp) }
		Box(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.Gray)) {
			if (value != null) LightCell(value, Modifier.fillMaxSize())
		}
	}
}

/**
 * yes — світло є: порожня клітинка (колір тла теми);
 * no — світла нема: блискавка + сіре тло;
 * first/second — блискавка, сіра лише перша/друга половина;
 * maybe — як no, але з прозорістю 50%.
 */
@Composable
private fun LightCell(value: String, modifier: Modifier = Modifier) {
	val painter = painterResource(R.drawable.ic_power_off)
	val baseIconColor = MaterialTheme.colorScheme.onBackground
	Canvas(modifier) {
		if (value !in setOf("no", "first", "second", "maybe", "mfirst", "msecond")) return@Canvas
		val w = size.width
		val h = size.height
		// maybe / mfirst / msecond — так само, як no / first / second, але з прозорістю 50%
		val alpha = if (value == "maybe" || value == "mfirst" || value == "msecond") 0.5f else 1f
		val greyLeft = if (value == "second" || value == "msecond") w / 2 else 0f
		val greyRight = if (value == "first" || value == "mfirst") w / 2 else w

		drawRect(OffColor, Offset(greyLeft, 0f), Size(greyRight - greyLeft, h), alpha)

		val s = minOf(w, h) * 0.85f
		fun icon(color: Color, left: Float, right: Float) {
			clipRect(left, 0f, right, h) {
				translate((w - s) / 2, (h - s) / 2) {
					with(painter) { draw(Size(s, s), alpha, ColorFilter.tint(color)) }
				}
			}
		}
		// Блискавка темна на сірому тлі і кольору тексту на тлі теми
		icon(IconColor, greyLeft, greyRight)
		if (greyLeft > 0f) icon(baseIconColor, 0f, greyLeft)
		if (greyRight < w) icon(baseIconColor, greyRight, w)
	}
}

/** Легенда лише для значень, які фактично є в таблиці */
@Composable
private fun Legend(used: Set<String>, preset: Preset) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		// Підписи беруться з preset.time_type у відповіді сайту
		preset.legendKeys().filter { it in used }.map { it to preset.typeText(it) }.forEach { (key, text) ->
			Row(verticalAlignment = Alignment.CenterVertically) {
				Box(Modifier.size(32.dp, 24.dp).border(0.5.dp, Color.Gray)) {
					LightCell(key, Modifier.fillMaxSize())
				}
				Spacer(Modifier.width(8.dp))
				Text(text, fontSize = 14.sp)
			}
		}
	}
}
