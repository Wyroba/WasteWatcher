package delta.Peter.Chi.WasteWatcher

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import redis.clients.jedis.Jedis
import java.util.*

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Thread {
            // Connect to Redis
            val jedis = Jedis("redis-12838.c1.us-central1-2.gce.cloud.redislabs.com", 12838)
            try {
                jedis.auth("default", "FJXMsdIeaWspO8n0rbq84opxJE11dQku")

                // Generate a unique key for the entry
                val entryKey = "product:${UUID.randomUUID()}"

                // Define the entry data
                val entryData = mapOf(
                    "UPC" to "060383825244",
                    "Date" to "Jan 13 2023",
                    "Lot Number" to "24456"
                )

                // Add the new entry to the database
                jedis.hmset(entryKey, entryData)

                // Log the action
                Log.d("RedisTest", "New entry added with key $entryKey: $entryData")

                // Optional: Retrieve and log the entry
                val retrievedEntry = jedis.hgetAll(entryKey)
                Log.d("RedisTest", "Retrieved entry: $retrievedEntry")
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                jedis.close()
            }
        }.start()
    }
}