package com.gflix.app.fragments.extensions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * CloudStream-style extension browser (mobile): Install / Update / Enable /
 * Disable / Delete live in [ExtensionActions]; this fragment lists available
 * extensions with version/language badges. TV twin: [ExtBrowserTvFragment].
 */
open class ExtBrowserMobileFragment : Fragment() {

    protected val viewModel by viewModels<ExtensionsViewModel>()
    private lateinit var listView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        listView = TextView(ctx)
        val refresh = Button(ctx).apply { text = "Refresh" }
        refresh.setOnClickListener { viewModel.refresh() }
        col.addView(refresh)
        col.addView(listView)
        root.addView(col)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.available.collect { available ->
                    listView.text = if (available.isEmpty()) {
                        "No extensions. Add a repo first."
                    } else {
                        available.joinToString("\n") {
                            "\u2022 ${it.name} v${it.version} [${it.language ?: "?"}]" +
                                (it.tvTypes.takeIf { t -> t.isNotEmpty() }?.joinToString("/") ?: "")
                        }
                    }
                }
            }
        }
    }
}
