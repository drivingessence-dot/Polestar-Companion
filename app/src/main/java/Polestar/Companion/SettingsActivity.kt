package Polestar.Companion

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Switch
import android.content.SharedPreferences
import Polestar.Companion.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sharedPreferences: SharedPreferences
    
    companion object {
        private const val PREFS_NAME = "PolestarCompanionPrefs"
        private const val KEY_IMPERIAL_UNITS = "imperial_units"
        private const val KEY_DARK_THEME = "dark_theme"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme before setting content view
        applyTheme()
        
        super.onCreate(savedInstanceState)
        
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
        setupUI()
        loadSettings()
    }
    
    private fun applyTheme() {
        val useDarkTheme = sharedPreferences.getBoolean(KEY_DARK_THEME, true) // Default to dark
        if (useDarkTheme) {
            setTheme(R.style.Theme_PolestarCompanion)
        } else {
            setTheme(R.style.Theme_PolestarCompanion_Light)
        }
    }
    
    private fun setupUI() {
        // Set up unit toggle
        binding.switchImperialUnits.setOnCheckedChangeListener { _, isChecked ->
            saveImperialUnits(isChecked)
        }
        
        // Set up theme toggle
        binding.switchDarkTheme.setOnCheckedChangeListener { _, isChecked ->
            saveDarkTheme(isChecked)
            // Note: Theme change will take effect on next app restart
            // For immediate effect, we would need to recreate the activity
        }
    }
    
    private fun loadSettings() {
        val useImperialUnits = sharedPreferences.getBoolean(KEY_IMPERIAL_UNITS, false)
        val useDarkTheme = sharedPreferences.getBoolean(KEY_DARK_THEME, false)
        
        binding.switchImperialUnits.isChecked = useImperialUnits
        binding.switchDarkTheme.isChecked = useDarkTheme
    }
    
    private fun saveImperialUnits(useImperial: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_IMPERIAL_UNITS, useImperial)
            .apply()
    }
    
    private fun saveDarkTheme(useDark: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_DARK_THEME, useDark)
            .apply()
    }
    
    companion object {
        fun getImperialUnits(prefs: SharedPreferences): Boolean {
            return prefs.getBoolean(KEY_IMPERIAL_UNITS, false)
        }
        
        fun getDarkTheme(prefs: SharedPreferences): Boolean {
            return prefs.getBoolean(KEY_DARK_THEME, false)
        }
    }
}
