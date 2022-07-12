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
import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus
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

    val SAMPLE_RATE = Constants.SampleRate._48000()       // samlpe rate of the input audio
    val CHANNELS = Constants.Channels.stereo()            // type of the input audio mono or stereo
    val APPLICATION = Constants.Application.audio()       // coding mode of the encoder
    var FRAME_SIZE = Constants.FrameSize._120()           // default frame size for 48000Hz

    val codec = Opus()                                    // getting an instance of Codec

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)


        micPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED


        prepareUI()

        codec.encoderInit(SAMPLE_RATE, CHANNELS, APPLICATION) // init encoder
        codec.decoderInit(SAMPLE_RATE, CHANNELS)              // init decoder
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

    private fun addInBuffer(shortArray: ShortArray, offset: Int){
        for(index in offset until offset+shortArray.size){
            buffer[index] = shortArray[index-offset]
        }
    }

    private suspend fun recAudio() = withContext(Dispatchers.IO){
        if(audioRecord == null) audioRecord = createAudioRecord()

        audioRecord?.startRecording()

        offset = 0

        while (isActive && offset < buffer.size) {
            val localBuffer = ShortArray(512)
            offset += audioRecord?.read(localBuffer, 0, 512)?:0

            val encoded = codec.encode(localBuffer, FRAME_SIZE)?:return@withContext
            addInBuffer(encoded, offset)
        }
    }

    private suspend fun playAudio() = withContext(Dispatchers.IO){
        audioTrack.play()

        val localBuffer = codec.decode(buffer, FRAME_SIZE)?:return@withContext
        audioTrack.write(localBuffer, 0, offset)
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