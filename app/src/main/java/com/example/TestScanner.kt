package com.example

import android.content.Context

fun testScanner(context: Context) {
   val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context)
}
