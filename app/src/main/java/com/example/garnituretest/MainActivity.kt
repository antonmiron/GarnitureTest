package com.example.garnituretest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.session.MediaSession
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.session.MediaButtonReceiver
import com.example.garnituretest.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.lang.IllegalStateException
import java.util.concurrent.CancellationException

class MainActivity : AppCompatActivity() {
    private var micPermissionGranted = false
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()){ _->
        micPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private lateinit var viewBinding: ActivityMainBinding
    private var job: Job? = null

    private val buffer = ShortArray(636630)
    private var offset = 0

    private val minBufferSize = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val audioManager: AudioManager by lazy{ getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val adapter = Adapter()

    private val mediaSessionCallback: MediaSession.Callback = object: MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {

            val mediaButtonAction = mediaButtonIntent.action
            val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)

            adapter.addLog("onMediaButton action: $mediaButtonAction")
            adapter.addLog("onMediaButton key event: $event")

            return super.onMediaButtonEvent(mediaButtonIntent)
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            super.onCustomAction(action, extras)
            adapter.addLog("onCustomAction action: $action")
            adapter.addLog("onCustomAction extras: $extras")
        }

        override fun onCommand(command: String, args: Bundle?, cb: ResultReceiver?) {
            super.onCommand(command, args, cb)
            adapter.addLog("onCommand command: $command")
            adapter.addLog("onCommand args: $args")
            adapter.addLog("onCommand cb: $cb")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        micPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        prepareUI()

        createAudioTrack().apply {
            play()
            release()
        }

        var mediaSession: MediaSession? = null
        val bluetoothProfileListener = object: BluetoothProfile.ServiceListener{
            override fun onServiceConnected(p0: Int, p1: BluetoothProfile?) {
                adapter.addLog("onServiceConnected p0:$p0, p1:$p1")

                mediaSession = MediaSession(this@MainActivity, "ANTON")
                mediaSession?.isActive = true
                mediaSession?.setCallback(mediaSessionCallback)
            }

            override fun onServiceDisconnected(p0: Int) {
                adapter.addLog("onServiceDisconnected p0:$p0")
                mediaSession?.release()
            }

        }
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(this, bluetoothProfileListener, BluetoothProfile.A2DP)
    }


    private fun prepareUI(){
        with(viewBinding){
            /** LOGS RV **/
            rvLogs.adapter = adapter

            /** LOGS COPY BTN **/
            btnCopy.setOnClickListener {
                val logs = adapter.getLogs().joinToString("\n")
                val clipboardService = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboardService.setPrimaryClip(ClipData.newPlainText("logs", logs))
                Toast.makeText(this@MainActivity, "Logs copied!", Toast.LENGTH_SHORT).show()
            }

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
        finally {
            delay(100)
            audioTrack?.release() }
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