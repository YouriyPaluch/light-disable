package com.example.lightDisable

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Address(val city: String, val street: String, val house: String) {
	val title: String get() = "$city, $street, $house"
}


object Dtek {
	private const val BASE = "https://www.dtek-dnem.com.ua/ua"
	private const val UA =
		"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
			"Chrome/124.0 Mobile Safari/537.36"

	init {
		// Сесійні cookie потрібні разом із csrf-token; живуть лише поки працює процес
		if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager())
	}

	/** csrf-token поточної сесії (лише в пам'яті) */
	@Volatile
	var csrf: String? = null
		private set

	@Volatile
	var streets: Map<String, List<String>> = emptyMap()
		private set

	private val mutex = Mutex()

	/** GET /shutdowns: кешує HTML у cacheDir, дістає csrf-token і список вулиць */
	suspend fun load(context: Context, force: Boolean = false) {
		mutex.withLock {
			if (!force && csrf != null && streets.isNotEmpty()) return
			withContext(Dispatchers.IO) {
				var html = fetchPage(context)
				var token = findToken(html)
				if (token == null && csrf != null) {
					// Повторний GET не віддав токен (напр. антибот): лишаємо токен поточної сесії
					return@withContext
				}
				if (token == null) {
					// Спроба з чистою сесією (як при першому запуску)
					(CookieHandler.getDefault() as? CookieManager)?.cookieStore?.removeAll()
					html = fetchPage(context)
					token = findToken(html)
				}
				csrf = token ?: throw IOException("csrf-token не знайдено у відповіді сайту")
				streets = parseStreets(html)
			}
		}
	}

	/** Очищає все, крім збережених адрес: токен, вулиці, cookie, кеш HTML */
	fun clear(context: Context) {
		csrf = null
		streets = emptyMap()
		(CookieHandler.getDefault() as? CookieManager)?.cookieStore?.removeAll()
		File(context.cacheDir, "shutdowns.html").delete()
	}

	/** Рядкове значення поля; порожній рядок, якщо поля немає або воно null */
	private fun text(o: JSONObject, key: String): String =
		if (o.isNull(key)) "" else o.optString(key, "").trim()

	/** Рекурсивний пошук об'єкта з ключем [key] у будь-якому місці JSON */
	private fun findObject(node: Any?, key: String): JSONObject? {
		when (node) {
			is JSONObject -> {
				node.optJSONObject(key)?.let { return it }
				for (k in node.keys()) findObject(node.opt(k), key)?.let { return it }
			}

			is JSONArray -> for (i in 0 until node.length()) findObject(node.opt(i), key)?.let { return it }
		}
		return null
	}

	/** preset.time_type (підписи значень) і preset.time_zone (годинні проміжки «ключ 1..24 -> [підпис, початок, кінець]») */
	private fun parsePreset(preset: JSONObject?): Preset {
		if (preset == null) return Preset.EMPTY
		val types = preset.optJSONObject("time_type")
		val zones = preset.optJSONObject("time_zone")
		return Preset(
			timeTypes = types?.keys()?.asSequence()?.associateWith { types.optString(it) }.orEmpty(),
			// Рівно 24 проміжки або нічого (тоді будуть значення за замовчуванням)
			zones = (1..24).mapNotNull { zones?.optJSONArray(it.toString()) }
				.map { Zone(it.optString(0), it.optString(1), it.optString(2)) }
				.let { if (it.size == 24) it else emptyList() },
			days = preset.optJSONObject("days")?.let { d -> d.keys().asSequence().associateWith { d.optString(it) } }.orEmpty(),
		)
	}

	private fun schName(names: JSONObject?, reason: String): String =
		if (names == null || names.isNull(reason)) "" else names.optString(reason, "").trim()

	/** sch_names з закешованої сторінки /shutdowns (запасний варіант) */
	private fun schNamesFromPage(context: Context): JSONObject? = try {
		val html = File(context.cacheDir, "shutdowns.html").readText()
		Regex(""""sch_names"\s*:\s*(\{[^{}]*\})""").find(html)?.let { JSONObject(it.groupValues[1]) }
	} catch (e: Exception) {
		null
	}

	private fun fetchPage(context: Context): String {
		val html = request("$BASE/shutdowns", null, null)
		File(context.cacheDir, "shutdowns.html").writeText(html)
		return html
	}

	private fun findToken(html: String): String? =
		Regex("""<meta\s+name=["']csrf-token["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
			.find(html)?.groupValues?.get(1)

	val isLoaded: Boolean get() = csrf != null && streets.isNotEmpty()

	/** Номери будинків — ключі параметра "data" */
	suspend fun houses(context: Context, city: String, street: String): List<String> {
		load(context)
		return withContext(Dispatchers.IO) {
			val data = ajax(city, street).optJSONObject("data") ?: return@withContext emptyList()
			// У data можуть бути й службові поля (напр. updateTimestamp) — беремо лише ключі з об'єктами будинків
			data.keys().asSequence().filter { data.optJSONObject(it) != null }.toList().sortedWith(compareBy({ leadingNumber(it) }, { it }))
		}
	}

	/** GET (оновлення csrf) + POST, потім розбір графіка на сьогодні */
	suspend fun schedule(context: Context, address: Address): Schedule {
		load(context, force = true)
		return withContext(Dispatchers.IO) {
			val json = ajax(address.city, address.street)
			val data = json.optJSONObject("data") ?: throw IOException("Параметр data відсутній")
			val house = data.optJSONObject(address.house)
				?: throw IOException("Будинок ${address.house} не знайдено у відповіді")
			val r = house.opt("sub_type_reason")
			val reason = if (r is JSONArray) r.optString(0) else house.optString("sub_type_reason", "")
			if (reason.isEmpty()) throw IOException("sub_type_reason відсутній")
			val fact = json.optJSONObject("fact") ?: throw IOException("Параметр fact відсутній")
			val factData = fact.optJSONObject("data") ?: throw IOException("fact.data відсутній")
			val keys = factData.keys().asSequence().toList()
			if (keys.isEmpty()) throw IOException("fact.data порожній")
			fun keyDate(k: String) = k.toLongOrNull()?.let { Kyiv.dateKey(it * 1000) }
			// Сьогодні — ключ з датою сьогодні (або перший ключ), завтра — ключ з датою завтра
			val todayKey = keys.firstOrNull { keyDate(it) == Kyiv.dateKey(System.currentTimeMillis()) } ?: keys.first()
			val tomorrowKey = keys.firstOrNull { keyDate(it) == Kyiv.dateKeyPlusDays(1) && it != todayKey }
			fun day(k: String): Day? {
				val h = factData.optJSONObject(k)?.optJSONObject(reason) ?: return null
				return Day(k.toLongOrNull(), (1..24).map { h.optString(it.toString(), "yes") })
			}
			val today = day(todayKey) ?: throw IOException("Немає графіка для $reason")
			val tomorrow = tomorrowKey?.let { day(it) }
			// Назва черги: preset.sch_names (запасно — будь-де у відповіді, потім у кешованій сторінці),
			// інакше сам sub_type_reason
			val presetJson = json.optJSONObject("preset")
			val schName = schName(presetJson?.optJSONObject("sch_names") ?: findObject(json, "sch_names"), reason)
				.ifEmpty { schName(schNamesFromPage(context), reason) }
				.ifEmpty { reason }
			Schedule(
				today = today,
				tomorrow = tomorrow,
				schName = schName,
				updatedAt = fact.optString("update"),
				subType = text(house, "sub_type"),
				startDate = text(house, "start_date"),
				endDate = text(house, "end_date"),
				updateTimestamp = text(data, "updateTimestamp")
					.ifEmpty { text(json, "updateTimestamp") }
					.ifEmpty { text(fact, "updateTimestamp") },
				preset = parsePreset(presetJson),
			)
		}
	}

	private fun ajax(city: String, street: String): JSONObject {
		val now = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.US).format(Date())
		val params = listOf(
			"method" to "getHomeNum",
			"data[0][name]" to "city", "data[0][value]" to city,
			"data[1][name]" to "street", "data[1][value]" to street,
			"data[2][name]" to "updateFact", "data[2][value]" to now,
		)
		val body = params.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
		return JSONObject(request("$BASE/ajax", body, csrf))
	}

	private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

	private fun leadingNumber(s: String): Int =
		s.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

	/** body == null -> GET, інакше POST */
	private fun request(url: String, body: String?, token: String?): String {
		val conn = URL(url).openConnection() as HttpURLConnection
		try {
			conn.connectTimeout = 20_000
			conn.readTimeout = 30_000
			conn.setRequestProperty("User-Agent", UA)
			if (body == null) {
				conn.requestMethod = "GET"
			} else {
				conn.requestMethod = "POST"
				conn.doOutput = true
				conn.setRequestProperty("x-requested-with", "XMLHttpRequest")
				conn.setRequestProperty("X-CSRF-TOKEN", token.orEmpty())
				conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
				conn.setRequestProperty("Referer", "$BASE/shutdowns")
				conn.outputStream.use { it.write(body.toByteArray()) }
			}
			val code = conn.responseCode
			if (code !in 200..299) throw IOException("HTTP $code")
			return conn.inputStream.bufferedReader().use { it.readText() }
		} finally {
			conn.disconnect()
		}
	}

	/** Витягує об'єкт після `DisconSchedule.streets =` підрахунком дужок */
	private fun parseStreets(html: String): Map<String, List<String>> {
		val key = html.indexOf("DisconSchedule.streets")
		if (key < 0) throw IOException("DisconSchedule.streets не знайдено")
		val start = html.indexOf('{', key)
		if (start < 0) throw IOException("Некоректний DisconSchedule.streets")
		var depth = 0
		var inStr = false
		var esc = false
		var end = -1
		for (i in start until html.length) {
			val c = html[i]
			if (inStr) {
				if (esc) esc = false
				else if (c == '\\') esc = true
				else if (c == '"') inStr = false
			} else when (c) {
				'"' -> inStr = true
				'{' -> depth++
				'}' -> if (--depth == 0) { end = i; break }
			}
		}
		if (end < 0) throw IOException("Некоректний DisconSchedule.streets")
		val obj = JSONObject(html.substring(start, end + 1))
		return obj.keys().asSequence().associateWith { city ->
			val arr = obj.getJSONArray(city)
			(0 until arr.length()).map { arr.getString(it) }
		}
	}
}

class AddressStore(context: Context) {
	private val prefs = context.getSharedPreferences("addresses", Context.MODE_PRIVATE)

	fun load(): List<Address> {
		val arr = JSONArray(prefs.getString("list", "[]"))
		return (0 until arr.length()).map {
			val o = arr.getJSONObject(it)
			Address(o.getString("city"), o.getString("street"), o.getString("house"))
		}
	}

	fun save(list: List<Address>) {
		val arr = JSONArray()
		list.forEach {
			arr.put(JSONObject().put("city", it.city).put("street", it.street).put("house", it.house))
		}
		prefs.edit().putString("list", arr.toString()).apply()
	}
}

