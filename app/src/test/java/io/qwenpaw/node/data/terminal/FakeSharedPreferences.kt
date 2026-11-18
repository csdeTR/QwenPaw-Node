package io.qwenpaw.node.data.terminal

import android.content.SharedPreferences

/** 仅测试用；SshProfileStore 的加密层在真实设备上由 EncryptedSharedPreferences 提供。 */
class FakeSharedPreferences : SharedPreferences {

    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()

    override fun getString(key: String, defValue: String?): String? =
        data[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (data[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            changes[key] = value
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply { changes[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            changes[key] = value
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            changes[key] = value
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            changes[key] = value
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            changes[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply {
            changes[key] = null
        }

        override fun clear(): SharedPreferences.Editor = apply { changes.clear() }

        override fun commit(): Boolean {
            changes.forEach { (key, value) ->
                if (value == null) data.remove(key) else data[key] = value
            }
            changes.clear()
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
