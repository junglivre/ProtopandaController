package gay.protopanda.controller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import gay.protopanda.controller.databinding.ActivitySettingsBinding
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnRestoreDefaults.setOnClickListener { populate(BleIdentity.DEFAULT) }
        binding.btnOpenRepository.setOnClickListener {
            openUrl(REPOSITORY_URL)
        }
        binding.creditGooDDu.setOnClickListener { openUrl(GOODDU_URL) }
        binding.creditMockthebear.setOnClickListener { openUrl(MOCKTHEBEAR_URL) }
        binding.creditJunglivre.setOnClickListener { openUrl(JUNGLIVRE_URL) }
        binding.tvAppVersion.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
        AvatarLoader.load(binding.avatarGooDDu, "$GOODDU_URL.png?size=192")
        AvatarLoader.load(binding.avatarMockthebear, "$MOCKTHEBEAR_URL.png?size=192")
        AvatarLoader.load(binding.avatarJunglivre, "$JUNGLIVRE_URL.png?size=192")
        populate(BleIdentity.load(this))
    }

    private fun populate(identity: BleIdentity) {
        binding.etServiceUuid.setText(identity.serviceUuid.toString())
        binding.etReadWriteUuid.setText(identity.readWriteUuid.toString())
        binding.etNotifyUuid.setText(identity.notifyUuid.toString())
    }

    private fun save() {
        val serviceUuid = parseUuid(binding.etServiceUuid.text?.toString(), binding.tilServiceUuid) ?: return
        val readWriteUuid = parseUuid(binding.etReadWriteUuid.text?.toString(), binding.tilReadWriteUuid) ?: return
        val notifyUuid = parseUuid(binding.etNotifyUuid.text?.toString(), binding.tilNotifyUuid) ?: return
        if (serviceUuid == readWriteUuid || serviceUuid == notifyUuid || readWriteUuid == notifyUuid) {
            binding.tilNotifyUuid.error = getString(R.string.error_distinct_uuids)
            return
        }

        BleIdentity.save(this, BleIdentity(serviceUuid, readWriteUuid, notifyUuid))
        startService(Intent(this, BlePeripheralService::class.java).setAction(BlePeripheralService.ACTION_RELOAD_IDENTITY))
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun parseUuid(value: String?, input: com.google.android.material.textfield.TextInputLayout): UUID? = try {
        input.error = null
        UUID.fromString(value?.trim())
    } catch (_: IllegalArgumentException) {
        input.error = getString(R.string.error_uuid)
        null
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    companion object {
        private const val REPOSITORY_URL = "https://github.com/junglivre/ProtopandaController"
        private const val GOODDU_URL = "https://github.com/GooDDu"
        private const val MOCKTHEBEAR_URL = "https://github.com/mockthebear"
        private const val JUNGLIVRE_URL = "https://github.com/junglivre"
    }
}
