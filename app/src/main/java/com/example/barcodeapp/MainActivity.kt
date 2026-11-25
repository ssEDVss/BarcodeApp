package com.example.barcodeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.barcodeapp.ui.theme.BarcodeAppTheme
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BarcodeAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ScannerScreen()
                }
            }
        }
    }
}

// ---------- База данных (SQLite) ----------

data class Review(val rating: Int, val text: String, val store: String, val price: Double?)

class ReviewDb(context: Context) : SQLiteOpenHelper(context, "reviews.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE reviews (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "barcode TEXT NOT NULL, " +
                    "rating INTEGER NOT NULL, " +
                    "text TEXT, " +
                    "store TEXT, " +
                    "price REAL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS reviews")
        onCreate(db)
    }

    fun add(barcode: String, rating: Int, text: String, store: String, price: Double?) {
        val values = ContentValues().apply {
            put("barcode", barcode)
            put("rating", rating)
            put("text", text)
            put("store", store)
            if (price != null) put("price", price) else putNull("price")
        }
        writableDatabase.insert("reviews", null, values)
    }

    fun getAll(barcode: String): List<Review> {
        val list = mutableListOf<Review>()
        readableDatabase.rawQuery(
            "SELECT rating, text, store, price FROM reviews WHERE barcode = ? ORDER BY id DESC",
            arrayOf(barcode)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Review(
                        c.getInt(0),
                        c.getString(1) ?: "",
                        c.getString(2) ?: "",
                        if (c.isNull(3)) null else c.getDouble(3)
                    )
                )
            }
        }
        return list
    }
}

// ---------- Запрос к Open Food Facts ----------

suspend fun fetchProduct(barcode: String): String = withContext(Dispatchers.IO) {
    try {
        val url = URL("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=product_name,brands,quantity")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "BarcodeApp/1.0 (student project)")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        if (json.optInt("status") == 1) {
            val p = json.getJSONObject("product")
            val name = p.optString("product_name", "").ifBlank { "Без названия" }
            val brand = p.optString("brands", "")
            val qty = p.optString("quantity", "")
            "Название: $name\nБренд: $brand\nОбъём: $qty"
        } else {
            "Товар не найден в базе"
        }
    } catch (e: Exception) {
        "Ошибка: ${e.message}"
    }
}

// ---------- Экран ----------

@Composable
fun ScannerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { ReviewDb(context) }

    var code by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("Штрихкод не отсканирован") }
    var reviews by remember { mutableStateOf(listOf<Review>()) }

    var rating by remember { mutableStateOf(0) }
    var reviewText by remember { mutableStateOf("") }
    var store by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Button(onClick = {
            GmsBarcodeScanning.getClient(context)
                .startScan()
                .addOnSuccessListener { barcode ->
                    val value = barcode.rawValue ?: ""
                    code = value
                    reviews = db.getAll(value)
                    info = "Ищем товар..."
                    scope.launch { info = fetchProduct(value) }
                }
                .addOnFailureListener { e ->
                    info = "Ошибка: ${e.message}"
                }
        }) {
            Text("Сканировать")
        }

        Spacer(Modifier.height(16.dp))
        Text("Код: $code")
        Spacer(Modifier.height(8.dp))
        Text(info)

        if (code.isNotBlank()) {
            Spacer(Modifier.height(16.dp))

            // Сводка по сохранённым отзывам
            if (reviews.isNotEmpty()) {
                val avg = reviews.map { it.rating }.average()
                val minPrice = reviews.mapNotNull { it.price }.minOrNull()
                Text("Средняя оценка: ${"%.1f".format(avg)} (отзывов: ${reviews.size})")
                if (minPrice != null) Text("Минимальная цена: $minPrice ₽")
            } else {
                Text("Отзывов пока нет")
            }

            Spacer(Modifier.height(24.dp))
            Text("Оставить отзыв")
            Spacer(Modifier.height(8.dp))

            // Выбор оценки 1-5
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 1..5) {
                    if (rating == i) {
                        Button(onClick = { rating = i }) { Text("$i") }
                    } else {
                        OutlinedButton(onClick = { rating = i }) { Text("$i") }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = reviewText,
                onValueChange = { reviewText = it },
                label = { Text("Текст отзыва") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = store,
                onValueChange = { store = it },
                label = { Text("Магазин") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = priceText,
                onValueChange = { priceText = it },
                label = { Text("Цена, ₽") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            Button(
                enabled = rating > 0,
                onClick = {
                    val price = priceText.replace(',', '.').toDoubleOrNull()
                    db.add(code, rating, reviewText, store, price)
                    reviews = db.getAll(code)
                    rating = 0
                    reviewText = ""
                    store = ""
                    priceText = ""
                }
            ) {
                Text("Сохранить")
            }

            Spacer(Modifier.height(24.dp))
            reviews.forEach { r ->
                val priceLine = r.price?.let { " · $it ₽" } ?: ""
                val storeLine = if (r.store.isNotBlank()) " · ${r.store}" else ""
                Text("★${r.rating}$storeLine$priceLine")
                if (r.text.isNotBlank()) Text(r.text)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}