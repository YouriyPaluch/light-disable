package com.example.lightDisable

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Дні рахуються за київським часом */
object Kyiv {
	private val tz: TimeZone = TimeZone.getTimeZone("Europe/Kyiv")

	/** Дата у вигляді yyyyMMdd для порівняння днів */
	fun dateKey(ms: Long): String =
		SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = tz }.format(Date(ms))

	fun dateKeyPlusDays(days: Int): String = dateKey(startOfDayMs(days))

	/** Початок дня зі зсувом [days] від сьогодні, мс */
	fun startOfDayMs(days: Int): Long = Calendar.getInstance(tz).apply {
		set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
		set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
		add(Calendar.DAY_OF_YEAR, days)
	}.timeInMillis

	/** День тижня за ISO: 1 = понеділок … 7 = неділя */
	fun isoWeekday(ms: Long): Int {
		val dow = Calendar.getInstance(tz).apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK) // 1 = неділя
		return (dow + 5) % 7 + 1
	}

	fun format(pattern: String, ms: Long): String =
		SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }.format(Date(ms))
}

/** Графік одного дня: 24 значення (години 1..24) no / yes / first / second / maybe */
class Day(
	val dateTs: Long?,
	val hours: List<String>,
) {
	val dateKey: String? get() = dateTs?.let { Kyiv.dateKey(it * 1000) }
}

/** Годинний проміжок з preset.time_zone: підпис («00-01»), початок («00:00»), кінець («01:00») */
class Zone(val label: String, val start: String, val end: String)

/**
 * Довідники з preset у відповіді сайту: підписи значень (time_type) і годинні проміжки (time_zone).
 * Також зберігається в пам'яті; якщо даних немає — використовуються значення за замовчуванням.
 */
class Preset(
	val timeTypes: Map<String, String>,
	val zones: List<Zone>,
	/** preset.days: "1" → «Понеділок» … "7" → «Неділя» */
	val days: Map<String, String> = emptyMap(),
) {
	/** Назва дня тижня для моменту [ms] за київським часом */
	fun dayName(ms: Long): String {
		val n = Kyiv.isoWeekday(ms).toString()
		return days[n] ?: DEFAULT_DAYS[n].orEmpty()
	}

	fun typeText(key: String): String = timeTypes[key] ?: DEFAULT_TYPES[key] ?: key

	/** Ключі для легенди: спочатку відомі у звичному порядку, потім будь-які нові з time_type */
	fun legendKeys(): List<String> {
		val known = ORDER.filter { it in timeTypes || it in DEFAULT_TYPES }
		return known + timeTypes.keys.filter { it !in ORDER }
	}

	/** Підпис години h (0..23), напр. «00-01» */
	fun hourLabel(h: Int): String = zones.getOrNull(h)?.label ?: "%02d-%02d".format(h, h + 1)

	/** Час межі півгодинного слоту (0..48): «00:00», «00:30», …, «24:00» */
	fun slotTime(slot: Int): String {
		if (slot == 48) return zones.getOrNull(23)?.end ?: "24:00"
		val base = zones.getOrNull(slot / 2)?.start ?: "%02d:00".format(slot / 2)
		return if (slot % 2 == 0) base else base.substringBefore(":") + ":30"
	}

	companion object {
		val EMPTY = Preset(emptyMap(), emptyList())
		private val ORDER = listOf("yes", "no", "first", "second", "maybe", "mfirst", "msecond")
		private val DEFAULT_DAYS = mapOf(
			"1" to "Понеділок", "2" to "Вівторок", "3" to "Середа", "4" to "Четвер",
			"5" to "П'ятниця", "6" to "Субота", "7" to "Неділя",
		)
		private val DEFAULT_TYPES = mapOf(
			"yes" to "Світло є",
			"no" to "Світла немає",
			"first" to "Світла не буде перші 30 хв.",
			"second" to "Світла не буде другі 30 хв",
			"maybe" to "Можливо відключення",
			"mfirst" to "Світла можливо не буде перші 30 хв.",
			"msecond" to "Світла можливо не буде другі 30 хв",
		)
	}
}

class Schedule(
	/** Графік на сьогодні */
	val today: Day,
	/** Графік на завтра, якщо він є у відповіді */
	val tomorrow: Day?,
	/** Назва черги (sch_names), напр. «Черга 1.1» */
	val schName: String,
	val updatedAt: String,
	/** Дані про відключення з даних будинку; порожні, якщо їх немає */
	val subType: String,
	val startDate: String,
	val endDate: String,
	val updateTimestamp: String,
	/** Підписи значень і годинні проміжки з preset */
	val preset: Preset = Preset.EMPTY,
)

/** Останній успішно отриманий графік кожної адреси — для показу, коли новий отримати не вдалося */
class ScheduleStore(context: Context) {
	private val prefs = context.getSharedPreferences("schedules", Context.MODE_PRIVATE)

	private fun key(a: Address) = "${a.city}|${a.street}|${a.house}"

	private fun dayToJson(d: Day) = JSONObject()
		.put("dateTs", d.dateTs ?: JSONObject.NULL)
		.put("hours", JSONArray(d.hours))

	/** null для залишків тестових даних (прапорець isTest у старих записах) — вони відкидаються */
	private fun dayFromJson(o: JSONObject): Day? {
		if (o.optBoolean("isTest", false)) return null
		val h = o.getJSONArray("hours")
		return Day(
			dateTs = if (o.isNull("dateTs")) null else o.getLong("dateTs"),
			hours = (0 until h.length()).map { h.getString(it) },
		)
	}

	private fun presetFromJson(o: JSONObject): Preset {
		val t = o.optJSONObject("timeTypes")
		val z = o.optJSONArray("zones")
		return Preset(
			timeTypes = t?.keys()?.asSequence()?.associateWith { t.optString(it) }.orEmpty(),
			zones = if (z == null) emptyList() else (0 until z.length()).map {
				val a = z.getJSONArray(it)
				Zone(a.optString(0), a.optString(1), a.optString(2))
			},
			days = o.optJSONObject("days")?.let { d -> d.keys().asSequence().associateWith { d.optString(it) } }.orEmpty(),
		)
	}

	/** Перезаписує збережений графік адреси (старий запис замінюється) */
	fun save(address: Address, s: Schedule, savedAt: Long = System.currentTimeMillis()) {
		val o = JSONObject()
			.put("savedAt", savedAt)
			.put("schName", s.schName)
			.put("updatedAt", s.updatedAt)
			.put("subType", s.subType)
			.put("startDate", s.startDate)
			.put("endDate", s.endDate)
			.put("updateTimestamp", s.updateTimestamp)
			.put("today", dayToJson(s.today))
			.put("tomorrow", s.tomorrow?.let { dayToJson(it) } ?: JSONObject.NULL)
			.put("timeTypes", JSONObject(s.preset.timeTypes))
			.put("days", JSONObject(s.preset.days))
			.put(
				"zones",
				JSONArray(s.preset.zones.map { JSONArray(listOf(it.label, it.start, it.end)) }),
			)
		prefs.edit().putString(key(address), o.toString()).apply()
	}

	/**
	 * Збережений графік і час збереження (мс), або null.
	 * Застарілі дані відкидаються: вчорашній день видаляється, а збережений «завтрашній»
	 * день, якщо він уже став сьогоднішнім, стає поточним.
	 */
	fun load(address: Address): Pair<Schedule, Long>? {
		val raw = prefs.getString(key(address), null) ?: return null
		return try {
			val o = JSONObject(raw)
			val savedAt = o.getLong("savedAt")
			val storedToday = if (o.has("today")) dayFromJson(o.getJSONObject("today")) else dayFromJson(o) // старий формат
			val storedTomorrow = o.optJSONObject("tomorrow")?.let { dayFromJson(it) }

			val todayKey = Kyiv.dateKey(System.currentTimeMillis())
			val tomorrowKey = Kyiv.dateKeyPlusDays(1)
			val days = listOfNotNull(storedToday, storedTomorrow)
			val newToday = days.firstOrNull { (it.dateKey ?: todayKey) == todayKey }
			val newTomorrow = days.firstOrNull { it.dateKey == tomorrowKey }
			if (newToday == null) {
				remove(address)
				return null
			}
			val result = Schedule(
				today = newToday,
				tomorrow = newTomorrow,
				schName = o.optString("schName"),
				updatedAt = o.optString("updatedAt"),
				subType = o.optString("subType"),
				startDate = o.optString("startDate"),
				endDate = o.optString("endDate"),
				updateTimestamp = o.optString("updateTimestamp"),
				preset = presetFromJson(o),
			)
			val droppedTest = !o.isNull("tomorrow") && o.has("tomorrow") && storedTomorrow == null
			if (newToday !== storedToday || newTomorrow !== storedTomorrow || !o.has("today") || droppedTest) {
				save(address, result, savedAt)
			}
			result to savedAt
		} catch (e: Exception) {
			null
		}
	}

	fun remove(address: Address) {
		prefs.edit().remove(key(address)).apply()
	}
}
