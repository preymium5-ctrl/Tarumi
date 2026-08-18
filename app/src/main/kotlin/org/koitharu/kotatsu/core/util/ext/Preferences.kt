package org.koitharu.kotatsu.core.util.ext

import android.content.SharedPreferences
import androidx.collection.ArraySet
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import org.json.JSONArray

fun ListPreference.setDefaultValueCompat(defaultValue: String) {
	if (value == null) {
		value = defaultValue
	}
}

fun MultiSelectListPreference.setDefaultValueCompat(defaultValue: Set<String>) {
	setDefaultValue(defaultValue) // FIXME not working
}

fun <E : Enum<E>> SharedPreferences.getEnumValue(key: String, enumClass: Class<E>): E? {
	val stringValue = getString(key, null) ?: return null
	return enumClass.enumConstants?.find {
		it.name == stringValue
	}
}

fun <E : Enum<E>> SharedPreferences.getEnumValue(key: String, defaultValue: E): E {
	return getEnumValue(key, defaultValue.javaClass) ?: defaultValue
}

fun <E : Enum<E>> SharedPreferences.Editor.putEnumValue(key: String, value: E?) {
	putString(key, value?.name)
}

fun SharedPreferences.observeChanges(): Flow<String?> = callbackFlow {
	val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		trySendBlocking(key)
	}
	registerOnSharedPreferenceChangeListener(listener)
	awaitClose {
		unregisterOnSharedPreferenceChangeListener(listener)
	}
}

fun <T> SharedPreferences.observe(key: String, valueProducer: suspend () -> T): Flow<T> = flow {
	emit(valueProducer())
	observeChanges().collect { upstreamKey ->
		if (upstreamKey == key) {
			emit(valueProducer())
		}
	}
}.distinctUntilChanged()

fun SharedPreferences.Editor.putAll(values: Map<String, *>, existingValues: Map<String, *> = emptyMap<String, Any>()) {
	values.forEach { e ->
		val key = e.key
		val v = e.value ?: return@forEach
		val existing = existingValues[key]

		when {
			v is Boolean -> putBoolean(key, v)
			v is String -> putString(key, v)
			v is JSONArray -> putStringSet(key, v.toStringSet())
			v is Number -> {
				when (existing) {
					is Long -> putLong(key, v.toLong())
					is Float -> putFloat(key, v.toFloat())
					is Int -> putInt(key, v.toInt())
					is Boolean -> putBoolean(key, v.toInt() != 0)
					else -> {
						when (v) {
							is Double -> putFloat(key, v.toFloat())
							is Float -> putFloat(key, v)
							is Long -> putLong(key, v)
							is Int -> {
								if (key.endsWith("_at") || key.endsWith("_date") || key.endsWith("duration") || key == "active_preset_2") {
									putLong(key, v.toLong())
								} else {
									putInt(key, v)
								}
							}
							else -> putInt(key, v.toInt())
						}
					}
				}
			}
		}
	}
}

private fun JSONArray.toStringSet(): Set<String> {
	val len = length()
	val result = ArraySet<String>(len)
	for (i in 0 until len) {
		result.add(getString(i))
	}
	return result
}
