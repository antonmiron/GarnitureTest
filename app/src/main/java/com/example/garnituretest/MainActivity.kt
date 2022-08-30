package com.example.garnituretest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
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
import com.google.android.material.slider.Slider
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.lang.Math.log10

class MainActivity : AppCompatActivity() {
    private var micPermissionGranted = false
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()){ isGranted ->
        micPermissionGranted = isGranted
    }
    private val pickSoundLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()){ uri ->
        Log.d("ANTON", "uri: $uri")
        pickSound(uri)
    }
    private lateinit var viewBinding: ActivityMainBinding
    private var job: Job? = null

    private var soundUri: Uri? = null
    private val minBufferSize = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
            /**PICK SOUND**/
            btnPickSound.setOnClickListener {
                job?.cancel()
                pickSoundLauncher.launch(arrayOf("audio/x-wav"))
            }

            /**STOP**/
            btnStop.setOnClickListener {
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
                    soundUri?.let {
                        tvStatus.text = "PLAY"
                        playAudio(contentResolver.openInputStream(it))
                    }
                    tvStatus.text = "STOP"

                }
            }

            /**Change volume**/
            seekBar.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
                val gainCoefficient = getGainCoefficientForIncomingCall(value)
                le.setTargetGain(gainCoefficient)
            })
        }
    }





    private suspend fun playAudio(inputStream: InputStream?) = withContext(Dispatchers.IO){
        inputStream?:return@withContext

        val buffer = ByteArray(minBufferSize)
        audioTrack.play()
        do{
            val offset = inputStream.read(buffer)
            audioTrack.write(buffer, 0, offset)
        }while (offset > 0)
    }


    private fun pickSound(uri: Uri){
        soundUri = uri
        tvStatus.text = File(uri.path?:"Unknown").name
    }

    /**
     * Convert settings value (from -15 to 15) to millibel (1 decibel == 100 millibel)
     * Setting`s value 0 equals 0 dB, corresponds to no amplification
     *
     * @return millibel for using in [LoudnessEnhancer.setTargetGain]
     * **/
    private fun getGainCoefficientForIncomingCall(settingsValue: Float?): Int{
        return when(settingsValue){
            15f -> 1500
            12f -> 1200
            9f -> 900
            6f -> 600
            3f -> 300
            0f -> 0
            -3f -> -300
            -6f -> -600
            -9f -> -900
            -12f -> -1200
            -15f -> -1500

            else -> 0
        }
    }



    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord{
        val minBufferSize = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
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