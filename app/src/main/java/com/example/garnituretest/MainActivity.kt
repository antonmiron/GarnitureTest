package com.example.garnituretest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.garnituretest.databinding.ActivityMainBinding
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import java.lang.Math.log10

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
    val le = LoudnessEnhancer(audioTrack.audioSessionId).apply { enabled = true }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)


        micPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val x: Short = Short.MAX_VALUE.dec()
        Log.d("ANTON", "x: $x")
        val w = x.inc().inc()
        Log.d("ANTON", "w: $w")
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
                    playChangedAudio()
//                    playAudio()
                    tvStatus.text = "STOP"
                }
            }

            /**Change volume**/
            seekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
//                    var p = (progress+1)/5.0f
//                    if(p<=0.4) p = 0.01f
                    var p = progress*2000
                    Log.d("ANTON", "progress: $progress, p: $p")
                    le.setTargetGain(p)


                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }



    private suspend fun recAudio() = withContext(Dispatchers.IO){
        audioRecord = createAudioRecord()

        audioRecord?.startRecording()

        offset = 0

        while (isActive && offset < buffer.size) {
            offset += audioRecord?.read(buffer, offset, 160)?:0
        }
    }

    private suspend fun playAudio() = withContext(Dispatchers.IO){
        Log.d("ANTON", "playAudio max amplitude: ${buffer.maxOrNull()}")

        audioTrack.play()
        audioTrack.write(buffer, 0, offset)
    }

    private suspend fun playChangedAudio() = withContext(Dispatchers.IO){

        audioTrack.play()
        audioTrack.write(buffer, 0, offset)
    }



    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord{
        val minBufferSize = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        val source = if(switchAudio.isChecked) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.MIC

        Log.d("ANTON", "source: $source")

        return AudioRecord(
            source,
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