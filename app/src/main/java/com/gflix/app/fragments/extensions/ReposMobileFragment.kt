package com.gflix.app.fragments.extensions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * CloudStream-style repository manager (mobile).
 * Add repo URL → validate + fetch repository.json → list; delete repo.
 * Full Leanback twin: [ReposTvFragment]. No new XML: programmatic layout so
 * this slice compiles without resource changes.
 */
open class ReposMobileFragment : Fragment() {

    protected val viewModel by viewModels<ExtensionsViewModel>()
    private lateinit var statusView: TextView
    private lateinit var urlInput: EditText

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
        urlInput = EditText(ctx).apply { hint = "https://…/repository.json" }
        val addBtn = Button(ctx).apply { text = "Install repo" }
        statusView = TextView(ctx)
        addBtn.setOnClickListener {
            val url = urlInput.text.toString()
            viewModel.addRepo(url) { ok ->
                viewLifecycleOwner.lifecycleScope.launch {
                    Toast.makeText(
                        ctx,
                        if (ok) "Repo added" else "Failed: ${viewModel.error.value}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        col.addView(urlInput)
        col.addView(addBtn)
        col.addView(statusView)
        root.addView(col)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.repos.collect { repos ->
                    statusView.text = if (repos.isEmpty()) {
                        "No repos yet. Paste a repository.json URL above."
                    } else {
                        repos.joinToString("\n") { "• ${it.name} — ${it.url}" }
                    }
                }
            }
        }
    }
}
