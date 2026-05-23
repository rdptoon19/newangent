package com.otgcam.agent.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.textfield.TextInputEditText
import com.otgcam.agent.CameraService
import com.otgcam.agent.MainActivity
import com.otgcam.agent.R

/**
 * First-launch fragment that collects and securely stores the Telegram credentials
 * and device Agent ID needed to operate the OTGCam Agent service.
 *
 * Credentials are stored in [EncryptedSharedPreferences] and never logged.
 */
class SetupFragment : Fragment() {

    private lateinit var etBotToken: TextInputEditText
    private lateinit var etChatId: TextInputEditText
    private lateinit var etAgentId: TextInputEditText

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etBotToken = view.findViewById(R.id.etBotToken)
        etChatId   = view.findViewById(R.id.etChatId)
        etAgentId  = view.findViewById(R.id.etAgentId)

        // Mask fields by default
        etBotToken.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        etChatId.inputType   = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        view.findViewById<View>(R.id.btnRevealToggle).setOnClickListener {
            toggleReveal()
        }

        view.findViewById<View>(R.id.btnSaveAndContinue).setOnClickListener {
            saveAndContinue()
        }
    }

    private fun toggleReveal() {
        val currentlyMasked = (etBotToken.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
        val newType = if (currentlyMasked) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        etBotToken.inputType = newType
        etChatId.inputType   = newType

        // Preserve cursor position
        etBotToken.text?.let { etBotToken.setSelection(it.length) }
        etChatId.text?.let   { etChatId.setSelection(it.length) }
    }

    private fun saveAndContinue() {
        val token   = etBotToken.text?.toString()?.trim() ?: ""
        val chatId  = etChatId.text?.toString()?.trim() ?: ""
        val agentId = etAgentId.text?.toString()?.trim() ?: ""

        if (token.isEmpty() || chatId.isEmpty() || agentId.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.setup_all_fields_required), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val masterKey = MasterKey.Builder(requireContext())
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                requireContext(),
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.edit()
                .putString(CameraService.KEY_BOT_TOKEN, token)
                .putString(CameraService.KEY_CHAT_ID, chatId)
                .putString(CameraService.KEY_AGENT_ID, agentId)
                .apply()

            // Mark setup as complete
            requireContext()
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SETUP_DONE, true).apply()

            (activity as? MainActivity)?.showMainScreen()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.setup_save_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val ENCRYPTED_PREFS_NAME = "otgcam_secure_prefs"
        private const val PREFS_NAME = "otgcam_prefs"
        const val KEY_SETUP_DONE = "setup_done"
    }
}
