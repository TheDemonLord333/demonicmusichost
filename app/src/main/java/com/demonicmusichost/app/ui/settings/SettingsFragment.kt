package com.demonicmusichost.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.demonicmusichost.app.data.network.BackendConfig
import com.demonicmusichost.app.databinding.FragmentSettingsBinding
import com.demonicmusichost.app.util.showSnackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var backendConfig: BackendConfig

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        // Aktuelle URL vorausfüllen
        binding.etBackendUrl.setText(backendConfig.baseUrl)

        binding.btnSaveUrl.setOnClickListener { saveUrl() }
        binding.btnTestConnection.setOnClickListener { testConnection() }
        binding.btnResetUrl.setOnClickListener {
            backendConfig.baseUrl = BackendConfig.DEFAULT_URL
            binding.etBackendUrl.setText(BackendConfig.DEFAULT_URL)
            binding.root.showSnackbar("Auf Standard zurückgesetzt")
        }
    }

    private fun saveUrl() {
        val input = binding.etBackendUrl.text?.toString()?.trim() ?: ""
        if (input.isBlank()) {
            binding.tilBackendUrl.error = "URL darf nicht leer sein"
            return
        }
        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            binding.tilBackendUrl.error = "URL muss mit http:// oder https:// beginnen"
            return
        }
        binding.tilBackendUrl.error = null
        backendConfig.baseUrl = input
        binding.root.showSnackbar("Backend-URL gespeichert")
    }

    private fun testConnection() {
        val url = binding.etBackendUrl.text?.toString()?.trim() ?: ""
        if (url.isBlank()) {
            binding.tilBackendUrl.error = "Bitte zuerst eine URL eingeben"
            return
        }

        binding.layoutStatus.isVisible = true
        binding.progressTest.isVisible = true
        binding.tvStatus.text = "Verbinde mit $url/health …"
        binding.tvStatus.setTextColor(resources.getColor(com.demonicmusichost.app.R.color.text_secondary, null))
        binding.btnTestConnection.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                val conn = URL("$url/health").openConnection()
                conn.connectTimeout = 5_000
                conn.readTimeout    = 5_000
                conn.connect()
                val code = (conn as java.net.HttpURLConnection).responseCode
                code == 200
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                binding.progressTest.isVisible = false
                binding.btnTestConnection.isEnabled = true
                if (result.getOrDefault(false)) {
                    binding.tvStatus.text = "✓ Verbindung erfolgreich"
                    binding.tvStatus.setTextColor(
                        resources.getColor(com.demonicmusichost.app.R.color.success_color, null)
                    )
                } else {
                    binding.tvStatus.text = "✗ Keine Verbindung: ${result.exceptionOrNull()?.message ?: "HTTP-Fehler"}"
                    binding.tvStatus.setTextColor(
                        resources.getColor(com.demonicmusichost.app.R.color.error_color, null)
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
