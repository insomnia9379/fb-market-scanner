package com.l7.dealscanner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Listing(
    val id: String,
    val title: String,
    val price: Int,
    val location: String,
    val url: String,
    val imgUrl: String?,
    val score: String,
    val model: String?,
    val prevPrice: Int?,
    val firstSeen: Long,
    val lastSeen: Long,
    val hidden: Boolean
)

class ListingDb(context: Context) :
    SQLiteOpenHelper(context, "listings.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE listings (
                id TEXT PRIMARY KEY,
                title TEXT,
                price INTEGER,
                location TEXT,
                url TEXT,
                img_url TEXT,
                score TEXT,
                model TEXT,
                prev_price INTEGER,
                first_seen INTEGER,
                last_seen INTEGER,
                hidden INTEGER DEFAULT 0
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    /** Insert or update. Returns "new", "price_drop", "price_up", or "seen". */
    fun upsert(id: String, title: String, price: Int, location: String, url: String, imgUrl: String?): String {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val (score, model) = DealScorer.score(title, price)

        val cur = db.rawQuery("SELECT price FROM listings WHERE id = ?", arrayOf(id))
        var result = "new"
        if (cur.moveToFirst()) {
            val oldPrice = cur.getInt(0)
            result = when {
                price < oldPrice -> "price_drop"
                price > oldPrice -> "price_up"
                else -> "seen"
            }
            val v = ContentValues().apply {
                put("title", title)
                put("price", price)
                put("location", location)
                put("score", score)
                put("model", model)
                put("last_seen", now)
                if (price != oldPrice) put("prev_price", oldPrice)
            }
            db.update("listings", v, "id = ?", arrayOf(id))
        } else {
            val v = ContentValues().apply {
                put("id", id)
                put("title", title)
                put("price", price)
                put("location", location)
                put("url", url)
                put("img_url", imgUrl)
                put("score", score)
                put("model", model)
                put("first_seen", now)
                put("last_seen", now)
                put("hidden", 0)
            }
            db.insert("listings", null, v)
        }
        cur.close()
        return result
    }

    fun getAll(filter: String? = null): List<Listing> {
        val db = readableDatabase
        val where = StringBuilder("hidden = 0")
        val args = mutableListOf<String>()
        if (filter != null && filter != "ALL") {
            where.append(" AND score = ?")
            args.add(filter)
        }
        val cur = db.rawQuery(
            """SELECT id,title,price,location,url,img_url,score,model,prev_price,first_seen,last_seen,hidden
               FROM listings WHERE $where
               ORDER BY CASE score WHEN 'STEAL' THEN 0 WHEN 'FAIR' THEN 1 WHEN 'UNKNOWN' THEN 2 ELSE 3 END,
               price ASC""",
            args.toTypedArray()
        )
        val out = mutableListOf<Listing>()
        while (cur.moveToNext()) {
            out.add(
                Listing(
                    id = cur.getString(0),
                    title = cur.getString(1) ?: "",
                    price = cur.getInt(2),
                    location = cur.getString(3) ?: "",
                    url = cur.getString(4) ?: "",
                    imgUrl = cur.getString(5),
                    score = cur.getString(6) ?: DealScorer.UNKNOWN,
                    model = cur.getString(7),
                    prevPrice = if (cur.isNull(8)) null else cur.getInt(8),
                    firstSeen = cur.getLong(9),
                    lastSeen = cur.getLong(10),
                    hidden = cur.getInt(11) == 1
                )
            )
        }
        cur.close()
        return out
    }

    fun hide(id: String) {
        val v = ContentValues().apply { put("hidden", 1) }
        writableDatabase.update("listings", v, "id = ?", arrayOf(id))
    }

    fun stats(): Map<String, Int> {
        val db = readableDatabase
        val out = mutableMapOf("total" to 0, "steals" to 0, "fair" to 0, "high" to 0, "hidden" to 0)
        val cur = db.rawQuery("SELECT score, hidden, COUNT(*) FROM listings GROUP BY score, hidden", null)
        while (cur.moveToNext()) {
            val score = cur.getString(0) ?: ""
            val hidden = cur.getInt(1) == 1
            val n = cur.getInt(2)
            out["total"] = out["total"]!! + n
            if (hidden) out["hidden"] = out["hidden"]!! + n
            else when (score) {
                DealScorer.STEAL -> out["steals"] = out["steals"]!! + n
                DealScorer.FAIR -> out["fair"] = out["fair"]!! + n
                DealScorer.HIGH -> out["high"] = out["high"]!! + n
            }
        }
        cur.close()
        return out
    }
}
