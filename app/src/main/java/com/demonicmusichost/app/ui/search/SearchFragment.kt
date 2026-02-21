package com.demonicmusichost.app.ui.search

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.demonicmusichost.app.R
import com.demonicmusichost.app.data.model.SearchResult
import com.demonicmusichost.app.databinding.FragmentSearchBinding
import com.demonicmusichost.app.util.hideKeyboard
import com.demonicmusichost.app.util.showSnackbar
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SearchViewModel by viewModels()
    private lateinit var searchAdapter: SearchResultAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onTabChanged(SearchTab.LOCAL)
        } else {
            binding.root.showSnackbar("Speicherzugriff wird benötigt, um lokale Musik zu lesen")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAdapter()
        setupTabs()
        setupSearch()
        observeViewModel()
    }

    private fun setupAdapter() {
        searchAdapter = SearchResultAdapter { result ->
            viewModel.addToQueue(result)
        }
        binding.rvResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val searchTab = when (tab?.position) {
                    0 -> SearchTab.SPOTIFY
                    1 -> SearchTab.YOUTUBE
                    2 -> {
                        checkStoragePermission()
                        SearchTab.LOCAL
                    }
                    else -> SearchTab.SPOTIFY
                }
                viewModel.onTabChanged(searchTab)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener { text ->
            viewModel.onQueryChanged(text?.toString() ?: "")
        }

        binding.btnBack.setOnClickListener {
            hideKeyboard()
            findNavController().navigateUp()
        }

        binding.etSearch.requestFocus()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.progressBar.isVisible = loading
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeTab.collect { tab ->
                updateResults(tab)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.spotifyResults.collect { if (viewModel.activeTab.value == SearchTab.SPOTIFY) updateList(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.youtubeResults.collect { if (viewModel.activeTab.value == SearchTab.YOUTUBE) updateList(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.localResults.collect { if (viewModel.activeTab.value == SearchTab.LOCAL) updateList(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is SearchEvent.ShowError -> binding.root.showSnackbar(event.message)
                    is SearchEvent.ShowMessage -> binding.root.showSnackbar(event.message)
                    is SearchEvent.SongAdded -> {
                        // Optionally navigate back after adding
                    }
                    SearchEvent.NavigateBack -> findNavController().navigateUp()
                }
            }
        }
    }

    private fun updateResults(tab: SearchTab) {
        when (tab) {
            SearchTab.SPOTIFY -> updateList(viewModel.spotifyResults.value)
            SearchTab.YOUTUBE -> updateList(viewModel.youtubeResults.value)
            SearchTab.LOCAL -> updateList(viewModel.localResults.value)
        }
    }

    private fun <T : SearchResult> updateList(results: List<T>) {
        searchAdapter.submitList(results)
        val query = viewModel.searchQuery.value
        binding.tvEmpty.isVisible = results.isEmpty() && query.length >= 2
        binding.tvHint.isVisible = query.length < 2
    }

    private fun checkStoragePermission() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permission)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
