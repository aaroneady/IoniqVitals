package com.ioniqvitals.obd

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ObdDataRepository {

    private val _dataFlow = MutableSharedFlow<ObdSnapshot>(replay = 1, extraBufferCapacity = 1)
    val dataFlow: SharedFlow<ObdSnapshot> = _dataFlow.asSharedFlow()

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        _dataFlow.tryEmit(ObdSnapshot())
    }

    fun context(): Context = appContext

    suspend fun emit(snapshot: ObdSnapshot) {
        _dataFlow.emit(snapshot)
    }

    fun tryEmit(snapshot: ObdSnapshot) {
        _dataFlow.tryEmit(snapshot)
    }
}
