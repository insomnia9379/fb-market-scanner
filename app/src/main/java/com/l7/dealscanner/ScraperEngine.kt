package com.l7.dealscanner

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * The Android replacement for the Playwright scraper.
 * Drives a WebView: loads a Marketplace search URL, auto-scrolls,
 * and extracts listing cards with injected JavaScript.
 *
 * Login persists via the WebView CookieManager — log in once, it sticks.
 */
class ScraperEngine(
    private val webView: WebView,
    private val db: ListingDb,
    private val log: (String) -> Unit,
    private val onDone: (found: Int, newCount: Int, drops: Int) -> Unit
) {
    // Desktop UA so we get the desktop Marketplace layout our extractor expects.
    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    var scanning = false
        private set

    private var pageLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    fun setup() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = desktopUa
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
            }
        }
    }

    fun buildSearchUrl(query: String, citySlug: String, minPrice: Int?, maxPrice: Int?, days: Int?, radiusMiles: Int?): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val sb = StringBuilder("https://www.facebook.com/marketplace/$citySlug/search?query=$q")
        minPrice?.let { sb.append("&minPrice=$it") }
        maxPrice?.let { sb.append("&maxPrice=$it") }
        days?.let { sb.append("&daysSinceListed=$it") }
        radiusMiles?.let { sb.append("&radius=$it") }
        sb.append("&exact=false")
        return sb.toString()
    }

    fun startScan(scope: CoroutineScope, url: String, scrollPasses: Int = 6) {
        if (scanning) { log("⚠ Scan already running"); return }
        scanning = true
        pageLoaded = false
        log("▶ Loading: $url")
        webView.loadUrl(url)

        scope.launch(Dispatchers.Main) {
            try {
                // Wait for page load (up to 30s)
                var waited = 0
                while (!pageLoaded && waited < 30_000) { delay(500); waited += 500 }
                if (!pageLoaded) { log("✗ Page load timed out"); finish(0, 0, 0); return@launch }
                delay(4000) // let dynamic content render

                if (needsLogin()) {
                    log("🔑 Not logged in. Log into Facebook in the browser tab, then hit Scan again.")
                    log("   (Your login is saved after the first time.)")
                    finish(0, 0, 0)
                    return@launch
                }

                // Auto-scroll to load more listings
                repeat(scrollPasses) { i ->
                    log("… scroll pass ${i + 1}/$scrollPasses")
                    evalJs("window.scrollTo(0, document.body.scrollHeight);")
                    delay(2500)
                }

                // Extract
                log("⛏ Extracting listings…")
                val raw = evalJs(EXTRACT_JS)
                val items = parseExtraction(raw)
                log("Found ${items.size} listing cards")

                var newCount = 0
                var drops = 0
                for (item in items) {
                    val result = db.upsert(
                        id = item.id, title = item.title, price = item.price,
                        location = item.location, url = item.url, imgUrl = item.img
                    )
                    val (score, _) = DealScorer.score(item.title, item.price)
                    when (result) {
                        "new" -> { newCount++; log("  ${DealScorer.badge(score)}  $${item.price}  ${item.title.take(40)}") }
                        "price_drop" -> { drops++; log("  📉 PRICE DROP  $${item.price}  ${item.title.take(40)}") }
                    }
                }
                log("✓ Done. ${items.size} found, $newCount new, $drops price drops.")
                finish(items.size, newCount, drops)
            } catch (e: Exception) {
                log("✗ Error: ${e.message}")
                finish(0, 0, 0)
            }
        }
    }

    private fun finish(found: Int, newCount: Int, drops: Int) {
        scanning = false
        onDone(found, newCount, drops)
    }

    private suspend fun needsLogin(): Boolean {
        val res = evalJs(
            "(function(){" +
            "var f=document.querySelector('form[action*=\"login\"]');" +
            "var e=document.querySelector('input[name=\"email\"]');" +
            "return (f!==null||e!==null||location.href.indexOf('login')>-1)?'YES':'NO';})()"
        )
        return res.contains("YES")
    }

    private suspend fun evalJs(script: String): String =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { value ->
                cont.resume(value ?: "null")
            }
        }

    data class RawItem(val id: String, val title: String, val price: Int,
                       val location: String, val url: String, val img: String?)

    private fun parseExtraction(raw: String): List<RawItem> {
        // evaluateJavascript returns a JSON-encoded string; unwrap then parse array.
        val unwrapped = try { JSONTokener(raw).nextValue() as? String ?: return emptyList() }
        catch (e: Exception) { return emptyList() }
        val arr = try { JSONArray(unwrapped) } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<RawItem>()
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            val id = Regex("/marketplace/item/(\\d+)").find(url)?.groupValues?.get(1) ?: continue
            if (!seen.add(id)) continue
            val priceStr = o.optString("price")
            val price = Regex("[\\d,]+").find(priceStr)?.value?.replace(",", "")?.toIntOrNull() ?: continue
            out.add(
                RawItem(
                    id = id,
                    title = o.optString("title").ifBlank { "(no title)" },
                    price = price,
                    location = o.optString("loc"),
                    url = "https://www.facebook.com/marketplace/item/$id",
                    img = o.optString("img").ifBlank { null }
                )
            )
        }
        return out
    }

    companion object {
        // Same extraction approach as the Playwright version: anchor cards.
        private const val EXTRACT_JS = """
(function(){
  var out=[];
  var anchors=document.querySelectorAll('a[href*="/marketplace/item/"]');
  anchors.forEach(function(a){
    try{
      var lines=a.innerText.split('\n').map(function(s){return s.trim();})
        .filter(function(s){return s.length>0;});
      var price=null,title=null,loc=null;
      for(var i=0;i<lines.length;i++){
        if(price===null && /^\$[\d,]+/.test(lines[i])){price=lines[i];}
        else if(price!==null && title===null){title=lines[i];}
        else if(title!==null && loc===null){loc=lines[i];}
      }
      var img=a.querySelector('img');
      out.push({url:a.href.split('?')[0],price:price||'',title:title||'',loc:loc||'',img:img?img.src:''});
    }catch(e){}
  });
  return JSON.stringify(out);
})();
"""
    }
}
