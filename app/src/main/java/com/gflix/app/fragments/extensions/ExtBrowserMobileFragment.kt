package com.gflix.app.fragments.extensions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gflix.app.extensions.ExtEntity
import com.gflix.extcore.CsExtensionMeta
import kotlinx.coroutines.launch

open class ExtBrowserMobileFragment : Fragment() {

    protected val viewModel by viewModels<ExtensionsViewModel>()
    private lateinit var rows: LinearLayout

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
        val refresh = Button(ctx).apply { text = "Refresh" }
        refresh.setOnClickListener { viewModel.refresh() }
        rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(refresh)
        col.addView(rows)
        root.addView(col)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.available.collect { render() } }
                launch { viewModel.installed.collect { render() } }
                launch { viewModel.currentId.collect { render() } }
            }
        }
    }

    private fun render() {
        if (!::rows.isInitialized) return
        val ctx = requireContext()
        rows.removeAllViews()
        val available = viewModel.available.value
        val installed = viewModel.installed.value.associateBy { it.internalName }
        val current = viewModel.currentId.value
        if (available.isEmpty() && installed.isEmpty()) {
            rows.addView(TextView(ctx).apply { text = "No extensions. Add a repo first." })
            return
        }
        for (meta in available) {
            rows.addView(rowView(meta, installed[meta.internalName], current == meta.internalName))
        }
        for ((id, ext) in installed) {
            if (available.none { it.internalName == id }) {
                rows.addView(orphanRowView(ext, current == id))
            }
        }
    }

    private fun rowView(meta: CsExtensionMeta, ext: ExtEntity?, isCurrent: Boolean): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val label = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = statusText(meta, ext, isCurrent)
        }
        row.addView(label)
        if (ext == null) {
            row.addView(Button(ctx).apply {
                text = "Install"
                setOnClickListener { viewModel.install(meta, ::toastResult) }
            })
        } else {
            if (!isCurrent) {
                row.addView(Button(ctx).apply {
                    text = "Use"
                    setOnClickListener {
                        viewModel.selectExtension(meta.internalName) { ok ->
                            toastResult(ok)
                            if (ok) runCatching {
                                androidx.navigation.fragment.findNavController(this@ExtBrowserMobileFragment)
                                    .navigate(com.gflix.app.R.id.home)
                            }
                        }
                    }
                })
            }
            row.addView(Button(ctx).apply {
                text = if (ext.enabled) "Off" else "On"
                setOnClickListener { viewModel.setEnabled(meta.internalName, !ext.enabled) }
            })
            row.addView(Button(ctx).apply {
                text = "Del"
                setOnClickListener { viewModel.deleteExtension(meta.internalName) }
            })
        }
        return row
    }

    private fun orphanRowView(ext: ExtEntity, isCurrent: Boolean): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "• ${ext.name} v${ext.version} (repo removed)" + if (isCurrent) " [current]" else ""
        })
        row.addView(Button(ctx).apply {
            text = "Del"
            setOnClickListener { viewModel.deleteExtension(ext.internalName) }
        })
        return row
    }

    private fun statusText(meta: CsExtensionMeta, ext: ExtEntity?, isCurrent: Boolean): String {
        val base = "• ${meta.name} v${meta.version} [${meta.language ?: "?"}]"
        if (ext == null) return base
        val state = if (!ext.enabled) "disabled" else "installed"
        return base + " — $state" + if (isCurrent) " [current]" else ""
    }

    private fun toastResult(ok: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            Toast.makeText(
                requireContext(),
                if (ok) "Done" else "Failed: ${viewModel.error.value}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
