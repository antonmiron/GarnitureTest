package com.example.garnituretest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
import java.lang.IllegalStateException
import java.util.concurrent.CancellationException

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
    private var audioTrack: AudioTrack? = null
    private val audioManager: AudioManager by lazy{ getSystemService(Context.AUDIO_SERVICE) as AudioManager }


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

                audioManager.startBluetoothSco()

                job = lifecycleScope.launch {
                    viewBinding.tvStatus.text = "RECORDING"
                    recAudio()
                    viewBinding.tvStatus.text = "STOPPED"
                }

                Log.d("ANTON","audioManager.isBluetoothScoOn: ${audioManager.isBluetoothScoOn}")
            }

            /**STOP**/
            btnStop.setOnClickListener {
                try {
                    audioRecord?.stop()
                    audioTrack?.stop()
                } catch (ex: IllegalStateException){}

                audioManager.stopBluetoothSco()

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
        try {
            audioRecord = createAudioRecord()

            audioRecord?.startRecording()
            Log.d("ANTON", "audioRecord: $audioRecord")

            offset = 0
            while (isActive && offset < buffer.size) {
                offset += audioRecord?.read(buffer, offset, 160)?:0
            }
        }
        catch (ex: CancellationException){ }
        finally { audioRecord?.release() }
    }

    private suspend fun playAudio() = withContext(Dispatchers.IO){
        try {
            audioTrack = createAudioTrack()
            Log.d("ANTON", "audioTrack: $audioTrack")
            audioTrack?.play()
            audioTrack?.write(buffer, 0, offset)
        }
        catch (ex: CancellationException){ }
        finally { audioTrack?.release() }
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