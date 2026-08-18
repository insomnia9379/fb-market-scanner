package com.l7.dealscanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip

class MainActivity : AppCompatActivity() {

    private lateinit var db: ListingDb
    private lateinit var engine: ScraperEngine
    private lateinit var webView: WebView
    private lateinit var flipper: ViewFlipper
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var adapter: ListingsAdapter
    private lateinit var statsView: TextView

    private var currentFilter = "ALL"

    // Suggestions for the city box — but you can type ANY city name.
    private val citySuggestions = listOf(
        "Sebring", "Lake Placid", "Tampa", "Orlando", "Fort Myers",
        "West Palm Beach", "Miami", "Lakeland", "Sarasota", "Okeechobee",
        "Port Charlotte", "Bradenton", "Naples", "Winter Haven", "Vero Beach"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = ListingDb(this)
        webView = findViewById(R.id.webView)
        flipper = findViewById(R.id.flipper)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        statsView = findViewById(R.id.statsView)

        val list = findViewById<RecyclerView>(R.id.listings)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ListingsAdapter(
            onOpen = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            onHide = { id -> db.hide(id); refreshList() }
        )
        list.adapter = adapter

        val cityInput = findViewById<AutoCompleteTextView>(R.id.cityInput)
        cityInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, citySuggestions)
        )

        engine = ScraperEngine(
            webView = webView,
            db = db,
            log = { line -> appendLog(line) },
            onDone = { _, _, _ ->
                runOnUiThread {
                    findViewById<Button>(R.id.scanBtn).isEnabled = true
                    refreshList()
                    showDeals()
                }
            }
        )
        engine.setup()

        findViewById<Button>(R.id.scanBtn).setOnClickListener { startScan() }
        findViewById<Button>(R.id.browserBtn).setOnClickListener { showBrowser() }
        findViewById<Button>(R.id.dealsBtn).setOnClickListener { showDeals() }

        findViewById<Chip>(R.id.chipAll).setOnClickListener { setFilter("ALL") }
        findViewById<Chip>(R.id.chipSteals).setOnClickListener { setFilter(DealScorer.STEAL) }
        findViewById<Chip>(R.id.chipFair).setOnClickListener { setFilter(DealScorer.FAIR) }
        findViewById<Chip>(R.id.chipHigh).setOnClickListener { setFilter(DealScorer.HIGH) }

        appendLog("Deal Scanner ready. First scan? Hit Browser and log into Facebook once.")
        refreshList()
    }

    /** "Lake Placid" -> "lakeplacid", "Fort Myers, FL" -> "fortmyersfl" style slug. */
    private fun toSlug(city: String): String =
        city.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun startScan() {
        val query = findViewById<EditText>(R.id.queryInput).text.toString().ifBlank { "electric dirt bike" }
        val maxPrice = findViewById<EditText>(R.id.maxPriceInput).text.toString().toIntOrNull()
        val minPrice = findViewById<EditText>(R.id.minPriceInput).text.toString().toIntOrNull()
        val days = findViewById<EditText>(R.id.daysInput).text.toString().toIntOrNull()
        val radius = findViewById<EditText>(R.id.radiusInput).text.toString().toIntOrNull()

        val cityRaw = findViewById<AutoCompleteTextView>(R.id.cityInput).text.toString().ifBlank { "Sebring" }
        val slug = toSlug(cityRaw)

        val url = engine.buildSearchUrl(query, slug, minPrice, maxPrice, days, radius)
        appendLog("City: $cityRaw  |  Radius: ${radius ?: "default"} mi")
        findViewById<Button>(R.id.scanBtn).isEnabled = false
        showBrowser()  // watch it work — this is the live view
        engine.startScan(lifecycleScope, url)
    }

    private fun setFilter(f: String) {
        currentFilter = f
        refreshList()
    }

    private fun refreshList() {
        adapter.submit(db.getAll(currentFilter))
        val s = db.stats()
        statsView.text = "Total ${s["total"]}   🔥 ${s["steals"]}   ✓ ${s["fair"]}   ⚠ ${s["high"]}"
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            logView.append(line + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun showBrowser() {
        flipper.displayedChild = 0
    }

    private fun showDeals() {
        flipper.displayedChild = 1
        refreshList()
    }

    override fun onBackPressed() {
        if (flipper.displayedChild == 0 && webView.canGoBack()) {
            webView.goBack()
        } else if (flipper.displayedChild == 0) {
            showDeals()
        } else {
            super.onBackPressed()
        }
    }
}
