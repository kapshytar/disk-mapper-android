package com.kvita.diskmapper.ui

import android.util.Log

object UiTrace {
    private const val TAG = "DiskMapperTrace"

    fun input(message: String) {
        Log.i(TAG, "INPUT | $message")
    }

    fun ui(message: String) {
        Log.i(TAG, "UI | $message")
    }

    fun vm(message: String) {
        Log.i(TAG, "VM | $message")
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(TAG, "ERR | $message")
        } else {
            Log.e(TAG, "ERR | $message", throwable)
        }
    }
}
