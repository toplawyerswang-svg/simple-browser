package com.trae.simplebrowser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.trae.simplebrowser.databinding.ActivityBookmarksBinding

import android.text.Editable
import android.text.TextWatcher

class BookmarksActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBookmarksBinding
    private lateinit var adapter: BookmarkAdapter
    private var allBookmarks = listOf<Bookmark>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = BookmarkAdapter()
        binding.bookmarksListView.adapter = adapter

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterBookmarks(s?.toString().orEmpty())
            }
        })

        binding.bookmarksListView.setOnItemClickListener { _, _, position, _ ->
            val bookmark = adapter.getItem(position) ?: return@setOnItemClickListener
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(bookmark.url), this, MainActivity::class.java)
            startActivity(intent)
        }

        binding.bookmarksListView.setOnItemLongClickListener { _, _, position, _ ->
            val bookmark = adapter.getItem(position) ?: return@setOnItemLongClickListener true
            AppPrefs.removeBookmark(this, bookmark.url)
            reload()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        allBookmarks = AppPrefs.getBookmarks(this)
        filterBookmarks(binding.searchEditText.text.toString())
    }

    private fun filterBookmarks(query: String) {
        val filtered = if (query.isBlank()) {
            allBookmarks
        } else {
            allBookmarks.filter {
                it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
            }
        }
        adapter.setItems(filtered, query)
    }

    private inner class BookmarkAdapter : ArrayAdapter<Bookmark>(
        this,
        R.layout.item_bookmark,
        ArrayList()
    ) {
        private val items = ArrayList<Bookmark>()
        private var currentQuery: String = ""

        fun setItems(list: List<Bookmark>, query: String = "") {
            items.clear()
            items.addAll(list)
            currentQuery = query
            clear()
            addAll(items)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Bookmark? = items.getOrNull(position)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bookmark, parent, false)

            val titleTextView = view.findViewById<TextView>(R.id.titleTextView)
            val urlTextView = view.findViewById<TextView>(R.id.urlTextView)
            val item = items[position]
            
            titleTextView.text = highlightText(item.title.ifBlank { item.url }, currentQuery)
            urlTextView.text = highlightText(item.url, currentQuery)
            return view
        }
        
        private fun highlightText(text: String, query: String): CharSequence {
            if (query.isBlank()) return text
            val spannable = android.text.SpannableString(text)
            val lowerText = text.lowercase()
            val lowerQuery = query.lowercase()
            var start = lowerText.indexOf(lowerQuery)
            
            val color = androidx.core.content.ContextCompat.getColor(context, R.color.search_highlight)
            
            while (start >= 0) {
                val end = start + lowerQuery.length
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                start = lowerText.indexOf(lowerQuery, end)
            }
            return spannable
        }
    }
}
