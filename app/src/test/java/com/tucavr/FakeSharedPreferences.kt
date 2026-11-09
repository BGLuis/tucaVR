package com.tucavr

import android.content.SharedPreferences

internal class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()

    override fun getAll(): Map<String, *> = HashMap(data)

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = data[key]?.toString() ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = (data[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = (data[key] as? Number)?.toInt() ?: defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = (data[key] as? Number)?.toLong() ?: defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = (data[key] as? Number)?.toFloat() ?: defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = (data[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(this)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
    }

    private class FakeEditor(
        private val prefs: FakeSharedPreferences,
    ) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearPending = false

        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            if (key != null) pending[key] = values?.toSet()
            return this
        }

        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = REMOVED
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearPending = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearPending) {
                prefs.data.clear()
            }
            for ((k, v) in pending) {
                if (v === REMOVED || v == null) {
                    prefs.data.remove(k)
                } else {
                    prefs.data[k] = v
                }
            }
        }

        companion object {
            private val REMOVED = Any()
        }
    }
}
