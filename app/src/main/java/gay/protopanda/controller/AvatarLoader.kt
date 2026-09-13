package gay.protopanda.controller

import android.graphics.BitmapFactory
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL

object AvatarLoader {
    fun load(imageView: ImageView, url: String) {
        Thread {
            val connection = URL(url).openConnection() as HttpURLConnection
            val bitmap = runCatching {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            connection.disconnect()
            if (bitmap != null) imageView.post { imageView.setImageBitmap(bitmap) }
        }.start()
    }
}
