package com.example.garnituretest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.garnituretest.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private var micPermissionGranted = false
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()){ isGranted ->
        micPermissionGranted = isGranted
    }
    private lateinit var viewBinding: ActivityMainBinding
    private var job: Job? = null

    private val buffer = ShortArray(636630)
    private var offset = 0

    private val minBufferSize = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private var audioRecord: AudioRecord? = null
    private val audioTrack = createAudioTrack()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)


        micPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED


        prepareUI()
    }


    private fun prepareUI(){
        with(viewBinding){
            /**REC**/
            btnRec.setOnClickListener {
                job?.cancel()

                if(!micPermissionGranted){
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@setOnClickListener
                }

                job = lifecycleScope.launch {
                    tvStatus.text = "REC"
                    recAudio()
                }
            }

            /**STOP**/
            btnStop.setOnClickListener {
                audioRecord?.stop()
                audioTrack.stop()

                job?.cancel()
                job = lifecycleScope.launch {
                    tvStatus.text = "STOP"
                }
            }

            /**PLAY**/
            btnPlay.setOnClickListener {
                job?.cancel()
                job = lifecycleScope.launch {
                    tvStatus.text = "PLAY"
                    playAudio()
                    tvStatus.text = "STOP"
                }
            }
        }
    }



    private suspend fun recAudio() = withContext(Dispatchers.IO){
        if(audioRecord == null) audioRecord = createAudioRecord()

        audioRecord?.startRecording()

        offset = 0

        while (isActive && offset < buffer.size) {
            offset += audioRecord?.read(buffer, offset, 160)?:0
        }
    }

    private suspend fun playAudio() = withContext(Dispatchers.IO){
        audioTrack.play()
        audioTrack.write(buffer, 0, offset)
    }




    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord{
        val minBufferSize = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            8000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 10)
    }

    private fun createAudioTrack(): AudioTrack{
        val attributesBuilder = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA)

        val formatBuilder = AudioFormat.Builder()
            .setSampleRate(8000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)

        return AudioTrack(
            attributesBuilder.build(),
            formatBuilder.build(),
            minBufferSize * 10,
            AudioTrack.MODE_STREAM,
            0)
    }
}