package com.example.lightDisable

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
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

	LaunchedEffect(address) {
		try {
			schedule = Dtek.schedule(context, address)
		} catch (e: Exception) {
			error = "Не вдалося отримати графік: ${e.message}"
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
					s.dateTs?.let {
						val fmt = SimpleDateFormat("dd.MM.yy", Locale.US)
							.apply { timeZone = TimeZone.getTimeZone("Europe/Kyiv") }
						Text("На сьогодні ${fmt.format(Date(it * 1000))}", fontSize = 16.sp)
					}
					if (s.updatedAt.isNotBlank()) Text("Оновлено: ${s.updatedAt}", fontSize = 13.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						HoursTable(s.hours, from = 0, modifier = Modifier.weight(1f))
						HoursTable(s.hours, from = 12, modifier = Modifier.weight(1f))
					}
					Legend()
				}
			}
		}
		Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Повернутись на головну") }
	}
}

private val OffColor = Color(0xFFD3D4D5)
private val IconColor = Color(0xFF11203E)

/** Таблиця 2 стовпці × 13 рядків: заголовок + 12 годин, починаючи з [from] */
@Composable
private fun HoursTable(hours: List<String>, from: Int, modifier: Modifier = Modifier) {
	Column(modifier.border(1.dp, Color.Gray)) {
		TableRow("Час", null)
		for (h in from until from + 12) {
			// ключ "1".."24" у відповіді = година (h-1)..h, тобто індекс h у списку
			TableRow("%02d-%02d".format(h, h + 1), hours.getOrElse(h) { "yes" })
		}
	}
}

@Composable
private fun TableRow(label: String, value: String?) {
	Row(Modifier.fillMaxWidth().height(32.dp)) {
		Box(
			Modifier.width(56.dp).fillMaxHeight().border(0.5.dp, Color.Gray),
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
		if (value != "no" && value != "first" && value != "second" && value != "maybe") return@Canvas
		val w = size.width
		val h = size.height
		val alpha = if (value == "maybe") 0.5f else 1f
		val greyLeft = if (value == "second") w / 2 else 0f
		val greyRight = if (value == "first") w / 2 else w

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

@Composable
private fun Legend() {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		listOf(
			"yes" to "Світло є",
			"no" to "Світла немає",
			"first" to "Світла немає перші 30 хв.",
			"second" to "Світла немає другі 30 хв.",
			"maybe" to "Можливе відключення",
		).forEach { (key, text) ->
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
