package com.marcusrosado.stamp.data

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A build made from source: every feature, nothing to buy. */
@Suppress("UNUSED_PARAMETER")
fun proStore(context: Context, flags: Flags): ProStore = object : ProStore {
    override val sold = false
    override val unlocked: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    override val price: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    override fun buy(activity: Activity) = Unit
    override fun refresh() = Unit
}
