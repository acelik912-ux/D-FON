package com.acelik.asistan

import android.app.Activity
import android.os.Looper
import java.util.concurrent.CountDownLatch

/** Bloğu ana (arayüz) iş parçacığında çalıştırır ve sonucunu bekler. */
fun <T> Activity.onMain(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val latch = CountDownLatch(1)
    var result: Result<T>? = null
    runOnUiThread {
        result = runCatching(block)
        latch.countDown()
    }
    latch.await()
    return result!!.getOrThrow()
}
