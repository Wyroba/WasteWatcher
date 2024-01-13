package delta.Peter.Chi.WasteWatcher

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)

        Thread {
            // Initialize Redis client
            val redisClient = RedisClient.create("redis://default:FJXMsdIeaWspO8n0rbq84opxJE11dQku@redis-12838.c1.us-central1-2.gce.cloud.redislabs.com:12838")
            val connection: StatefulRedisConnection<String, String> = redisClient.connect()
            val syncCommands = connection.sync()

            try {
                // Generate a unique key for the new entry
                val newEntryKey = "product:${UUID.randomUUID()}"

                // Define the new entry data using Gson
                val newEntryObject = JsonObject()
                newEntryObject.addProperty("UPC", "060383825244")
                newEntryObject.addProperty("Date", "Jan 13 2023") // Change this to the current date if necessary
                newEntryObject.addProperty("Lot Number", "24456")
                val newEntryJson = Gson().toJson(newEntryObject)

                // Add the new entry to the database as JSON
                syncCommands.set(newEntryKey, newEntryJson)

                // Log the new entry action
                Log.d("RedisTest", "New entry added with key $newEntryKey: $newEntryJson")

                // Fetch all keys that start with 'product:'
                val keys = syncCommands.keys("product:*")
                val targetDate = "Jan 13 2023"
                val results = StringBuilder()

                for (key in keys) {
                    val json = syncCommands.get(key)
                    val jsonObject = Gson().fromJson(json, JsonObject::class.java)
                    val date = jsonObject.get("Date").asString
                    if (date == targetDate) {
                        results.append(json).append("\n\n")
                    }
                }

                // Check if any items were found
                val finalText = if (results.isEmpty()) {
                    "No item found"
                } else {
                    results.toString()
                }

                // Update the TextView
                runOnUiThread {
                    textView.text = finalText
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                connection.close()
                redisClient.shutdown()
            }
        }.start()
    }
}
