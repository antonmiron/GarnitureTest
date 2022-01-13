package com.example.garnituretest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log

class Broadcast(private val audioManager: AudioManager, private val callBack:(state: Int) -> Unit): BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if(intent == null || intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
        //Log.d("VVV", "onReceive: $intent")

        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
        Log.d("VVV", "Broadcast: state - $state (1-connected, 0 - disconnected, 2 - connecting)")
        callBack(state)

        when{
            state == AudioManager.SCO_AUDIO_STATE_CONNECTED && audioManager.isBluetoothScoAvailableOffCall-> {
                Log.d("VVV", "Broadcast: bluetooth on")
            }
            state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && audioManager.isBluetoothScoOn-> {
                Log.d("VVV", "Broadcast: bluetooth off")
            }
        }
    }
}